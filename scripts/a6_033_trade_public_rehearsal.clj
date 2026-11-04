(ns a6-033-trade-public-rehearsal
  "Ensaio descartável da migration 033 (trade public-id).
   Não toca volumes live.

   ODISSEU_PROBE_STORAGE=/tmp/.../datomic \\
   ODISSEU_APPLY_033=1 \\
   clojure -M -e '(load-file \"scripts/a6_033_trade_public_rehearsal.clj\")'"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.schema :as schema]))

(def ^:private mig-id "pokemon/033-trade-public-id")
(def ^:private mig-file "migrations/pokemon/033-trade-public-id.edn")
(def ^:private new-attrs [:trade/public-id :trade/public-key :trade-seq/chat-id :trade-seq/next])

(defn- env [k d] (or (System/getenv k) d))

(defn- migrations [db]
  (->> (d/q '[:find ?id :where [?e :odisseu.schema/migration-id ?id]] db)
       (map first) sort vec))

(defn- attr? [db ident]
  (some? (:db/ident (d/pull db '[:db/ident] ident))))

(defn- ensure-base-through-032! [conn]
  (let [ids (set (migrations (d/db conn)))
        steps (->> schema/migration-files
                   (map (fn [f]
                          {:id (str "pokemon/" (-> f
                                                   (str/replace #"^migrations/pokemon/" "")
                                                   (str/replace #"\.edn$" "")))
                           :file f}))
                   (remove #(= (:id %) mig-id))
                   (remove #(contains? ids (:id %))))]
    (doseq [{:keys [id file]} steps]
      (let [tx (edn/read-string (slurp (io/resource file)))]
        (d/transact conn {:tx-data (conj (vec tx) {:odisseu.schema/migration-id id})})
        (println "applied.base=" id)))))

(defn- apply-033! [conn]
  (let [already (boolean (some #{mig-id} (migrations (d/db conn))))]
    (if already
      (do (println "033.already-present=true") :already)
      (let [tx (edn/read-string (slurp (io/resource mig-file)))]
        (d/transact conn {:tx-data (conj (vec tx) {:odisseu.schema/migration-id mig-id})})
        (println "033.applied=true")
        :applied))))

(defn- seed-legacy-trade! [conn]
  (let [id "legacy-canonical-trade-1"
        now 1700000000000]
    (d/transact conn {:tx-data [{:trade/id id
                                 :trade/chat-id "chat-legacy"
                                 :trade/player-a "ash"
                                 :trade/player-b "misty"
                                 :trade/save-a "a"
                                 :trade/save-b "a"
                                 :trade/state "invited"
                                 :trade/created-at now
                                 :trade/expires-at (+ now 300000)
                                 :trade/revision 0
                                 :trade/context-edn "{}"}]})
    id))

(println "=== a6-033 rehearsal ===")
(let [storage (env "ODISSEU_PROBE_STORAGE" nil)]
  (when-not storage
    (binding [*out* *err*] (println "ODISSEU_PROBE_STORAGE required") (System/exit 2)))
  (let [system (env "ODISSEU_PROBE_SYSTEM" (str "033-rehearsal-" (System/currentTimeMillis)))
        db-name (env "ODISSEU_PROBE_DB" "core")
        client (persistence/client {:storage-dir storage :system system})
        _ (d/create-database client {:db-name db-name})
        conn (d/connect client {:db-name db-name})]
    (println "probe.storage=" storage)
    (ensure-base-through-032! conn)
    (println "migrations.before033.count=" (count (migrations (d/db conn))))
    (println "migrations.before033.max=" (last (migrations (d/db conn))))
    (doseq [a new-attrs] (println "attr.before" (name a) "=" (attr? (d/db conn) a)))
    (let [legacy (seed-legacy-trade! conn)]
      (println "legacy.trade=" legacy)
      (when (= "1" (env "ODISSEU_APPLY_033" "0"))
        (println "apply1=" (apply-033! conn))
        (println "apply2=" (apply-033! conn)))
      (doseq [a new-attrs] (println "attr.after" (name a) "=" (attr? (d/db conn) a)))
      (println "has.033=" (boolean (some #{mig-id} (migrations (d/db conn)))))
      (let [t (d/pull (d/db conn) '[*] [:trade/id legacy])]
        (println "legacy.state=" (:trade/state t))
        (println "legacy.has-public-id=" (contains? t :trade/public-id))
        (println "legacy.still-addressable=" (= legacy (:trade/id t))))))
  (println "REHEARSAL_OK")
  (System/exit 0))
