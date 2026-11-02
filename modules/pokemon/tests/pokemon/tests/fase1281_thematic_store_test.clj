(ns pokemon.tests.fase1281-thematic-store-test
  "Fase 12.8.1 — persistência descartável: tick, outbox, claim, restart."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.thematic-events :as thematic]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.events :as events]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.thematic-event-store :as tstore])
  (:import [java.nio.file Files]))

(defn- fresh []
  (let [dir (str (Files/createTempDirectory "fase1281-" (make-array java.nio.file.attribute.FileAttribute 0)))
        client (persistence/client {:system "fase1281" :storage-dir dir})
        conn (persistence/ensure-db! client "thematic")
        s (trainer/bootstrap! (store/make-store conn))]
    (trainer/create-trainer! s {:chat-id "chat-piloto" :player-id "ash"})
    {:conn conn :store s :dir dir}))

(defn- seed-progress!
  [conn occurrence-id]
  (d/transact conn
              {:tx-data
               [[:db/add [:save/key "chat-piloto|ash|a"]
                 :save/missoes-eventos-edn
                 (pr-str {"thematic"
                          {occurrence-id
                           {"progresso" {"cap-dragao" 3 "evo-dragao" 1 "raid-dragao" 2}
                            "premiados" []
                            "candy-picks" {}}}})]]}))

(deftest tick-flag-off-noop
  (let [{:keys [store]} (fresh)]
    (is (false? (events/thematic-events-enabled?)))
    (is (= "thematic-events-flag-off"
           (:note (thematic/tick! store {:chat-id "chat-piloto" :agora-ms 1000}))))))

(deftest tick-ativa-encerra-outbox-idempotente-com-flag
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn]} (fresh)
          agora 1000000
          _ (thematic/admin-schedule!
             store {:chat-id "chat-piloto"
                    :next-at agora
                    :catalog-id "festival-dragoes"
                    :enabled? true
                    :agora-ms agora})
          t1 (thematic/tick! store {:chat-id "chat-piloto" :agora-ms agora :draw 0})
          started (:started (first (:processed t1)))
          _ (is (some? started))
          outbox1 (ports/list-pokemon-outbox store 20)
          start-ev (some #(when (= "thematic-event-started" (:kind %)) %) outbox1)
          _ (is (some? start-ev))
          _ (is (re-find #"FESTIVAL DOS DRAGÕES" (str (:texto start-ev))))
          t1b (thematic/tick! store {:chat-id "chat-piloto" :agora-ms agora :draw 0})
          _ (is (nil? (:started (first (:processed t1b)))))
          occ (first (tstore/active-occurrences (d/db conn) "chat-piloto" agora))
          fim (:fim-ms occ)
          t2 (thematic/tick! store {:chat-id "chat-piloto" :agora-ms fim :draw 0})
          _ (is (= [started] (:ended (first (:processed t2)))))
          outbox2 (ports/list-pokemon-outbox store 20)
          end-ev (some #(when (= "thematic-event-ended" (:kind %)) %) outbox2)]
      (is (some? end-ev))
      (is (empty? (tstore/active-occurrences (d/db conn) "chat-piloto" fim)))
      ;; Restart: re-tick não duplica outbox de fim
      (let [t3 (thematic/tick! store {:chat-id "chat-piloto" :agora-ms fim :draw 0})
            ends (filter #(= "thematic-event-ended" (:kind %))
                         (ports/list-pokemon-outbox store 50))]
        (is (nil? (first (:ended (first (:processed t3))))))
        (is (= 1 (count ends)))))))

(deftest claim-event-missions-doces-e-recibo-idempotente
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn]} (fresh)
          agora 2000000
          _ (thematic/admin-schedule!
             store {:chat-id "chat-piloto" :next-at agora
                    :catalog-id "festival-dragoes" :enabled? true :agora-ms agora})
          t1 (thematic/tick! store {:chat-id "chat-piloto" :agora-ms agora :draw 0})
          oid (:started (first (:processed t1)))
          _ (seed-progress! conn oid)
          w (writer/freeze-writer store "chat-piloto" "ash")
          r1 (thematic/claim!
              store w {:occurrence-id oid
                       :operation-id "claim-event-1"})
          r2 (thematic/claim!
              store w {:occurrence-id oid
                       :operation-id "claim-event-1"})
          cards (ports/professor-cards store "chat-piloto" "ash" "a")]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= (:ids r1) (:ids r2)))
      (is (pos? (reduce + 0 (vals (:doces r1)))))
      (is (pos? (reduce + 0 (vals (or (:cards cards) {}))))))))

