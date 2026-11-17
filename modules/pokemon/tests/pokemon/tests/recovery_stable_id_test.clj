(ns pokemon.tests.recovery-stable-id-test
  "IDs estáveis A6.4: colisões, duplicatas, reordenação, snapshots divergentes."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.application.recovery-stable-id :as sid]))

(defn- poke
  [m]
  (merge {:nome "Pikachu" :shiny false :ataque 10 :defesa 10
          :atq-esp 10 :def-esp 10 :veloc 10 :hp 30 :habilidade "static"
          :tipos ["elétrico"] :imagem "img/pika" :nivel 5 :hp-atual 12
          :xp-desde-nivel 3 :status "normal" :golpes ["thunder"]}
         m))

(defn- account [equipe]
  {:equipe equipe :pc [] :enfermaria []})

(deftest nucleo-ignora-volateis
  (let [a (sid/stable-core (poke {:nivel 5 :hp-atual 10 :xp-desde-nivel 1 :golpes ["a"]}))
        b (sid/stable-core (poke {:nivel 99 :hp-atual 1 :xp-desde-nivel 999 :golpes ["b" "c"]
                                  :status "ko" :collection-index 7}))]
    (is (= a b))
    (is (= (sid/core-digest a) (sid/core-digest b)))))

(deftest id-sintetico-estavel-e-versionado
  (let [core (sid/stable-core (poke {}))
        id1 (sid/propose-synthetic-id {:chat-id "g@g.us" :player-id "p@lid" :core core})
        id2 (sid/propose-synthetic-id {:chat-id "g@g.us" :player-id "p@lid" :core core})
        id3 (sid/propose-synthetic-id {:chat-id "other@g.us" :player-id "p@lid" :core core})]
    (is (= id1 id2))
    (is (not= id1 id3))
    (is (re-find #"^a64sid:a64-stable-v1:" id1))))

(deftest duplicatas-mesma-especie-ficam-ambiguous
  (let [c (sid/classify-account
           {:chat-id "g@g.us" :player-id "p@lid"
            :account (account [(poke {}) (poke {:nivel 9 :hp-atual 2})])})]
    (is (= 2 (get-in c [:status-counts :ambiguous])))
    (is (false? (:import-ready? c)))
    (is (every? nil? (map :proposed-id (:rows c))))))

(deftest especie-igual-stats-diferentes-sao-distintas
  (let [c (sid/classify-account
           {:chat-id "g@g.us" :player-id "p@lid"
            :account (account [(poke {:ataque 10}) (poke {:ataque 20})])})]
    (is (= 2 (get-in c [:status-counts :synthesizable])))
    (is (true? (:import-ready? c)))
    (is (= 2 (count (set (map :proposed-id (:rows c))))))))

(deftest reordenacao-preserva-proposed-ids
  (let [eq [(poke {:nome "A" :ataque 1}) (poke {:nome "B" :ataque 2})]
        a (sid/classify-account {:chat-id "g" :player-id "p" :account (account eq)})
        b (sid/classify-account {:chat-id "g" :player-id "p"
                                 :account (account (reverse eq))})]
    (is (true? (sid/reorder-invariant? a b)))))

(deftest snapshots-divergentes-mesmo-nucleo-mesmo-sintetico
  (let [core-poke (poke {:nivel 1})
        progressed (poke {:nivel 50 :hp-atual 1 :xp-desde-nivel 80})
        id-a (:proposed-id (first (:rows (sid/classify-account
                                          {:chat-id "g" :player-id "p"
                                           :account (account [core-poke])}))))
        id-b (:proposed-id (first (:rows (sid/classify-account
                                          {:chat-id "g" :player-id "p"
                                           :account (account [progressed])}))))]
    (is (= id-a id-b))))

(deftest native-id-preferido-e-borrow-unico
  (let [donor (account [(poke {:id-pokemon "native-1" :ataque 7})])
        target (account [(poke {:ataque 7 :nivel 3})
                         (poke {:ataque 7 :nivel 4})]) ; duplicate cores → ambiguous borrow
        single (account [(poke {:ataque 7})])
        borrows (sid/cross-snapshot-borrow donor single)
        amb (sid/cross-snapshot-borrow donor target)]
    (is (= :eligible-borrow (:status (first borrows))))
    (is (= "native-1" (:borrow-id (first borrows))))
    (is (every? #(= :ambiguous (:status %)) amb))
    (testing "classify com id nativo"
      (let [c (sid/classify-account {:chat-id "g" :player-id "p" :account donor})]
        (is (= :native (:status (first (:rows c)))))
        (is (= "native-1" (:proposed-id (first (:rows c)))))))))

(deftest matriz-colisao-agrega-riscos
  (let [ok (sid/classify-account {:chat-id "g" :player-id "p"
                                  :account (account [(poke {:ataque 1})])})
        bad (sid/classify-account {:chat-id "g" :player-id "p"
                                   :account (account [(poke {}) (poke {})])})
        m (sid/collision-risk-matrix [ok bad])]
    (is (= 3 (:pokemon m)))
    (is (= 1 (:synthesizable m)))
    (is (= 2 (:ambiguous m)))
    (is (= 1 (:import-ready-accounts m)))))
