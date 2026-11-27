(ns pokemon.tests.accuracy-persistence-a6-test
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.move-effects :as effects]
            [pokemon.domain.rng :as rng]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(defn- isolated-store []
  (let [id (str (java.util.UUID/randomUUID))
        client (persistence/client {:system (str "accuracy-" id)
                                    :storage-dir (str "/tmp/accuracy-" id)})
        s (store/make-store (persistence/ensure-db! client "isolado"))]
    (trainer/bootstrap! s)
    s))

(deftest precisao-persiste-no-snapshot-e-menu-reflete-catalogo
  (let [thunder {"slug" "thunder" "tipo" "electric" "poder" 110
                 "classe" "especial" "precisao" 100}
        toxic {"slug" "toxic" "tipo" "poison" "poder" 0
               "classe" "status" "precisao" 95}
        slim (battle/slim-golpe-for-battle thunder)
        apresentar (ns-resolve 'pokemon.application.bridge-tx 'present-pokemon)
        dto (apresentar {:hp 100 :golpes [thunder toxic]} 100)]
    (is (= 100 (:precisao slim) (:precisao (effects/enriquecer slim))))
    (is (= [100 90] (mapv :precisao (:golpes dto))))
    (is (= 90 (:precisao (effects/enriquecer (battle/golpe-normalizado toxic)))))))

(deftest menu-inicial-do-ginasio-usa-precisao-do-motor
  (let [poke {:nome "A" :hp 100
              :golpes [{"slug" "toxic" "tipo" "poison" "poder" 0
                        "classe" "status" "precisao" 95}]}]
    (with-redefs [gym/challenges-enabled? (constantly true)
                  gym-app/start-challenge!
                  (fn [& _] {:battle {:pokemons {:x poke :o poke}}})]
      (let [r (tx/start-gym-challenge!
               nil {:chat-id "c" :player-id "p" :save-id "a" :fence-generation 1
                    :operation-id "op" :gym-id "g"})]
        (is (= 90 (get-in r [:battle :pokemon-x :golpes 0 :precisao])))
        (is (= 90 (get-in r [:battle :pokemon-o :golpes 0 :precisao])))))))

(deftest raid-retry-preserva-erro-sem-novo-sorteio
  (let [s (isolated-store)
        p {:nome "Lugia" :species "lugia" :hp 200 :ataque 80 :defesa 80
           :atq-esp 80 :def-esp 80 :veloc 40 :tipos ["flying"] :nivel 20
           :raridade "comum" :golpes [{:slug "aeroblast" :tipo "flying"
                                       :poder 100 :classe :especial :precisao 95}]}
        chefe {:nome "Onix" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50
               :veloc 30 :tipos ["rock"] :nivel 20 :raridade "comum"}
        _ (trainer/create-trainer! s {:chat-id "c" :player-id "p"})
        _ (collection/adicionar-pokemon! s "c" "p" p 200 nil)
        ctx {:chat-id "c" :player-id "p" :save-id "a"
             :fence-generation (long (:fence-generation (ports/get-player s "c" "p")))}]
    (tx/open-raid! s (merge ctx {:operation-id "open" :raid-id "r" :ginasio "pedra"
                                 :chefe chefe :agora-ms 1000}))
    (tx/join-raid! s (merge ctx {:operation-id "join" :raid-id "r"
                                 :collection-index 0 :nome "A" :agora-ms 1001}))
    (tx/start-raid! s (merge ctx {:operation-id "start" :raid-id "r" :agora-ms 1002}))
    (let [r (raid-app/get-raid s "r")
          req (merge ctx {:operation-id "attack" :raid-id "r" :move-index 0
                          :expected-turn-index (:turn-index r) :agora-ms 1003
                          :rng {:accuracy 99}})
          original (tx/raid-attack! s req)
          estado (raid-app/get-raid s "r")
          repetido (with-redefs [rng/roll (fn [& _] (throw (ex-info "sorteio duplicado" {})))]
                     (tx/raid-attack! s req))]
      (is (:errou original))
      (is (:duplicate repetido))
      (is (:errou repetido))
      (is (= (:dano original) (:dano repetido) 0))
      (is (= (:revide original) (:revide repetido)))
      (is (= (:golpe original) (:golpe repetido)))
      (is (= (:pokemon-atacante original) (:pokemon-atacante repetido)))
      (is (= (:turn-index estado) (:turn-index (raid-app/get-raid s "r")))))))

(defn- post [base path]
  (let [body "{\"chatId\":\"c\",\"playerId\":\"p\",\"saveId\":\"a\",\"fenceGeneration\":1,\"operationId\":\"op\",\"battleId\":\"b\",\"expectedTurnIndex\":0,\"action\":\"attack\",\"moveIndex\":0,\"rng\":{\"accuracy\":99}}"
        request (-> (HttpRequest/newBuilder (URI/create (str base path)))
                    (.header "Authorization" "Bearer test")
                    (.header "X-Actor-Player-Id" "p")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    .build)]
    (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))))

(deftest rotas-so-aceitam-rng-no-modo-de-teste
  (let [s (isolated-store)
        recebidas (atom [])]
    (with-redefs [tx/confirm-battle-turn! (fn [_ req] (swap! recebidas conj req) {:ok true})
                  tx/confirm-pvp-turn! (fn [_ req] (swap! recebidas conj req) {:ok true})
                  tx/raid-attack! (fn [_ req] (swap! recebidas conj req) {:ok true})]
      (doseq [permitido? [false true]]
        (let [server (bridge/start! {:store s :port 0 :token "test"
                                     :allow-test-rng permitido?})]
          (try
            (doseq [path ["/v1/battles/turn" "/v1/pvp/turn" "/v1/raids/attack"]]
              (let [antes (count @recebidas)]
                (is (= (if permitido? 200 400) (.statusCode (post (:base-url server) path))))
                (is (= (+ antes (if permitido? 1 0)) (count @recebidas)))
                (when permitido?
                  (is (= 99 (get-in (peek @recebidas) [:rng "accuracy"]))))))
            (finally ((:stop! server)))))))))
