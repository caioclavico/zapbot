(ns odisseu.smoke.bag-flags-apply
  "Aplica manifesto JSON de flags kit/diário no Datomic (sem conceder itens).
   Entrada produzida por scripts/a64-bag-kit-daily-backfill.py.

   clojure -M -m odisseu.smoke.bag-flags-apply \\
     --storage DIR --flags FILE [--system odisseu-m90-core] [--db core] [--apply]"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.onboarding-store :as onboard-store]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.http.json :as json]))

(defn- parse-args [args]
  (loop [a args m {:dry-run true
                   :system "odisseu-m90-core"
                   :db-name "core"}]
    (if-let [x (first a)]
      (case x
        "--storage" (recur (nnext a) (assoc m :storage (second a)))
        "--flags" (recur (nnext a) (assoc m :flags (second a)))
        "--system" (recur (nnext a) (assoc m :system (second a)))
        "--db" (recur (nnext a) (assoc m :db-name (second a)))
        "--apply" (recur (next a) (assoc m :dry-run false))
        "--dry-run" (recur (next a) (assoc m :dry-run true))
        (recur (next a) m))
      m)))

(defn- read-flags [path]
  (let [raw (slurp (io/file path))
        data (try (json/read-str raw)
                  (catch Exception _
                    (edn/read-string raw)))
        players (or (get data "players") (:players data))]
    players))

(defn- g [m & ks]
  (some #(get m %) ks))

(defn- player-tx
  [db row]
  (let [chat (or (g row "chatId" :chatId :chat-id) "")
        player (or (g row "playerId" :playerId :player-id) "")
        saves (onboard-store/save-entities-for-player db chat player)
        kit? (boolean (g row "kitInicialResgatado" :kitInicialResgatado
                         :kit-inicial-resgatado))
        dia (g row "ultimoBonusDiario" :ultimoBonusDiario :ultimo-bonus-diario)
        seqn (long (or (g row "sequenciaBonusDiario" :sequenciaBonusDiario
                          :sequencia-bonus-diario)
                       0))]
    (when (empty? saves)
      (println "SKIP no saves" chat player))
    {:chat chat :player player :saves (count saves) :kit kit? :dia dia :seq seqn
     :tx (into []
               (mapcat
                (fn [e]
                  (let [sk (:save/key e)]
                    (cond-> []
                      kit?
                      (conj [:db/add [:save/key sk] :save/kit-inicial-resgatado true])
                      (and (string? dia) (pos? (count dia)))
                      (conj [:db/add [:save/key sk] :save/ultimo-bonus-diario dia])
                      (pos? seqn)
                      (conj [:db/add [:save/key sk] :save/sequencia-bonus-diario seqn]))))
                saves))}))

(defn -main [& args]
  (let [{:keys [storage flags system db-name dry-run]} (parse-args args)]
    (when (or (str/blank? storage) (str/blank? flags))
      (binding [*out* *err*]
        (println "Uso: --storage DIR --flags FILE [--apply]"))
      (System/exit 2))
    (println (if dry-run "DRY-RUN" "APPLY") storage flags)
    (let [rows (read-flags flags)
          client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          _ (schema/install! conn)
          plans (mapv #(player-tx (d/db conn) %) rows)]
      (doseq [p plans]
        (println (select-keys p [:chat :player :saves :kit :dia :seq])
                 "tx" (count (:tx p)))
        (when (and (not dry-run) (seq (:tx p)))
          (d/transact conn {:tx-data (:tx p)})))
      (println "done players" (count plans)
               "kit" (count (filter :kit plans)))
      (System/exit 0))))
