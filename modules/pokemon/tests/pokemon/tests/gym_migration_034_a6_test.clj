(ns pokemon.tests.gym-migration-034-a6-test
  "Validação local da migration 034 (Datomic descartável) — NÃO produção."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.ports :as ports]
            [pokemon.domain.gym :as gym]))

(def ^:private system "odisseu-gym-034-a6")
(def ^:private storage-rel ".datomic-local/gym-034-a6")

(defn- client []
  (persistence/client {:system system :storage-dir storage-rel}))

(defn- wipe! [db-name]
  (persistence/delete-db! (client) db-name))

(defn- open-conn! [db-name]
  (persistence/ensure-db! (client) db-name))

(deftest migration-034-idempotente-e-aditiva
  (let [db-name "m034-clean"]
    (wipe! db-name)
    (let [conn (open-conn! db-name)
          _ (schema/install! conn)
          db1 (d/db conn)
          attr1 (d/pull db1 '[*] :save/gym-permanence-daily-edn)
          _ (schema/install! conn) ;; reaplicação
          db2 (d/db conn)
          attr2 (d/pull db2 '[*] :save/gym-permanence-daily-edn)
          mid (d/pull db2 '[*] [:odisseu.schema/migration-id "pokemon/034-gym-permanence-daily"])]
      (is (= :db.type/string (:db/valueType attr1)))
      (is (= :db.cardinality/one (:db/cardinality attr1)))
      (is (= attr1 attr2) "reinstall não altera o atributo")
      (is (= "pokemon/034-gym-permanence-daily"
             (:odisseu.schema/migration-id mid))))))

(deftest migration-034-compativel-com-save-legado
  (let [db-name "m034-legacy"]
    (wipe! db-name)
    (let [conn (open-conn! db-name)
          ;; schema completo (inclui 034)
          _ (schema/install! conn)
          s (store/make-store conn)
          _ (trainer/bootstrap! s)
          _ (trainer/create-trainer! s {:chat-id "c034" :player-id "ash"})
          save (ports/get-save s "c034" "ash" "a")
          ent (d/pull (d/db conn) '[*] [:save/key "c034|ash|a"])]
      (is (some? save))
      (is (nil? (:save/gym-permanence-daily-edn ent))
          "save legado sem o attr continua válido")
      ;; gravação explícita do mapa diário
      (d/transact conn {:tx-data [[:db/add [:save/key "c034|ash|a"]
                                   :save/gym-permanence-daily-edn
                                   (pr-str {"2026-11-05" 40})]]})
      (let [ent2 (d/pull (d/db conn) '[*] [:save/key "c034|ash|a"])
            parsed (edn/read-string (:save/gym-permanence-daily-edn ent2))]
        (is (= 40 (get parsed "2026-11-05")))
        (is (= 60 (gym/aplicar-teto-diario 100 40)))))))

(deftest migration-034-presente-no-catalogo-schema
  (is (some #(re-find #"034-gym-permanence-daily" %) schema/migration-files)))
