(ns a6.progressive-xp-v3-audit
  "RO audit of an ISOLATED cold-backup restore. No transact, create-db or delete.
  Run with A6_V3_ISOLATED_RESTORE=confirmed and A6_V3_AUDIT_STORAGE=<copy>.
  Never point this at the live volume."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.growth :as growth]))

(def ^:private immutable
  [:id-pokemon :species :nome :nivel :hp :hp-atual :ataque :defesa
   :atq-esp :def-esp :veloc :shiny])

(def ^:private pull-pattern
  '[:pokemon/id-pokemon :pokemon/species :pokemon/nome :pokemon/level
    :pokemon/xp :pokemon/xp-desde-nivel :pokemon/xp-total
    :pokemon/xp-progress-version :pokemon/growth-rate
    :pokemon/hp :pokemon/hp-atual :pokemon/ataque :pokemon/defesa
    :pokemon/atq-esp :pokemon/def-esp :pokemon/veloc :pokemon/shiny])

(defn- sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str
           (map #(format "%02x" (bit-and 255 (int %)))
                (.digest md (.getBytes (str s) "UTF-8"))))))

(defn- from-entity [p]
  (cond-> {:id-pokemon (:pokemon/id-pokemon p)
           :species (:pokemon/species p)
           :nome (:pokemon/nome p)
           :nivel (:pokemon/level p)}
    (some? (:pokemon/xp p)) (assoc :xp (long (:pokemon/xp p)))
    (some? (:pokemon/xp-desde-nivel p))
    (assoc :xp-desde-nivel (long (:pokemon/xp-desde-nivel p)))
    (some? (:pokemon/xp-total p)) (assoc :xp-total (long (:pokemon/xp-total p)))
    (some? (:pokemon/xp-progress-version p))
    (assoc :xp-progress-version (:pokemon/xp-progress-version p))
    (some? (:pokemon/growth-rate p))
    (assoc :growth-rate (keyword (:pokemon/growth-rate p)))
    (some? (:pokemon/hp p)) (assoc :hp (:pokemon/hp p))
    (some? (:pokemon/hp-atual p)) (assoc :hp-atual (:pokemon/hp-atual p))
    (some? (:pokemon/ataque p)) (assoc :ataque (:pokemon/ataque p))
    (some? (:pokemon/defesa p)) (assoc :defesa (:pokemon/defesa p))
    (some? (:pokemon/atq-esp p)) (assoc :atq-esp (:pokemon/atq-esp p))
    (some? (:pokemon/def-esp p)) (assoc :def-esp (:pokemon/def-esp p))
    (some? (:pokemon/veloc p)) (assoc :veloc (:pokemon/veloc p))
    (some? (:pokemon/shiny p)) (assoc :shiny (:pokemon/shiny p))))

(defn- probe [r]
  (try
    (when (or (nil? (:id-pokemon r)) (nil? (:nivel r)))
      (throw (ex-info "Missing identity or level" {:code :missing-id-or-level})))
    (let [next (growth/ensure-progressive-v3-for-grant r)
          twice (growth/ensure-progressive-v3-for-grant next)
          n (long (:nivel r))
          needed (growth/progressive-xp-por-nivel n)
          residual (long (:xp-desde-nivel next))
          immutable-ok (= (select-keys r immutable) (select-keys next immutable))
          same-again? (= next twice)
          xp-range-ok (if (= n 100) (zero? residual)
                        (and (<= 0 residual) (< residual needed)))]
      (if (and immutable-ok same-again? xp-range-ok)
        {:status :ok :before r :after next}
        {:status :blocked
         :reason (cond (not immutable-ok) :immutable-drift
                       (not same-again?) :not-idempotent
                       :else :invalid-residual)}))
    (catch Exception e
      {:status :blocked
       :reason (or (:code (ex-data e)) :conversion-error)})))

