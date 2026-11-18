(ns a64-idle-026-probe
  "Probe RO + ensaio 026 em storage descartável (não toca volumes live)."
  (:require [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.ports :as ports]))

(defn- env [k default]
  (or (System/getenv k) default))

(defn- count-attr [db attr]
  (or (ffirst (d/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db attr)) 0))

(defn- migrations [db]
  (->> (d/q '[:find ?id :where [?e :odisseu.schema/migration-id ?id]] db)
       (map first)
       sort
       vec))

(defn- attr-exists? [db ident]
  (some? (d/pull db '[:db/ident] ident)))

(defn- active-battles [db]
  (let [ids (->> (d/q '[:find ?bid
                        :where
                        [?e :battle/id ?bid]
                        [?e :battle/active? true]]
                      db)
                 (map first)
                 vec)
        idle? (attr-exists? db :battle/idle-expires-at)]
    (mapv (fn [bid]
            (let [e (d/pull db '[*] [:battle/id bid])]
              (cond-> {:battle-id bid
                       :active? (boolean (:battle/active? e))
                       :phase (:battle/phase e)}
                idle? (assoc :idle-expires-at (:battle/idle-expires-at e)
                             :idle-expired? (boolean (:battle/idle-expired? e))))))
          ids)))

(defn -main
  [& _]
  (let [storage (env "ODISSEU_PROBE_STORAGE" nil)
        system (env "ODISSEU_PROBE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_PROBE_DB" "core")
        apply? (= "1" (env "ODISSEU_PROBE_APPLY_026" "0"))]
    (when (or (nil? storage) (empty? storage))
      (binding [*out* *err*]
        (println "ODISSEU_PROBE_STORAGE obrigatório"))
      (System/exit 2))
    (println "probe.storage=" storage)
    (println "probe.system=" system "db=" db-name "apply026=" apply?)
    (let [client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          db0 (d/db conn)
          m0 (migrations db0)
          report0 {:migrations m0
                   :migration-count (count m0)
                   :has-025? (boolean (some #{"pokemon/025-save-slot-metadata"} m0))
                   :has-026? (boolean (some #{"pokemon/026-battle-idle"} m0))
                   :players (count-attr db0 :player/key)
                   :saves (count-attr db0 :save/key)
                   :pokemon (count-attr db0 :pokemon/key)
                   :battles (count-attr db0 :battle/id)
                   :active-battles (active-battles db0)
                   :raids (count-attr db0 :raid/id)}]
      (println "BEFORE" (pr-str report0))
      (when apply?
        (schema/install! conn)
        (let [s (doto (store/make-store conn) trainer/bootstrap!)
              db1 (d/db conn)
              m1 (migrations db1)
              report1 {:migrations m1
                       :migration-count (count m1)
                       :has-026? (boolean (some #{"pokemon/026-battle-idle"} m1))
                       :players (count-attr db1 :player/key)
                       :saves (count-attr db1 :save/key)
                       :pokemon (count-attr db1 :pokemon/key)
                       :battles (count-attr db1 :battle/id)
                       :active-battles (active-battles db1)
                       :sweep (ports/expire-idle-battles! s {:limit 8})}]
          (println "AFTER" (pr-str (dissoc report1 :sweep)))
          (println "SWEEP" (pr-str (:sweep report1)))
          (when-not (:has-026? report1)
            (binding [*out* *err*] (println "FAIL: 026 não aplicada"))
            (System/exit 1))
          (when (or (not= (:players report0) (:players report1))
                    (not= (:saves report0) (:saves report1))
                    (not= (:pokemon report0) (:pokemon report1)))
            (binding [*out* *err*] (println "FAIL: contagens mudaram após 026"))
            (System/exit 1))))
      (println "OK")
      (System/exit 0))))

(-main)