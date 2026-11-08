(ns pokemon.tests.lote-a-integrity-a6-test
  "Lote A — integridade A1–A4 com Datomic descartável (party/raid, Grande Bola,
  eventos temáticos após restart, claim idempotente)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.mission-queries :as missions]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.thematic-events :as thematic]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.events :as events]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.thematic-event-store :as tstore])
  (:import [java.nio.file Files]
           [java.time Instant]))

(defn- fresh
  ([]
   (fresh (str "lote-a-" (java.util.UUID/randomUUID))))
  ([db-name]
   (let [dir (str (Files/createTempDirectory "lote-a-a6-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
         client (persistence/client {:system "lote-a-a6" :storage-dir dir})
         conn (persistence/ensure-db! client db-name)
         s (trainer/bootstrap! (store/make-store conn))]
     (trainer/create-trainer! s {:chat-id "chat-a" :player-id "ash"})
     (trainer/create-trainer! s {:chat-id "chat-a" :player-id "misty"})
     {:conn conn :store s :dir dir :client client :db-name db-name})))

(defn- reopen
  [{:keys [client db-name dir]}]
  (let [client (or client (persistence/client {:system "lote-a-a6" :storage-dir dir}))
        conn (persistence/ensure-db! client db-name)
        s (trainer/bootstrap! (store/make-store conn))]
    {:conn conn :store s :dir dir :client client :db-name db-name}))

(def ^:private mon-a
  {:nome "A" :species "rattata" :hp 1 :hp-atual 1 :ataque 80 :defesa 40
   :atq-esp 40 :def-esp 40 :veloc 50 :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Hit" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private mon-b
  {:nome "B" :species "eevee" :hp 120 :hp-atual 120 :ataque 80 :defesa 40
   :atq-esp 40 :def-esp 40 :veloc 50 :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Hit" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private chefe
  {:nome "Boss" :tipos ["grass"] :defesa 40 :def-esp 40 :raridade "raro"
   :fator-dificuldade 1.0 :nivel 10
   :golpes [{:nome-exibicao "Vine" :tipo "grass" :poder 40 :classe :especial}]})

(deftest a1-raid-one-mon-faint-coop-continua
  (testing "join sela 1 mon; faint elimina ash; misty mantém combate; reopen OK"
    (let [{:keys [store] :as env} (fresh "raid-party")
          chat "chat-a"
          rid "raid-party-1"
          ;; 3º arg = hp-atual selado na coleção (assert-combatente usa hp-atual).
          _ (collection/adicionar-pokemon! store chat "ash" mon-a 1 nil)
          _ (collection/adicionar-pokemon! store chat "ash" mon-b 120 nil)
          _ (collection/adicionar-pokemon! store chat "misty" (assoc mon-b :nome "M") 100 nil)
          col (ports/list-collection store chat "ash" "a")
          idx0 (long (:collection-index (first col) 0))
          col-m (ports/list-collection store chat "misty" "a")
          idx-m (long (:collection-index (first col-m) 0))
          _ (raid-app/open-raid!
             store chat "ash"
             {:operation-id "open-1" :raid-id rid :ginasio "pedra" :nome-ginasio "Pedra"
              :nivel-ginasio 10 :liga "bronze" :chefe chefe
              :min-jogadores 1 :agora 1000})
          join (raid-app/join-raid!
                store chat "ash"
                {:operation-id "join-1" :raid-id rid
                 :collection-index idx0 :nome "Ash" :agora 1001})
          _ (raid-app/join-raid!
             store chat "misty"
             {:operation-id "join-2" :raid-id rid
              :collection-index idx-m :nome "Misty" :agora 1001})
          part0 (:participant join)
          _ (is (= 1 (count (:party part0))) "join sela exatamente 1 mon")
          _ (is (= 1 (long (:hp part0))) "ativo entra com 1 HP")
          _ (raid-app/start-raid!
             store chat "ash"
             {:operation-id "start-1" :raid-id rid :agora 1002})
          atk (raid-app/attack!
               store chat "ash"
               {:operation-id "atk-1" :raid-id rid
                :expected-turn-index 0 :move-index 0 :agora 1010})
          raid1 (raid-app/get-raid store rid)
          env2 (reopen env)
          raid2 (raid-app/get-raid (:store env2) rid)
          ash2 (get-in raid2 [:participantes "ash"])
          misty2 (get-in raid2 [:participantes "misty"])]
      (is (false? (:derrota? atk)))
      (is (false? (:trocou? atk)))
      (is (true? (:eliminado? atk)))
      (is (= "combate" (:fase raid1)))
      (is (= "combate" (:fase raid2)))
      (is (= 1 (count (:party ash2))))
      (is (zero? (long (:hp ash2 0))))
      (is (pos? (long (:hp misty2 0))))
      (is (= "misty" (:vez raid2))))))

(deftest a2-normalize-ball-aliases-e-shop
  (is (= "grande-bola" (#'tx/normalize-ball "2")))
  (is (= "grande-bola" (#'tx/normalize-ball "great")))
  (is (= "grande-bola" (#'tx/normalize-ball "great-ball")))
  (is (= "grande-bola" (#'tx/normalize-ball "super")))
  (is (= "grande-bola" (#'tx/normalize-ball "Super Bola")))
  (is (= "ultra-bola" (#'tx/normalize-ball "ultra-ball")))
  (is (= "grande-bola" (shop/normalizar-item "great-ball")))
  (is (= "grande-bola" (shop/normalizar-item "super-bola")))
  (is (= "grande-bola" (shop/normalizar-item "great"))))

(deftest a3-eventos-missoes-sobrevivem-restart
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn] :as env} (fresh "thematic-restart")
          agora 7000000
          instant (Instant/ofEpochMilli agora)
          _ (thematic/admin-schedule!
             store {:chat-id "chat-a" :next-at agora
                    :catalog-id "noite-sombras" :enabled? true :agora-ms agora})
          t1 (thematic/tick! store {:chat-id "chat-a" :agora-ms agora :draw 0})
          oid (:started (first (:processed t1)))
          _ (is (some? oid))
          _ (d/transact conn
                        {:tx-data
                         [[:db/add [:save/key "chat-a|ash|a"]
                           :save/missoes-eventos-edn
                           (pr-str {"thematic"
                                    {oid {"progresso" {"cap-sombra" 2 "bat-sombra" 1}
                                          "premiados" []
                                          "candy-picks" {}}}})]]})
          snap1 (missions/read-missions store "chat-a" "ash" "a"
                                        {:instant instant :timezone "UTC"})
          ev1 (some #(when (= oid (:occurrence-id %)) %) (:events snap1))
          env2 (reopen env)
          snap2 (missions/read-missions (:store env2) "chat-a" "ash" "a"
                                        {:instant instant :timezone "UTC"})
          ev2 (some #(when (= oid (:occurrence-id %)) %) (:events snap2))
          occs (tstore/active-occurrences (d/db (:conn env2)) "chat-a" agora)]
      (is (= "noite-sombras" (or (:catalog-id ev1) (:id ev1))))
      (is (= "active" (:status ev1)))
      (is (= 2 (get-in ev1 [:missions 0 :progresso])))
      (is (some? ev2) "após reopen, ocorrência ativa ainda aparece em !pk missoes evento")
      (is (= "noite-sombras" (or (:catalog-id ev2) (:id ev2))))
      (is (= 2 (get-in ev2 [:missions 0 :progresso])))
      (is (= 1 (get-in ev2 [:missions 1 :progresso])))
      (is (= 1 (count occs)))
      (is (= oid (:occurrence-id (first occs)))))))

(deftest a4-claim-grande-bola-idempotente-e-captura
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn]} (fresh "claim-bola")
          agora 8000000
          _ (thematic/admin-schedule!
             store {:chat-id "chat-a" :next-at agora
                    :catalog-id "noite-sombras" :enabled? true :agora-ms agora})
          oid (:started (first (:processed
                                (thematic/tick! store {:chat-id "chat-a"
                                                       :agora-ms agora :draw 0}))))
          _ (d/transact conn
                        {:tx-data
                         [[:db/add [:save/key "chat-a|ash|a"]
                           :save/missoes-eventos-edn
                           (pr-str {"thematic"
                                    {oid {"progresso" {"cap-sombra" 4 "bat-sombra" 2}
                                          "premiados" []
                                          "candy-picks" {}}}})]]})
          w (writer/freeze-writer store "chat-a" "ash")
          r1 (thematic/claim! store w {:occurrence-id oid :operation-id "claim-gb-1"})
          r2 (thematic/claim! store w {:occurrence-id oid :operation-id "claim-gb-1"})
          inv (ports/list-inventory store "chat-a" "ash" "a")
          gb (long (or (some #(when (= "grande-bola" (:item-id %)) (:qty %)) inv) 0))
          _ (is (false? (:duplicate r1)))
          _ (is (true? (:duplicate r2)))
          _ (is (= (:ids r1) (:ids r2)))
          _ (is (>= (long (get-in r1 [:items "grande-bola"] 0)) 2)
                "missões de noite-sombras concedem Grande Bola")
          _ (is (>= gb 2) "Grande Bola entrou no inventário (não só pending)")
          _ (collection/adicionar-pokemon! store "chat-a" "ash" mon-b 120 nil)
          cap (capture-app/attempt-capture!
               store "chat-a" "ash"
               {:operation-id "cap-with-gb"
                :pokemon {:nome "Zubat" :species "zubat" :nivel 5
                          :hp 20 :hp-atual 20 :ataque 10 :defesa 10
                          :atq-esp 10 :def-esp 10 :veloc 30
                          :tipos ["poison" "flying"] :raridade "comum"}
                :bola "grande-bola"
                :roll 0 :fuga-roll 99})
          inv2 (ports/list-inventory store "chat-a" "ash" "a")
          gb2 (long (or (some #(when (= "grande-bola" (:item-id %)) (:qty %)) inv2) 0))]
      (is (true? (:capturou? cap)))
      (is (= (dec gb) gb2) "captura debita Grande Bola do inventário canônico"))))
