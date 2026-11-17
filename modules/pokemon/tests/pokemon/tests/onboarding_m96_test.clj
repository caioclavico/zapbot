(ns pokemon.tests.onboarding-m96-test
  "M9.6 — onboarding: kit (10 Pokébolas), starters Gen1, idempotência."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.onboarding :as onboard-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.onboarding :as onboard]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m96-tests")
(def ^:private storage-rel ".datomic-local/m96-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- pokebolas
  [s chat player save-id]
  (->> (ports/list-inventory s chat player save-id)
       (filter #(= "pokebola" (:item-id %)))
       (map :qty)
       (reduce + 0)))

(deftest kit-legado-dez-pokebolas
  (is (= "pokebola" onboard/kit-item-id))
  (is (= 10 onboard/kit-qty))
  (is (= 3 (count onboard/starters)))
  (is (= ["bulbasaur" "charmander" "squirtle"]
         (mapv :species onboard/starters))))

(deftest resolve-starter-numero-e-nome
  (is (= "bulbasaur" (:species (onboard/resolve-starter "1"))))
  (is (= "charmander" (:species (onboard/resolve-starter 2))))
  (is (= "squirtle" (:species (onboard/resolve-starter "Squirtle"))))
  (is (= "charmander" (:species (onboard/resolve-starter "CHARMANDER"))))
  (is (= :invalid_starter
         (try (onboard/resolve-starter "pikachu")
              (catch Exception ex (err/error-code ex))))))

(deftest status-maquina-estados
  (is (= "not_started"
         (onboard/onboarding-status {:kit-inicial-resgatado false
                                     :inicial-escolhido false
                                     :collection-count 0})))
  (is (= "awaiting_starter"
         (onboard/onboarding-status {:kit-inicial-resgatado true
                                     :inicial-escolhido false
                                     :collection-count 0})))
  (is (= "completed"
         (onboard/onboarding-status {:kit-inicial-resgatado true
                                     :inicial-escolhido true
                                     :collection-count 1})))
  (is (= "completed"
         (onboard/onboarding-status {:kit-inicial-resgatado false
                                     :inicial-escolhido false
                                     :collection-count 3}))
      "migrado com coleção não reabre onboarding"))

(deftest jornada-kit-starter-e-idempotencia
  (let [db "s96-journey"
        chat "chat-m96"
        player "ash"
        s (fresh-store! db)]
    (testing "primeiro start concede kit"
      (let [r (tx/start-journey! s {:chat-id chat :player-id player
                                    :operation-id "kit-ash-1"})
            st (:onboarding r)]
        (is (true? (:created r)))
        (is (false? (:already-complete r)))
        (is (= "awaiting_starter" (:status st)))
        (is (= 10 (pokebolas s chat player "a")))
        (is (true? (:kit-claimed st)))
        (is (true? (:starter-available st)))))
    (testing "repetir start não duplica kit"
      (let [r2 (tx/start-journey! s {:chat-id chat :player-id player
                                     :operation-id "kit-ash-1"})
            st (:onboarding r2)]
        (is (false? (:created r2)))
        (is (= 10 (pokebolas s chat player "a")))
        (is (= "awaiting_starter" (:status st)))))
    (testing "escolha por número"
      (let [c (tx/choose-starter!
               s {:chat-id chat :player-id player
                  :operation-id "starter-ash-1" :choice "1"})
            coll (ports/list-collection s chat player "a")
            save (ports/get-save s chat player "a")]
        (is (false? (:duplicate c)))
        (is (= "bulbasaur" (:species c)))
        (is (= 1 (count coll)))
        (is (= "Bulbasaur" (:nome (first coll))))
        (is (= 1 (:nivel (first coll))))
        (is (true? (:inicial-escolhido save)))
        (is (= 0 (:ativo-index save 0)))))
    (testing "repetir mesma escolha (recibo)"
      (let [c2 (tx/choose-starter!
                s {:chat-id chat :player-id player
                   :operation-id "starter-ash-1" :choice "1"})]
        (is (true? (:duplicate c2)))
        (is (= 1 (count (ports/list-collection s chat player "a"))))))
    (testing "outra escolha após completo"
      (let [c3 (tx/choose-starter!
                s {:chat-id chat :player-id player
                   :operation-id "starter-ash-2" :choice "2"})]
        (is (true? (:duplicate c3)))
        (is (true? (:starter-already-chosen c3)))
        (is (= 1 (count (ports/list-collection s chat player "a"))))))
    (testing "start após complete"
      (let [r3 (tx/start-journey! s {:chat-id chat :player-id player
                                     :operation-id "kit-ash-late"})]
        (is (true? (:already-complete r3)))
        (is (= 10 (pokebolas s chat player "a")))
        (is (= 1 (:collection-count (:onboarding r3))))))))

(deftest kit-nao-duplica-ao-trocar-save
  (let [s (fresh-store! "s96-ab")
        chat "chat-ab"
        player "misty"]
    (tx/start-journey! s {:chat-id chat :player-id player
                          :operation-id "kit-misty"})
    (is (= 10 (pokebolas s chat player "a")))
    (is (true? (:kit-inicial-resgatado (ports/get-save s chat player "b"))))
    (trainer/switch-save! s chat player "b")
    (let [st (onboard-app/status s chat player)
          kit (tx/claim-starter-kit!
               s {:chat-id chat :player-id player
                  :operation-id "kit-misty-b"})]
      (is (false? (:kit-available st)))
      (is (true? (:kit-already-claimed kit)))
      (is (= 0 (pokebolas s chat player "b")))
      (is (= 10 (pokebolas s chat player "a"))))))

(deftest starter-por-nome-e-concorrencia
  (let [s (fresh-store! "s96-race")
        chat "chat-race"
        player "brock"]
    (tx/start-journey! s {:chat-id chat :player-id player
                          :operation-id "kit-brock"})
    (let [futs (mapv
                (fn [choice]
                  (future
                    (try
                      (tx/choose-starter!
                       s {:chat-id chat :player-id player
                          :operation-id (str "starter-race-" choice)
                          :choice choice})
                      (catch Exception ex
                        {:error (err/error-code ex)}))))
                ["charmander" "squirtle" "1"])
          results (mapv deref futs)
          ok (filter #(and (map? %) (false? (:duplicate %)) (:species %)) results)
          coll (ports/list-collection s chat player "a")]
      (is (= 1 (count coll)) "apenas um starter")
      (is (= 1 (count ok)) "apenas uma concessão nova"))))

(deftest persistencia-apos-reabrir
  (let [db "s96-persist"
        chat "chat-p"
        player "oak"]
    (let [s (fresh-store! db)]
      (tx/start-journey! s {:chat-id chat :player-id player
                            :operation-id "kit-oak"})
      (tx/choose-starter! s {:chat-id chat :player-id player
                             :operation-id "starter-oak"
                             :choice "squirtle"}))
    (let [s (reopen-store db)
          st (onboard-app/status s chat player)
          coll (ports/list-collection s chat player "a")
          save (ports/get-save s chat player "a")]
      (is (= "completed" (:status st)))
      (is (= 10 (pokebolas s chat player "a")))
      (is (= 1 (count coll)))
      (is (= "squirtle" (str/lower-case (or (:species (first coll))
                                            (:nome (first coll))
                                            ""))))
      (is (true? (:inicial-escolhido save)))
      (is (true? (:kit-inicial-resgatado save))))))

(deftest migrado-com-colecao-nao-recebe-starter
  (let [s (fresh-store! "s96-mig")
        chat "chat-mig"
        player "giovanni"
        mon {:nome "Rattata" :species "rattata"
             :hp 40 :ataque 30 :defesa 20 :atq-esp 20 :def-esp 20
             :veloc 40 :tipos ["normal"] :nivel 5 :raridade "comum"
             :golpes [{:nome-exibicao "Investida" :tipo "normal"
                       :poder 40 :classe :fisico}]}]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (collection/adicionar-pokemon! s chat player mon 40 nil)
    (let [st (onboard-app/status s chat player)
          c (tx/choose-starter!
             s {:chat-id chat :player-id player
                :operation-id "starter-mig" :choice "1"})]
      (is (= "completed" (:status st)))
      (is (false? (:starter-available st)))
      (is (true? (:starter-already-chosen c)))
      (is (= 1 (count (ports/list-collection s chat player "a")))))))