(defn- attr-exists? [db attr]
  ;; Datomic Local client rejects scalar find (`.`); pull by ident is the
  ;; established A6 pattern (see a6_fase7_integrity_manifest / flat9 audits).
  (some? (:db/ident (d/pull db '[:db/ident] attr))))

(defn- count-attr [db attr]
  (if (attr-exists? db attr)
    (long (or (ffirst (d/q '[:find (count ?e) :in $ ?a :where [?e ?a]]
                             db attr)) 0))
    0))

(defn- sum-attr [db attr]
  (if (attr-exists? db attr)
    (long (or (ffirst (d/q '[:find (sum ?v) :in $ ?a :where [?e ?a ?v]]
                             db attr)) 0))
    0))

(defn audit! [db]
  (let [ids (mapv first (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db))
        rows (->> ids (map #(from-entity (d/pull db pull-pattern %)))
                  (sort-by #(str (:id-pokemon %))) vec)
        checks (mapv probe rows)
        blocked (filterv #(= :blocked (:status %)) checks)
        ok (filterv #(= :ok (:status %)) checks)
        summary {:result (if (empty? blocked) :AUDIT_PASS :AUDIT_BLOCKED)
                 :pokemon-count (count rows)
                 :player-count (max (count-attr db :player/key)
                                    (count-attr db :trainer/player-id))
                 :save-count (count-attr db :save/key)
                 :egg-count (count-attr db :egg/id)
                 :coins-total (sum-attr db :save/coins)
                 :by-version (frequencies
                              (map #(or (:xp-progress-version %) "unversioned") rows))
                 :converted (count ok)
                 :blocked (count blocked)
                 :blocked-reasons (frequencies (map :reason blocked))
                 :before-fingerprint
                 (sha256 (pr-str (mapv #(select-keys % (conj immutable
                                                               :xp-desde-nivel
                                                               :xp-progress-version))
                                        rows)))
                 :unchanged-fingerprint
                 (sha256 (pr-str (mapv #(select-keys (:after %) immutable) ok)))
                 :invariant-check :no-level-or-immutable-change
                 :transaction-count 0}]
    summary))

(defn -main [& _]
  (when-not (= "confirmed" (System/getenv "A6_V3_ISOLATED_RESTORE"))
    (throw (ex-info "Requires explicit confirmation of isolated restore"
                    {:code :isolated-restore-required})))
  (let [storage (System/getenv "A6_V3_AUDIT_STORAGE")
        _ (when (str/blank? storage)
            (throw (ex-info "A6_V3_AUDIT_STORAGE is required" {})))
        system (or (System/getenv "A6_V3_AUDIT_SYSTEM") "odisseu-m90-core")
        db-name (or (System/getenv "A6_V3_AUDIT_DB") "core")
        _ (when-not (.isDirectory (io/file storage))
            (throw (ex-info "Isolated restore storage does not exist" {})))
        ;; External live guard rejects canonical production storage.
        client (persistence/client
                {:system system :storage-dir storage :maintenance-control? true})
        names (set (d/list-databases client {}))
        _ (when-not (contains? names db-name)
            (throw (ex-info "Database missing: never create a DB during audit"
                            {:db-name db-name})))
        conn (d/connect client {:db-name db-name})
        result (audit! (d/db conn))
        target (or (System/getenv "A6_V3_AUDIT_REPORT")
                   "/tmp/a6-progressive-xp-v3-audit.edn")]
    (spit (io/file target) (pr-str result))
    (.setReadable (io/file target) false false)
    (.setReadable (io/file target) true true)
    (.setWritable (io/file target) false false)
    (.setWritable (io/file target) true true)
    (println "A6_V3_AUDIT" (:result result)
             (select-keys result [:pokemon-count :player-count :save-count
                                  :blocked :blocked-reasons :by-version
                                  :before-fingerprint]))
    (when (= :AUDIT_BLOCKED (:result result))
      (System/exit 2))
    result))