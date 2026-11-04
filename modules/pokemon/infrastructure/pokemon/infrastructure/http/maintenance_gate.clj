(ns pokemon.infrastructure.http.maintenance-gate
  "Fail-closed, process-local bridge gate with durable maintenance state.
   The state file must live on the persistent maintenance volume."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.nio.channels FileChannel]
           [java.time Instant]))

(defn- state-file [dir] (io/file dir "bridge-maintenance.edn"))

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
      {:phase :draining :reason "interrupted-maintenance-state-write" :updated-at (str (Instant/now))}
      (if-not (.exists f)
        {:phase :open :updated-at (str (Instant/now))}
        (try
          (let [s (edn/read-string (slurp f))]
            (if (contains? #{:open :draining :quiescent} (:phase s))
              s
              {:phase :quiescent :reason "invalid-maintenance-state" :updated-at (str (Instant/now))}))
          (catch Throwable _
            ;; A malformed marker must never silently reopen writes.
            {:phase :quiescent :reason "unreadable-maintenance-state" :updated-at (str (Instant/now))}))))))

(defn create
  "Creates a gate. A persisted draining/quiescent state survives JVM restart."
  [dir]
  (when-not (and (string? dir) (not (str/blank? dir)))
    (throw (ex-info "maintenance_dir_required" {})))
  {:dir dir :lock (Object.) :state (atom (load-state dir)) :active (atom 0)})

(defn acquire! [gate]
  (locking (:lock gate)
    (when (= :open (:phase @(:state gate)))
      (swap! (:active gate) inc)
      true)))

(defn release! [gate]
  (locking (:lock gate)
    (swap! (:active gate) #(max 0 (dec %)))
    (.notifyAll ^Object (:lock gate)))
  nil)

(defn status [gate]
  (locking (:lock gate)
    (assoc @(:state gate) :active-requests @(:active gate))))

(defn enter! [gate timeout-ms]
  (let [deadline (+ (System/nanoTime) (* 1000000 (max 0 (long timeout-ms))))]
    (locking (:lock gate)
      (when (= :open (:phase @(:state gate)))
        (let [draining {:phase :draining :started-at (str (Instant/now))}]
          ;; Close the in-memory gate before touching the filesystem. If persistence
          ;; fails, this JVM remains write-blocked and the operator must abort.
          (reset! (:state gate) draining)
          (reset! (:state gate) (persist! (:dir gate) draining))))
      (loop []
        (if (zero? @(:active gate))
          (let [next (persist! (:dir gate)
                               (assoc @(:state gate) :phase :quiescent
                                      :quiescent-at (str (Instant/now))))]
            (reset! (:state gate) next)
            {:quiescent? true :active-requests 0 :state next})
          (let [remaining (- deadline (System/nanoTime))]
            (if (pos? remaining)
              (do (.wait ^Object (:lock gate)
                         (long (max 1 (quot remaining 1000000))))
                  (recur))
              {:quiescent? false :active-requests @(:active gate)
               :state @(:state gate)})))))))

(defn resume! [gate]
  (locking (:lock gate)
    (when-not (and (= :quiescent (:phase @(:state gate)))
                   (zero? @(:active gate)))
      (throw (ex-info "maintenance_resume_requires_quiescence"
                      {:phase (:phase @(:state gate)) :active-requests @(:active gate)})))
    (let [next (persist! (:dir gate)
                         {:phase :open :resumed-at (str (Instant/now))})]
      (reset! (:state gate) next)
      next)))
