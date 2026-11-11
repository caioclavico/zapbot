(ns pokemon.infrastructure.http.maintenance-gate
  "Fail-closed, process-local bridge gate with durable maintenance state.

  Phases (verified conditions, not mere labels):
  - :open — mutations and safe reads admitted
  - :draining — mutations closed; in-flight writes drain; safe reads allowed
  - :quiescent — zero in-flight writes; safe reads allowed; ready to freeze
  - :frozen — no writers and no game reads; ready for controlled shutdown

  The state file must live on the persistent maintenance volume colocated with
  Datomic storage. Unknown/corrupt markers never reopen writes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.species-hp-freeze :as freeze-ops])
  (:import [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.nio.channels FileChannel]
           [java.time Instant]))

(def ^:private valid-phases #{:open :draining :quiescent :frozen})

(defn- state-file [dir] (io/file dir "bridge-maintenance.edn"))

(defn- now-iso [] (str (Instant/now)))

(defn- persist! [dir state]
  (let [root (io/file dir)
        target (state-file dir)
        tmp (io/file root (str ".bridge-maintenance-" (java.util.UUID/randomUUID) ".tmp"))]
    (when-not (.isDirectory root)
      (Files/createDirectories (.toPath root) (make-array java.nio.file.attribute.FileAttribute 0)))
    (spit tmp (pr-str state))
    (with-open [ch (FileChannel/open (.toPath tmp)
                                     (into-array StandardOpenOption
                                                 [StandardOpenOption/WRITE]))]
      (.force ch true))
    (Files/move (.toPath tmp) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
    state))

(defn- load-state [dir]
  (let [f (state-file dir)
        interrupted-write? (try
                             (some #(re-find #"^\.bridge-maintenance-.*\.tmp$" (.getName %))
                                   (.listFiles (io/file dir)))
                             (catch Throwable _ false))]
    (if interrupted-write?
      {:phase :draining :reason "interrupted-maintenance-state-write" :updated-at (now-iso)}
      (if-not (.exists f)
        {:phase :open :updated-at (now-iso)}
        (try
          (let [s (edn/read-string (slurp f))]
            (if (contains? valid-phases (:phase s))
              s
              {:phase :quiescent :reason "invalid-maintenance-state" :updated-at (now-iso)}))
          (catch Throwable _
            ;; A malformed marker must never silently reopen writes.
            {:phase :quiescent :reason "unreadable-maintenance-state" :updated-at (now-iso)}))))))

(defn create
  "Creates a gate. A persisted draining/quiescent/frozen state survives JVM restart.
   In-memory counters reset on restart — quiescence after crash requires a fresh
   enter/freeze cycle and receipt/journal reconciliation, never a presumed zero."
  [dir]
  (when-not (and (string? dir) (not (str/blank? dir)))
    (throw (ex-info "maintenance_dir_required" {})))
  {:dir dir
   :lock (Object.)
   :state (atom (load-state dir))
   :active-writes (atom 0)
   :active-reads (atom 0)
   :admitted-writes (atom 0)
   :completed-writes (atom 0)
   :failed-writes (atom 0)
   :rejected-writes (atom 0)
   :admitted-reads (atom 0)
   :completed-reads (atom 0)
   :rejected-reads (atom 0)})

(defn- accounting [gate]
  {:active-writes @(:active-writes gate)
   :active-reads @(:active-reads gate)
   ;; Legacy alias used by existing callers/status payloads.
   :active-requests @(:active-writes gate)
   :admitted-writes @(:admitted-writes gate)
   :completed-writes @(:completed-writes gate)
   :failed-writes @(:failed-writes gate)
   :rejected-writes @(:rejected-writes gate)
   :admitted-reads @(:admitted-reads gate)
   :completed-reads @(:completed-reads gate)
   :rejected-reads @(:rejected-reads gate)})

(defn status [gate]
  (locking (:lock gate)
    (merge @(:state gate) (accounting gate))))

(defn mutation-request?
  "Fail-closed classifier: unknown methods/paths are treated as mutations."
  [method path]
  (let [m (str/upper-case (str (or method "")))
        p (str (or path ""))]
    (cond
      ;; Maintenance control and health are never game mutations.
      (or (= p "/health") (str/starts-with? p "/ops/maintenance/")) false
      ;; Explicit write methods always mutate.
      (contains? #{"POST" "PUT" "PATCH" "DELETE"} m) true
      (= "GET" m)
      (let [;; Map common GET routes onto freeze-op keywords when possible.
            op (cond
                 (str/starts-with? p "/v1/trainer") :trainer-read
                 (str/starts-with? p "/v1/collection") :collection-read
                 (str/starts-with? p "/v1/active-save") :active-save-read
                 (= p "/v1/saves") :saves-list
                 (str/starts-with? p "/v1/missions") :missions-read
                 (str/starts-with? p "/v1/gyms") :gyms-read
                 (str/starts-with? p "/v1/raid") :raid-read
                 (str/starts-with? p "/v1/encounter") :encounter-read
                 (str/starts-with? p "/v1/joy") :joy-read
                 (str/starts-with? p "/v1/shop") :shop-read
                 (str/starts-with? p "/v1/inventory") :inventory-read
                 (str/starts-with? p "/v1/adventure") :adventure-read
                 (str/starts-with? p "/v1/pvp/active") :pvp-active-read
                 (str/starts-with? p "/v1/onboarding") :onboarding-read
                 (str/starts-with? p "/v1/operations") :operation-get
                 (str/starts-with? p "/v1/events") :events-pending
                 (str/starts-with? p "/v1/profile") :profile-friendship
                 (str/starts-with? p "/v1/named-teams") :named-teams-read
                 (str/starts-with? p "/v1/professor") :professor-cards
                 (str/starts-with? p "/v1/platform") :platform-blocks
                 :else nil)]
        (if op
          (not (freeze-ops/allowed-during-freeze? op))
          ;; Unknown GET → treat as mutation (fail closed).
          true))
      :else true)))

(defn scheduler-tick-path?
  "Paths that advance raid/thematic schedulers — always mutations."
  [path]
  (contains? #{"/v1/raids/auto/tick" "/v1/thematic-events/tick"} (str path)))

(defn acquire-write!
  "Admit a durable mutation. Increments the write counter before returning true
   so enter! cannot race past an in-flight admission."
  [gate]
  (locking (:lock gate)
    (if (= :open (:phase @(:state gate)))
      (do (swap! (:admitted-writes gate) inc)
          (swap! (:active-writes gate) inc)
          true)
      (do (swap! (:rejected-writes gate) inc)
          false))))

(defn acquire-read!
  "Admit a classified safe read while open/draining/quiescent. Blocked when frozen."
  [gate]
  (locking (:lock gate)
    (let [phase (:phase @(:state gate))]
      (if (contains? #{:open :draining :quiescent} phase)
        (do (swap! (:admitted-reads gate) inc)
            (swap! (:active-reads gate) inc)
            true)
        (do (swap! (:rejected-reads gate) inc)
            false)))))

(defn acquire!
  "Backward-compatible write admission (legacy callers / idle sweeper)."
  [gate]
  (acquire-write! gate))

(defn acquire-for!
  "Admit according to HTTP method/path classification."
  [gate method path]
  (if (or (mutation-request? method path) (scheduler-tick-path? path))
    (acquire-write! gate)
    (acquire-read! gate)))

(defn release-write!
  "Decrement in-flight writes. outcome :ok|:error updates completed/failed counters."
  ([gate] (release-write! gate :ok))
  ([gate outcome]
   (locking (:lock gate)
     (swap! (:active-writes gate) #(max 0 (dec %)))
     (if (= :error outcome)
       (swap! (:failed-writes gate) inc)
       (swap! (:completed-writes gate) inc))
     (.notifyAll ^Object (:lock gate)))
   nil))

(defn release-read!
  [gate]
  (locking (:lock gate)
    (swap! (:active-reads gate) #(max 0 (dec %)))
    (swap! (:completed-reads gate) inc))
  nil)

(defn release!
  "Backward-compatible write release."
  ([gate] (release-write! gate :ok))
  ([gate outcome] (release-write! gate outcome)))

(defn release-for!
  [gate method path & [outcome]]
  (if (or (mutation-request? method path) (scheduler-tick-path? path))
    (release-write! gate (or outcome :ok))
    (release-read! gate)))

(defn writes-allowed?
  [gate]
  (= :open (:phase (status gate))))

(defn ticks-allowed?
  "New scheduler ticks only while open. In-flight admitted ticks may finish."
  [gate]
  (writes-allowed? gate))

(defn enter!
  "Close mutation admission (draining), wait for active writes, then quiescent.
   Safe reads may continue; they do not block quiescence."
  [gate timeout-ms]
  (let [deadline (+ (System/nanoTime) (* 1000000 (max 0 (long timeout-ms))))]
    (locking (:lock gate)
      (let [phase (:phase @(:state gate))]
        (when (= :frozen phase)
          (throw (ex-info "maintenance_enter_forbidden_while_frozen"
                          {:phase phase})))
        (when (contains? #{:open :draining} phase)
          (let [draining (merge @(:state gate)
                                {:phase :draining :started-at (now-iso)})]
            ;; Close the in-memory gate before touching the filesystem. If persistence
            ;; fails, this JVM remains write-blocked and the operator must abort.
            (reset! (:state gate) draining)
            (reset! (:state gate) (persist! (:dir gate) draining)))))
      (loop []
        (if (zero? @(:active-writes gate))
          (let [next (persist! (:dir gate)
                               (assoc @(:state gate) :phase :quiescent
                                      :quiescent-at (now-iso)
                                      :active-writes 0))]
            (reset! (:state gate) next)
            (merge {:quiescent? true :frozen? false :phase :quiescent}
                   (accounting gate)
                   {:state next}))
          (let [remaining (- deadline (System/nanoTime))]
            (if (pos? remaining)
              (do (.wait ^Object (:lock gate)
                         (long (max 1 (quot remaining 1000000))))
                  (recur))
              (merge {:quiescent? false :frozen? false
                      :phase (:phase @(:state gate))}
                     (accounting gate)
                     {:state @(:state gate)}))))))))

(defn freeze!
  "Mark quiescent system as frozen (no game reads/writes). Requires verified
   quiescent phase and zero in-flight writes."
  [gate]
  (locking (:lock gate)
    (let [phase (:phase @(:state gate))
          active @(:active-writes gate)]
      (when-not (and (= :quiescent phase) (zero? active))
        (throw (ex-info "maintenance_freeze_requires_quiescence"
                        {:phase phase :active-writes active})))
      (let [next (persist! (:dir gate)
                           {:phase :frozen
                            :frozen-at (now-iso)
                            :quiescent-at (:quiescent-at @(:state gate))})]
        (reset! (:state gate) next)
        (merge next (accounting gate))))))

(defn resume!
  "Reopen only from quiescent or frozen with zero in-flight writes."
  [gate]
  (locking (:lock gate)
    (let [phase (:phase @(:state gate))
          active @(:active-writes gate)]
      (when-not (and (contains? #{:quiescent :frozen} phase) (zero? active))
        (throw (ex-info "maintenance_resume_requires_quiescence"
                        {:phase phase :active-requests active :active-writes active})))
      (let [next (persist! (:dir gate)
                           {:phase :open :resumed-at (now-iso)})]
        (reset! (:state gate) next)
        next))))
