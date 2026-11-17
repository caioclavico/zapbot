(ns odisseu.smoke.a6-species-hp-ro-audit
  "Auditoria RO agregada de HP por espécie — sem PII, sem writes."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(defn- env [k default]
  (or (System/getenv k) default))

(defn- blank-ver? [ver]
  (str/blank? (str ver)))

(defn- anon-sample [e]
  {:species (or (not-empty (str (:pokemon/species e))) "unknown")
   :nivel (long (:pokemon/level e 1))
   :hp (long (:pokemon/hp e 0))
   :hp-atual (long (:pokemon/hp-atual e 0))
   :has-hp-base? (let [b (:pokemon/hp-base e)]
                   (boolean (and b (pos? (long b)))))
   :hp-formula-version (let [v (:pokemon/hp-formula-version e)]
                         (when-not (blank-ver? v) (str v)))})

(defn- sample [entities pred]
  (->> entities (filter pred) (take 5) (mapv anon-sample)))

(defn run
  []
  (let [system (env "ODISSEU_BRIDGE_SYSTEM" "odisseu-m90-core")
        storage (env "ODISSEU_BRIDGE_STORAGE" "/data/datomic-local")
        db-name (env "ODISSEU_BRIDGE_DB" "core")
        client (persistence/client {:system system :storage-dir storage})
        conn (d/connect client {:db-name db-name})
        db (d/db conn)
        eids (mapv first (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db))
        entities (mapv #(d/pull db
                                '[:pokemon/hp :pokemon/hp-atual :pokemon/level
                                  :pokemon/species :pokemon/hp-base
                                  :pokemon/hp-formula-version]
                                %)
                       eids)
        total (count entities)
        ver-of (fn [e]
                 (let [v (:pokemon/hp-formula-version e)]
                   (if (blank-ver? v) "legacy-unset" (str v))))
        by-ver (frequencies (map ver-of entities))
        partial-pred (fn [e]
                       (let [hp (long (:pokemon/hp e 0))
                             at (long (:pokemon/hp-atual e 0))]
                         (and (pos? hp) (pos? at) (< at hp))))
        fainted-pred (fn [e] (not (pos? (long (:pokemon/hp-atual e 0)))))
        full-pred (fn [e]
                    (let [hp (long (:pokemon/hp e 0))
                          at (long (:pokemon/hp-atual e 0))]
                      (and (pos? hp) (= at hp))))
        no-base-pred (fn [e]
                       (let [b (:pokemon/hp-base e)]
                         (or (nil? b) (not (pos? (long b))))))
        partial (count (filter partial-pred entities))
        fainted (count (filter fainted-pred entities))
        full (count (filter full-pred entities))
        no-base (count (filter no-base-pred entities))
        active-raids (count (d/q '[:find ?r
                                   :where
                                   [?r :raid/id]
                                   [?r :raid/fase ?f]
                                   [(contains? #{"lobby" "combate" "captura"} ?f)]]
                                 db))
        active-battles (count (d/q '[:find ?b
                                     :where
                                     [?b :battle/id]
                                     [?b :battle/active? true]]
                                   db))]
    {:ok true
     :read-only true
     :total-pokemon total
     :by-hp-formula-version by-ver
     :hp-partial partial
     :hp-fainted fainted
     :hp-full full
     :without-hp-base no-base
     :with-hp-base (- total no-base)
     :active-raids-approx active-raids
     :active-battles active-battles
     :samples {:partial (sample entities partial-pred)
               :fainted (sample entities fainted-pred)
               :legacy-unset (sample entities #(blank-ver? (:pokemon/hp-formula-version %)))
               :species-hp-v1 (sample entities #(= "species-hp-v1"
                                                   (str (:pokemon/hp-formula-version %))))}
     :compat-notes ["Legado = hp-formula-version ausente; leitura não converte."
                    "Batch futuro exige hp-base ou espécie no catálogo A6."
                    "Excluir/congelar raids e batalhas ativas antes da conversão."
                    "hp-atual=0 deve permanecer 0 (proporcional)."]}))

(defn -main [& _]
  (let [report (run)]
    (println (pr-str report))
    (shutdown-agents)
    (System/exit (if (:ok report) 0 1))))
