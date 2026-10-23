(ns pokemon.tests.evolution-moves-a54-test
  "A5.4 — regressões persistentes de evolução e ciclo de golpes."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.evolution :as evolution]
            [pokemon.application.moves :as moves]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as errors]
            [pokemon.domain.evolution :as domain-evolution]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-a54-evolution-moves")
(def ^:private storage ".datomic-local/a54-evolution-moves")

(defn- fresh-store! [db]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db)
    (let [s (store/make-store (persistence/ensure-db! client db))]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store [db]
  (let [client (persistence/client {:system system :storage-dir storage})
        s (store/make-store (persistence/ensure-db! client db))]
    (trainer/bootstrap! s)
    s))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pedra-trovao" :qty 1}}))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(def ^:private base-pokemon
  {:nome "Eevee" :hp 55 :hp-atual 40 :ataque 55 :defesa 50
   :atq-esp 45 :def-esp 65 :veloc 55 :tipos ["normal"] :nivel 20
   :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
             :poder 40 :classe :fisico}]})

(deftest evolucao-por-nivel-e-amizade-e-deterministica
  (let [reg (assoc base-pokemon :amizade 80)
        dados {:nivel-min 20 :amizade-min 70}]
    (is (true? (domain-evolution/elegivel-nivel? reg dados)))
    (is (false? (domain-evolution/elegivel-nivel?
                 (assoc reg :amizade 10) dados)))
    (is (false? (domain-evolution/elegivel-nivel?
                 (assoc reg :nivel 19) dados)))))

(deftest evolucao-com-item-e-retry-apos-reabertura
  (let [db "a54-evolution" chat "a54-chat" player "ash"]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (collection/adicionar-pokemon! s chat player base-pokemon 20 nil)
      (let [req {:operation-id "a54-evolve"
                 :chat-id chat :player-id player :save-id "a"
                 :fence-generation (fence s chat player) :idx 0
                 :item-id "pedra-trovao"
                 :dados {:nome-novo "Jolteon" :imagem "jolteon.png"
                         :tipos ["electric"] :habilidade "volt-absorb"
                         :hp 65 :ataque 65 :defesa 60 :atq-esp 110
                         :def-esp 95 :veloc 130}}
            first-result (evolution/evolve! s chat player req)
            retry (evolution/evolve! s chat player req)]
        (is (false? (:duplicate first-result)))
        (is (true? (:duplicate retry)))))
    (let [s (reopen-store db)
          poke (first (ports/list-collection s chat player "a"))]
      (is (= "Jolteon" (:nome poke)))
      (is (= 30 (:hp-atual poke))))))

(deftest ciclo-de-golpes-preserva-historico-e-idempotencia
  (let [s (fresh-store! "a54-moves") chat "a54-moves-chat" player "misty"]
    (boot! s chat player)
    (collection/adicionar-pokemon!
     s chat player
     (assoc base-pokemon
            :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                      :poder 40 :classe :fisico}
                     {:slug "quick-attack" :nome-exibicao "Ataque Rápido"
                      :tipo "normal" :poder 40 :classe :fisico}
                     {:slug "growl" :nome-exibicao "Rugido" :tipo "normal"
                      :poder 0 :classe :status}
                     {:slug "tail-whip" :nome-exibicao "Chicote de Cauda"
                      :tipo "normal" :poder 0 :classe :status}])
     20 nil)
    (let [offer (moves/offer-move!
                 s chat player {:operation-id "a54-offer" :idx 0
                                :golpe {:slug "swift" :nome-exibicao "Swift"
                                        :tipo "normal" :poder 60 :classe :especial}})
          learn-req {:operation-id "a54-learn" :idx 0 :slot 3 :oferta? true
                     :golpe {:slug "swift" :nome-exibicao "Swift"
                             :tipo "normal" :poder 60 :classe :especial}}
          learned (moves/learn-move! s chat player learn-req)
          retry (moves/learn-move! s chat player learn-req)]
      (is (= :pendente (:resultado offer)))
      (is (false? (:duplicate learned)))
      (is (true? (:duplicate retry))))))

(deftest move-invalido-nao-consome-estado
  (let [s (fresh-store! "a54-invalid") chat "a54-invalid-chat" player "brock"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player base-pokemon 20 nil)
    (let [code (try
                 (moves/forget-move!
                  s chat player {:operation-id "a54-forget"
                                 :idx 0 :slot 0})
                 :ok
                 (catch Exception ex (errors/error-code ex)))]
      (is (= :cannot_forget_last_move code))
      (is (= ["tackle"]
             (mapv #(get % "slug") (:golpes (first (ports/list-collection s chat player "a")))))))))
