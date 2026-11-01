(ns pokemon.tests.raid-fase12-test
  "Fase 12 — turnos canônicos, lobby 5 min, invariante de vez."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as auto]
            [pokemon.infrastructure.http.json :as json]))

(def ^:private boss
  {:nome "Onix" :species "onix" :tipos ["rock"] :raridade "raro"
   :hp 70 :ataque 60 :defesa 100 :atq-esp 40 :def-esp 55 :veloc 50
   :golpes [{:nome-exibicao "Rock Throw" :tipo "rock" :poder 50 :classe :fisico}
            {:nome-exibicao "Earthquake" :tipo "ground" :poder 80 :classe :fisico}]})

(defn- mon
  [nome]
   {:nome nome :species (str/lower-case nome) :nivel 5 :hp 100 :hp-atual 100
   :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}
            {:nome-exibicao "Growl" :tipo "normal" :poder 0 :classe :status}]})

(defn- join
  [r pid nome]
  (raid/entrar r {:player-id pid :nome nome :save-id "a"
                  :fence-generation 1 :collection-index 0
                  :pokemon (mon nome)} 1000))

(deftest lobby-padrao-5-minutos
  (is (= (* 5 60 1000) raid/duracao-inscricoes))
  (is (= raid/duracao-inscricoes auto/min-interval-ms)
      "intervalo mínimo auto ≥ duração do lobby")
  (let [r (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                           :liga "iniciante" :chefe boss :agora 1000
                           :criador "ash@lid"})]
    (is (= (+ 1000 raid/duracao-inscricoes) (:expira r)))))

(deftest json-camel-strips-question-mark
  (let [s (json/write-str {:sua-vez? true :inscrito? false :voce? true
                           :vez-valida? true :captura-pendente false})]
    (is (re-find #"\"suaVez\":true" s))
    (is (re-find #"\"inscrito\":false" s))
    (is (re-find #"\"voce\":true" s))
    (is (re-find #"\"vezValida\":true" s))
    (is (not (re-find #"suaVez\?" s)))
    (is (not (re-find #"inscrito\?" s)))))

(deftest vez-inicial-valida-apos-inicio
  (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "111@lid"})
        r (-> base
              (join "111@lid" "Ash")
              (join "222@c.us" "Misty")
              (raid/iniciar "111@lid" 1000))]
    (is (= "combate" (:fase r)))
    (is (= "111@lid" (:vez r)))
    (is (raid/vez-valida? r))))

(deftest ataque-exige-identidade-canonica-exata
  (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "111@lid"})
        r0 (-> base
               (join "111@lid" "Ash")
               (join "222@c.us" "Misty")
               (raid/iniciar "111@lid" 1000))
        err-lid (try (raid/atacar r0 "111@c.us" 0 1001)
                     (catch Exception ex (err/error-code ex)))
        err-outro (try (raid/atacar r0 "222@c.us" 0 1001)
                       (catch Exception ex (err/error-code ex)))
        ok (raid/atacar r0 "111@lid" 0 1001)]
    (is (= :raid_not_your_turn err-lid))
    (is (= :raid_not_your_turn err-outro))
    (is (pos? (:dano ok)))
    (is (= "222@c.us" (get-in ok [:raid :vez])))
    (is (raid/vez-valida? (:raid ok)))))

(deftest incidente-todos-bloqueados-por-vez-invalida
  (testing "ponteiro fantasma → raid_turn_invalid (não mascara autorização)"
    (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                                :liga "iniciante" :chefe boss :agora 1000
                                :criador "111@lid"})
          r0 (-> base
                 (join "111@lid" "Ash")
                 (join "222@lid" "Misty")
                 (raid/iniciar "111@lid" 1000)
                 (assoc :vez "ghost@lid"))
          codes (mapv (fn [pid]
                        (try (raid/atacar r0 pid 0 1001)
                             (catch Exception ex (err/error-code ex))))
                      ["111@lid" "222@lid" "ghost@lid"])]
      (is (every? #{:raid_turn_invalid} codes)))))

(deftest rotacao-completa-entre-jogadores
  (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "a@lid"})
        r0 (-> base
               (join "a@lid" "A")
               (join "b@lid" "B")
               (raid/iniciar "a@lid" 1000))
        a1 (raid/atacar r0 "a@lid" 0 1001)
        r1 (:raid a1)
        a2 (raid/atacar r1 "b@lid" 0 1002)
        r2 (:raid a2)]
    (is (= "b@lid" (:vez r1)))
    (is (= "a@lid" (:vez r2)))
    (is (raid/vez-valida? r2))))

(deftest sair-em-combate-realoca-vez
  (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "a@lid"})
        r0 (-> base
               (join "a@lid" "A")
               (join "b@lid" "B")
               (join "c@lid" "C")
               (raid/iniciar "a@lid" 1000))
        r1 (raid/sair-combate r0 "a@lid" 1001)]
    (is (= "b@lid" (:vez r1)))
    (is (raid/vez-valida? r1))
    (is (not (contains? (:participantes r1) "a@lid")))))

(deftest golpe-status-rejeitado
  (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                              :liga "iniciante" :chefe boss :agora 1000
                              :criador "a@lid"})
        r0 (-> base (join "a@lid" "A") (join "b@lid" "B")
               (raid/iniciar "a@lid" 1000))
        err (try (raid/atacar r0 "a@lid" 1 1001)
                 (catch Exception ex (err/error-code ex)))]
    (is (= :invalid_raid_move err))))
