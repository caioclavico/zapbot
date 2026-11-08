(ns pokemon.tests.homolog-abc-integrated-a6-test
  "Homologação integrada A+B+C — sequência real com Datomic descartável:
  missão evento → claim → inventário → captura → batalha → raid (faint+reserva)
  → reopen JVM → missões/evento preservados. Idempotência de claim."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.mission-queries :as missions]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.thematic-events :as thematic]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.events :as events]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.thematic-event-store :as tstore])
  (:import [java.nio.file Files]
           [java.time Instant]))

(defn- fresh
  ([] (fresh "homolog-abc"))
  ([db-name]
   (let [dir (str (Files/createTempDirectory "homolog-abc-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
         client (persistence/client {:system "homolog-abc" :storage-dir dir})
         conn (persistence/ensure-db! client db-name)
         s (trainer/bootstrap! (store/make-store conn))]
     (trainer/create-trainer! s {:chat-id "chat-h" :player-id "ash"})
     {:conn conn :store s :dir dir :client client :db-name db-name})))

(defn- reopen [{:keys [client db-name dir]}]
  (let [client (or client (persistence/client {:system "homolog-abc" :storage-dir dir}))
        conn (persistence/ensure-db! client db-name)
        s (trainer/bootstrap! (store/make-store conn))]
    {:conn conn :store s :dir dir :client client :db-name db-name}))

(def ^:private rng-hit
  {:accuracy 0 :crit 99 :damage-var 10 :dodge 99 :flee 99
   :status 99 :contagio 99 :multi-hit 0 :wild-move 0
   :status-golpe 99 :impede-para 99 :cura-sono 99 :cura-gelo 99 :cura-confusao 99})

(def ^:private mon-fragile
  {:nome "A" :species "rattata" :hp 1 :hp-atual 1 :ataque 80 :defesa 40
   :atq-esp 40 :def-esp 40 :veloc 50 :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Hit" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private mon-reserve
  {:nome "B" :species "eevee" :hp 120 :hp-atual 120 :ataque 80 :defesa 40
   :atq-esp 40 :def-esp 40 :veloc 50 :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Hit" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private squirtle
  {:nome "Squirtle" :species "squirtle" :hp 100 :ataque 60 :defesa 70
   :atq-esp 60 :def-esp 65 :veloc 50 :tipos ["water"] :nivel 12
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 40 :classe :especial}]})

(def ^:private wild-weak
  {:nome "Magikarp" :species "magikarp" :raridade "comum" :hp 5 :ataque 10
   :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80 :tipos ["water"] :nivel 5
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private chefe
  {:nome "Boss" :tipos ["grass"] :defesa 40 :def-esp 40 :raridade "raro"
   :fator-dificuldade 1.0 :nivel 10
   :golpes [{:nome-exibicao "Vine" :tipo "grass" :poder 40 :classe :especial}]})

(deftest homolog-abc-sequencia-completa-datomic
  (with-redefs [events/thematic-events-enabled? (constantly true)]
    (let [{:keys [store conn] :as env} (fresh)
          chat "chat-h"
          player "ash"
          agora 9000000
          instant (Instant/ofEpochMilli agora)
          _ (thematic/admin-schedule!
             store {:chat-id chat :next-at agora
                    :catalog-id "noite-sombras" :enabled? true :agora-ms agora})
          oid (:started (first (:processed
                                (thematic/tick! store {:chat-id chat
                                                       :agora-ms agora :draw 0}))))
          _ (is (some? oid))
          _ (d/transact conn
                        {:tx-data
                         [[:db/add [:save/key "chat-h|ash|a"]
                           :save/missoes-eventos-edn
                           (pr-str {"thematic"
                                    {oid {"progresso" {"cap-sombra" 4 "bat-sombra" 2}
                                          "premiados" []
                                          "candy-picks" {}}}})]]})
          w (writer/freeze-writer store chat player)
          r1 (thematic/claim! store w {:occurrence-id oid :operation-id "h-claim-1"})
          r2 (thematic/claim! store w {:occurrence-id oid :operation-id "h-claim-1"})
          inv (ports/list-inventory store chat player "a")
          gb (long (or (some #(when (= "grande-bola" (:item-id %)) (:qty %)) inv) 0))]

      (testing "1) claim idempotente → inventário Grande Bola"
        (is (false? (:duplicate r1)))
        (is (true? (:duplicate r2)))
        (is (>= (long (get-in r1 [:items "grande-bola"] 0)) 2))
        (is (>= gb 2)))

      (testing "2) captura consome Grande Bola"
        (collection/adicionar-pokemon! store chat player squirtle 100 nil)
        (let [cap (capture-app/attempt-capture!
                   store chat player
                   {:operation-id "h-cap-1"
                    :pokemon {:nome "Zubat" :species "zubat" :nivel 5
                              :hp 20 :hp-atual 20 :ataque 10 :defesa 10
                              :atq-esp 10 :def-esp 10 :veloc 30
                              :tipos ["poison" "flying"] :raridade "comum"}
                    :bola "grande-bola" :roll 0 :fuga-roll 99})
              depois (long (or (some #(when (= "grande-bola" (:item-id %)) (:qty %))
                                     (ports/list-inventory store chat player "a"))
                               0))]
          (is (true? (:capturou? cap)))
          (is (= (dec gb) depois))))

      (testing "3) batalha wild → aguardando captura"
        (let [bid "h-battle-1"
              _ (battle-app/start-wild-battle!
                 store chat player
                 {:operation-id "h-start" :battle-id bid
                  :pokemon-x squirtle :pokemon-o wild-weak
                  :hp-x 100 :hp-o 5 :collection-index 0})
              turn (battle-app/confirm-turn!
                    store chat player
                    {:operation-id "h-win" :battle-id bid
                     :expected-turn-index 0 :action :attack :move-index 0
                     :rng rng-hit})
              b (:battle turn)
              base (long (:chance-base-captura b 0))]
          (is (true? (:aguardando-captura? b)))
          (is (pos? base))
          (is (= (capture/chance-com-bola base "grande-bola")
                 (get (capture/chances-painel base false) "grande-bola")))))

      (testing "4) raid 1-mon: join sela 1; faint solo → derrota; sem troca"
        (collection/adicionar-pokemon! store chat player mon-fragile 1 nil)
        (collection/adicionar-pokemon! store chat player mon-reserve 120 nil)
        (let [col (ports/list-collection store chat player "a")
              fragile (some #(when (= "A" (:nome %)) %) col)
              idx (long (:collection-index fragile 0))
              rid "h-raid-1"
              _ (raid-app/open-raid!
                 store chat player
                 {:operation-id "h-open" :raid-id rid :ginasio "pedra"
                  :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                  :chefe chefe :min-jogadores 1 :agora 1000})
              join (raid-app/join-raid!
                    store chat player
                    {:operation-id "h-join" :raid-id rid
                     :collection-index idx :nome "Ash" :agora 1001})
              _ (is (= 1 (count (:party (:participant join)))))
              _ (raid-app/start-raid!
                 store chat player
                 {:operation-id "h-raid-start" :raid-id rid :agora 1002})
              atk (raid-app/attack!
                   store chat player
                   {:operation-id "h-atk" :raid-id rid
                    :expected-turn-index 0 :move-index 0 :agora 1010})
              raid (raid-app/get-raid store rid)]
          (is (true? (:derrota? atk)))
          (is (false? (:trocou? atk)))
          (is (true? (:eliminado? atk)))
          (is (= "derrota" (:fase raid)))
          (is (= "A" (get-in raid [:participantes player :pokemon :nome])))
          (is (zero? (long (get-in raid [:participantes player :hp] 0))))
          (is (pos? (double (:multiplicador atk 1.0))))))

      (testing "5) reopen JVM — evento/missões + estado da raid sobrevivem"
        (let [env2 (reopen env)
              s2 (:store env2)
              snap (missions/read-missions s2 chat player "a"
                                           {:instant instant :timezone "UTC"})
              ev (some #(when (= "noite-sombras" (or (:id %) (:catalog-id %))) %)
                       (:events snap))
              raid2 (raid-app/get-raid s2 "h-raid-1")
              ash (get-in raid2 [:participantes player])
              save (ports/get-save s2 chat player "a")
              occs (tstore/active-occurrences (d/db (:conn env2)) chat agora)
              inv2 (ports/list-inventory s2 chat player "a")]
          (is (some? ev) "missão temática visível após restart")
          (is (= "active" (:status ev)))
          (is (= "derrota" (:fase raid2)))
          (is (= 1 (count (:party ash))))
          (is (zero? (long (:hp ash 0))))
          (is (= 1 (count occs)))
          (is (= "a" (:save-id save "a")))
          (is (sequential? inv2)))))))

(deftest homolog-abc-taxas-captura-aprovadas
  (is (= 78 (get capture/raridade-chance-base "comum")))
  (is (= 58 (get capture/raridade-chance-base "incomum")))
  (is (= 28 (get capture/raridade-chance-base "raro")))
  (is (= 14 (get capture/raridade-chance-base "epico")))
  (is (= 5 (get capture/raridade-chance-base "lendario")))
  (is (= 3 (get capture/raridade-chance-base "mitico")))
  (is (= 35 (raid/chance-captura "raro")))
  (is (= 20 (raid/chance-captura "epico")))
  (is (= 8 (raid/chance-captura "lendario")))
  (is (= 4 (raid/chance-captura "mitico")))
  (is (= 1.5 (get-in capture/bolas ["grande-bola" :multiplicador-captura])))
  (is (= 2.0 (double (get-in capture/bolas ["ultra-bola" :multiplicador-captura]))))
  (is (= 1.5 capture/frambo-multiplicador-captura)))
