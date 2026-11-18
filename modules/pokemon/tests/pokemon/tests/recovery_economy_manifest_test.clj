(ns pokemon.tests.recovery-economy-manifest-test
  "Economia + manifesto A6.4 sem persistência."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.application.recovery-economy :as eco]
            [pokemon.application.recovery-manifest :as man]))

(deftest economia-unknown-nao-e-zero
  (let [c (eco/classify-player-economy [])]
    (is (= :unknown (:status c)))
    (is (true? (:do-not-default-zero c)))
    (is (nil? (:coins c)))))

(deftest economia-known-e-divergent
  (let [loja {:moedas 10 :inventario {"pokebola" 2}}
        known (eco/classify-player-economy
               [{:source :cassandra-v1-loja :loja loja}
                {:source :cassandra-v2-loja :loja loja}])
        div (eco/classify-player-economy
             [{:source :cassandra-v1-loja :loja loja}
              {:source :cassandra-v2-loja :loja {:moedas 99 :inventario {}}}])]
    (is (= :known (:status known)))
    (is (= 10 (:coins known)))
    (is (= :divergent (:status div)))
    (is (nil? (:coins div)))
    (is (true? (:do-not-default-zero div)))))

(deftest identidade-por-chat-nao-global
  (let [scope (man/identity-scope
               "p@lid"
               [{:chat-id "a@g.us" :fingerprint-hash "h1"}
                {:chat-id "b@g.us" :fingerprint-hash "h2"}])]
    (is (= :per-chat (:canonical-model scope)))
    (is (true? (:multi-chat? scope)))
    (is (true? (:historically-mixed? scope)))))

(deftest manifesto-simula-sem-confirm
  (let [account {:equipe [{:nome "X" :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1
                           :veloc 1 :hp 10 :habilidade "h" :tipos ["a"]
                           :id-pokemon "n1"}]
                 :pc [] :enfermaria []}
        entry (man/entry-from-accounts
               {:chat-id "c@g.us" :player-id "p@lid"
                :source :cassandra-v1
                :account account
                :loja-appearances [{:source :cassandra-v1-loja
                                    :loja {:moedas 5 :inventario {}}}]
                :trainer-status :resolved
                :all-chat-appearances [{:chat-id "c@g.us" :account account}]})
        sim (man/simulate-batch [entry])
        blocked (man/entry-from-accounts
                 {:chat-id "c@g.us" :player-id "p@lid"
                  :source :cassandra-v1
                  :account account
                  :loja-appearances [{:source :v1 :loja {:moedas 1}}
                                     {:source :v2 :loja {:moedas 9}}]
                  :trainer-status :resolved
                  :all-chat-appearances [{:chat-id "c@g.us" :account account}]})]
    (is (true? (:eligible? entry)))
    (is (true? (:dry-run entry)))
    (is (false? (:persist entry)))
    (is (true? (:batch-ready? sim)))
    (is (false? (:confirm-invoked sim)))
    (is (false? (:eligible? blocked)))
    (is (some #{:economy-divergent} (:block-reasons blocked)))
    (testing "validate rejeita eligible+divergent se forçado"
      (let [forced (assoc blocked :eligible? true)
            v (man/validate-entry forced)]
        (is (false? (:ok v)))
        (is (some #{:divergent-economy-not-auto-eligible} (:errors v)))))))
