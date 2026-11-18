(ns odisseu.smoke.a6-species-hp-ro-export
  "Exporta agregados + fixtures anonimizados (sem PII) — somente leitura."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.smoke.a6-species-hp-ro-audit :as audit]))

(defn- anon-row [e]
  {:id (str "anon-" (Math/abs (hash (str (:pokemon/id-pokemon e)))))
   :species (or (not-empty (str (:pokemon/species e))) "unknown")
   :nivel (long (:pokemon/level e 1))
   :hp (long (:pokemon/hp e 0))
   :hp-atual (long (:pokemon/hp-atual e 0))
   :hp-base (when-let [b (:pokemon/hp-base e)]
              (when (pos? (long b)) (long b)))
   :hp-formula-version (let [v (:pokemon/hp-formula-version e)]
                         (when-not (str/blank? (str v)) (str v)))})

(defn run
  []
  (let [agg (audit/run)
        system (or (System/getenv "ODISSEU_BRIDGE_SYSTEM") "odisseu-m90-core")
        storage (or (System/getenv "ODISSEU_BRIDGE_STORAGE") "/data/datomic-local")
        db-name (or (System/getenv "ODISSEU_BRIDGE_DB") "core")
        client (persistence/client {:system system :storage-dir storage})
        conn (d/connect client {:db-name db-name})
        db (d/db conn)
        eids (mapv first (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db))
        entities (mapv #(d/pull db
                                '[:pokemon/id-pokemon :pokemon/hp :pokemon/hp-atual
                                  :pokemon/level :pokemon/species :pokemon/hp-base
                                  :pokemon/hp-formula-version]
                                %)
                       eids)
        rows (mapv anon-row entities)]
    (assoc agg
           :fixture-rows rows
           :exported-at (str (java.time.Instant/now))
           :note "anonimizado; sem player-id/chat-id")))

(defn -main [& _]
  (let [report (run)]
    (println (pr-str report))
    (shutdown-agents)
    (System/exit (if (:ok report) 0 1))))