(defn- start-festival!
  [store conn agora]
  (thematic/admin-schedule!
   store {:chat-id "chat-piloto" :next-at agora
          :catalog-id "festival-dragoes" :enabled? true :agora-ms agora})
  (let [t (thematic/tick! store {:chat-id "chat-piloto" :agora-ms agora :draw 0})
        oid (:started (first (:processed t)))]
    (seed-progress! conn oid)
    oid))

(deftest claim-seguro-concorrente-retry-mochila-e-economia
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn]} (fresh)
          agora 3000000
          oid (start-festival! store conn agora)
          w (writer/freeze-writer store "chat-piloto" "ash")
          ;; Dois resgates simultâneos com o mesmo operation-id
          f1 (future (thematic/claim! store w {:occurrence-id oid :operation-id "claim-race"}))
          f2 (future (thematic/claim! store w {:occurrence-id oid :operation-id "claim-race"}))
          r1 @f1
          r2 @f2
          cards (:cards (ports/professor-cards store "chat-piloto" "ash" "a"))
          candy-total (reduce + 0 (vals cards))]
      (is (= 1 (count (filter (complement :duplicate) [r1 r2]))))
      (is (= 1 (count (filter :duplicate [r1 r2]))))
      (is (= (:ids r1) (:ids r2)))
      (is (pos? candy-total))
      ;; Retry do mesmo comando → duplicate, sem novo doce
      (let [r3 (thematic/claim! store w {:occurrence-id oid :operation-id "claim-race"})]
        (is (true? (:duplicate r3)))
        (is (= candy-total
               (reduce + 0 (vals (:cards (ports/professor-cards store "chat-piloto" "ash" "a"))))))))))

(deftest claim-mochila-cheia-vai-para-pending
  (with-redefs [events/thematic-events-enabled? (constantly true)
                pokemon.domain.capacity/capacidade-mochila (fn [& _] 0)]
    (let [{:keys [store conn]} (fresh)
          agora 4000000
          oid (start-festival! store conn agora)
          w (writer/freeze-writer store "chat-piloto" "ash")
          r (thematic/claim! store w {:occurrence-id oid :operation-id "claim-full-bag"})]
      (is (empty? (:added r)))
      (is (pos? (reduce + 0 (vals (:pending r)))))
      (is (pos? (reduce + 0 (vals (:doces r))))
          "Doces não ocupam mochila — creditados mesmo com bag cheia"))))

(deftest claim-preserva-unknown-pending-e-evento-encerrado
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn]} (fresh)
          agora 5000000
          oid (start-festival! store conn agora)
          _ (d/transact conn {:tx-data [[:db/add [:save/key "chat-piloto|ash|a"]
                                         :save/economy-status "unknown-pending"]
                                        [:db/add [:save/key "chat-piloto|ash|a"]
                                         :save/coins 999]]})
          ;; Encerra ocorrência antes do claim (missões já concluídas)
          _ (d/transact conn {:tx-data [[:db/add [:thematic-event-occurrence/id oid]
                                         :thematic-event-occurrence/status "ended"]]})
          w (writer/freeze-writer store "chat-piloto" "ash")
          r (thematic/claim! store w {:occurrence-id oid :operation-id "claim-ended"})
          save (d/pull (d/db conn) '[*] [:save/key "chat-piloto|ash|a"])]
      (is (false? (:duplicate r)))
      (is (seq (:ids r)))
      (is (= "unknown-pending" (:save/economy-status save)))
      (is (= 999 (:save/coins save)))
      ;; Segunda claim com outro op → nothing (já premiado)
      (is (= :mission_nothing_to_claim
             (err/error-code
              (try
                (thematic/claim! store w {:occurrence-id oid :operation-id "claim-ended-2"})
                nil
                (catch Exception ex ex))))))))
