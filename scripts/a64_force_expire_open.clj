(ns a64-force-expire-open
  "Lista e (opcionalmente) encerra batalhas/raids abertas no Datomic live.
   Uso:
     ODISSEU_PROBE_STORAGE=/data/datomic-local \\
     ODISSEU_FORCE_EXPIRE=0|1 \\
     clojure -M:test -i scripts/a64_force_expire_open.clj"
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.battle-idle-store :as idle-store]))

(defn- env [k default]
  (or (System/getenv k) default))

(defn- edn-read [s]
  (when (and s (not (empty? s)))
    (edn/read-string s)))

(defn- open-battles
  [db]
  (->> (d/q '[:find ?bid
              :where [?e :battle/id ?bid]]
            db)
       (map first)
       (keep (fn [bid]
               (let [e (d/pull db '[*] [:battle/id bid])
                     st (or (edn-read (:battle/state-edn e)) {})]
                 (when (and (not (true? (:battle/idle-expired? e)))
                            (or (true? (:battle/active? e))
                                (true? (:aguardando-captura? st))
                                (= "waiting" (str (:battle/phase e)))))
                   {:battle-id bid
                    :active? (boolean (:battle/active? e))
                    :phase (:battle/phase e)
                    :kind (:battle/kind e)
                    :aguardando-captura? (boolean (:aguardando-captura? st))
                    :idle-expires-at (:battle/idle-expires-at e)
                    :outcome (:outcome st)}))))
       vec))

(defn- open-raids
  [db]
  (->> (d/q '[:find ?rid ?fase
              :where
              [?e :raid/id ?rid]
              [?e :raid/fase ?fase]]
            db)
       (keep (fn [[rid fase]]
               (when (and (contains? #{"inscricoes" "combate"} (str fase))
                          (not (true? (:raid/idle-expired?
                                       (d/pull db '[:raid/idle-expired?]
                                               [:raid/id rid])))))
                 {:raid-id rid :fase fase})))
       vec))

(defn -main
  [& _]
  (let [storage (env "ODISSEU_PROBE_STORAGE" nil)
        system (env "ODISSEU_PROBE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_PROBE_DB" "core")
        force? (= "1" (env "ODISSEU_FORCE_EXPIRE" "0"))]
    (when (or (nil? storage) (empty? storage))
      (binding [*out* *err*]
        (println "ODISSEU_PROBE_STORAGE obrigatório"))
      (System/exit 2))
    (let [client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          db (d/db conn)
          battles (open-battles db)
          raids (open-raids db)
          timeout (try
                    (Long/parseLong
                     (or (System/getenv "POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS") "300"))
                    (catch Exception _ 300))]
      (println "idle-timeout-seconds=" timeout)
      (println "open-battles=" (count battles) (pr-str battles))
      (println "open-raids=" (count raids) (pr-str raids))
      (if-not force?
        (do (println "dry-run (set ODISSEU_FORCE_EXPIRE=1 to close)")
            (System/exit 0))
        (let [agora (System/currentTimeMillis)
              bids (mapv :battle-id battles)
              rids (mapv :raid-id raids)
              ;; Sempre encerra uma a uma — funciona mesmo em JVM sem :force.
              br (mapv #(idle-store/expire-battle! conn % agora) bids)
              rr (mapv #(idle-store/expire-raid! conn % agora) rids)
              result {:force true
                      :expired-battles (count (remove :duplicate br))
                      :expired-raids (count (remove :duplicate rr))}
              db2 (d/db conn)]
          (println "FORCE" (pr-str result))
          (println "after-open-battles=" (count (open-battles db2)))
          (println "after-open-raids=" (count (open-raids db2)))
          (println "OK")
          (System/exit 0))))))

(-main)
