(ns pokemon.tests.recovery-reconcile-test
  "Reconciliação A6.4: fingerprints, aliases sem inferência, classificação e plano."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.application.recovery-reconcile :as rr]))

(defn- account
  [{:keys [equipe pc moedas inventario ginasios]}]
  (cond-> {:equipe (or equipe [])
           :pc (or pc [])
           :enfermaria []}
    moedas (assoc :loja {:moedas moedas :inventario (or inventario {})})
    ginasios (assoc :ginasios ginasios)))

(defn- poke [nome nivel]
  {:id-pokemon (str "id-" nome "-" nivel)
   :nome nome
   :nivel nivel
   :hp-atual (* 10 nivel)})

(deftest identity-kind-separa-jogador-e-grupo
  (is (= :lid (rr/identity-kind "123@lid")))
  (is (= :c.us (rr/identity-kind "5511@c.us")))
  (is (= :g.us (rr/identity-kind "120@g.us")))
  (is (= :other (rr/identity-kind "x"))))

(deftest fingerprint-estavel-e-sensivel-a-progresso
  (let [a (rr/account-fingerprint
           (account {:equipe [(poke "Bulbasaur" 5)] :moedas 100}))
        b (rr/account-fingerprint
           (account {:equipe [(poke "Bulbasaur" 5)] :moedas 100}))
        c (rr/account-fingerprint
           (account {:equipe [(poke "Bulbasaur" 6)] :moedas 100}))]
    (is (= (:fingerprint a) (:fingerprint b)))
    (is (not= (:fingerprint a) (:fingerprint c)))
    (is (= 5 (:level-sum a)))
    (is (= 100 (:coins a)))))

(deftest nao-infere-alias-sem-evidencia
  (is (empty? (rr/propose-aliases
               [{:current-player-id "a@lid"
                 :historical-player-id "b@c.us"}])))
  (let [ok (rr/propose-aliases
            [{:current-player-id "a@lid"
              :historical-player-id "b@c.us"
              :evidence "admin-confirmed-wa-2026-10"}])]
    (is (= 1 (count ok)))
    (is (= :pending-human (:status (first ok))))
    (is (= :lid (:current-kind (first ok))))
    (is (= :c.us (:historical-kind (first ok))))))

(deftest classifica-resolvido-pendente-inconclusivo
  (let [same (account {:equipe [(poke "Pikachu" 10)] :moedas 50})
        other (account {:equipe [(poke "Pikachu" 12)] :moedas 50})
        resolved (rr/classify-player
                  "p@c.us"
                  [(rr/source-record {:source :cassandra-v1 :player-id "p@c.us"
                                      :chat-id "g@g.us" :account same})
                   (rr/source-record {:source :cassandra-v2 :player-id "p@c.us"
                                      :chat-id "g@g.us" :account same})])
        pending (rr/classify-player
                 "p@c.us"
                 [(rr/source-record {:source :cassandra-v1 :player-id "p@c.us"
                                     :chat-id "g@g.us" :account same})
                  (rr/source-record {:source :cassandra-operational :player-id "p@c.us"
                                     :chat-id "g@g.us" :account other})])
        incomplete (rr/classify-player
                    "p@c.us"
                    [(rr/source-record {:source :cassandra-v1 :player-id "p@c.us"
                                        :chat-id "g@g.us" :account same})
                     {:source :cassandra-v2 :player-id "p@c.us" :chat-id "g@g.us"
                      :player-kind :c.us :chat-kind :g.us :fingerprint nil}])]
    (is (= :resolved (:status resolved)))
    (is (= :pending (:status pending)))
    (is (= :inconclusive (:status incomplete)))))

(deftest reconcile-e-plano-nao-escolhe-vencedor
  (let [same (account {:equipe [(poke "Eevee" 8)] :moedas 10})
        diverged (account {:equipe [(poke "Eevee" 20)] :moedas 999})
        matrix (rr/reconcile
                [(rr/source-record {:source :cassandra-v1 :player-id "a@c.us"
                                    :chat-id "g1@g.us" :account same})
                 (rr/source-record {:source :cassandra-v2 :player-id "a@c.us"
                                    :chat-id "g1@g.us" :account same})
                 (rr/source-record {:source :cassandra-v1 :player-id "b@c.us"
                                    :chat-id "g1@g.us" :account same})
                 (rr/source-record {:source :cassandra-operational :player-id "b@c.us"
                                    :chat-id "g1@g.us" :account diverged})
                 (rr/source-record {:source :cassandra-operational :player-id "c@lid"
                                    :chat-id "g1@g.us" :account same})])
        plan (rr/plan-import-batch matrix)
        anon (rr/anonymize-matrix matrix)]
    (is (= 3 (:player-count matrix)))
    ;; a (legado igual) + c (só operacional, fingerprint único) → resolved
    (is (= 2 (get-in matrix [:status-counts :resolved])))
    (is (= 1 (get-in matrix [:status-counts :pending])))
    (is (= 1 (count (:eligible plan))))
    (is (= 2 (count (:await-human plan))))
    (is (true? (:anonymized? anon)))
    (is (every? #(nil? (:player-id %)) (:rows anon)))
    (is (every? #(string? (:player-hash %)) (:rows anon)))
    (testing "operacional sem legado fica await-human no plano"
      (let [only-op (first (filter #(= "c@lid" (:player-id %)) (:rows matrix)))]
        (is (= :resolved (:status only-op)))
        (is (= :await-human (:action (rr/import-candidate only-op))))))))
