(ns a6-recovery-preview
  "Prévia somente leitura de snapshots históricos offline.

  O arquivo de entrada deve ser uma cópia autorizada, nunca uma conexão
  Cassandra. Este comando não cria Datomic, não ativa slots e não importa nada."
  (:require [clojure.pprint :refer [pprint]]
            [pokemon.migration.export :as export]
            [pokemon.recovery :as recovery]))

(defn- usage []
  (println "Uso: clojure -M -m a6-recovery-preview <dump.edn> <chat-id> <player-id>"))

(defn -main [& args]
  (if (not= 3 (count args))
    (do (usage) (System/exit 2))
    (let [[path chat-id player-id] args
          dump (export/load-edn-file path)
          exp (export/from-authorized-offline-dump dump)]
      (pprint {:read-only true
               :source :authorized-offline-copy
               :chat-id chat-id
               :player-id player-id
               :available (recovery/discover exp chat-id player-id)}))))
