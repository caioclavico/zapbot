(ns pokemon.tests.raid-faint-continue-a6-test
  "P0: desmaio de um mon da party NAO encerra a raid se ha reserva viva."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.raid :as raid]))

(def ^:private agora 1000000)

(defn- mon
  [nome hp & {:keys [tipos poder]}]
  {:nome nome :hp hp :hp-atual hp :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40
   :veloc 50 :tipos (or tipos ["normal"]) :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Hit" :tipo (or (first tipos) "normal")
             :poder (or poder 80) :classe :fisico}]})

(defn- raid-combate
  [part]
  (let [base (raid/nova-raid {:raid-id "r1" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["grass"] :defesa 40 :def-esp 40
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        r (raid/entrar base {:player-id "ash" :nome "Ash" :save-id "a"
                             :fence-generation 1 :party part} agora)]
    (raid/iniciar r "ash" (inc agora))))

(defn- forcar-hp-ativo
  [raid pid hp]
  (let [part (get-in raid [:participantes pid])
        idx (long (:active-party-idx part 0))
        part' (-> part
                  (assoc-in [:party idx :hp] hp)
                  (assoc :hp hp))]
    (assoc-in raid [:participantes pid] part')))

(deftest solo-com-reserva-continua-apos-desmaio
  (testing "ativo desmaia troca fase permanece combate"
    (let [party [{:pokemon (mon "A" 50) :collection-index 0}
                 {:pokemon (mon "B" 100) :collection-index 1}]
          r0 (-> (raid-combate party) (forcar-hp-ativo "ash" 1))
          out (raid/atacar r0 "ash" 0 (+ agora 10))]
      (is (false? (:derrota? out)))
      (is (= "combate" (:fase (:raid out))))
      (is (true? (:trocou? out)))
      (is (pos? (get-in out [:raid :participantes "ash" :hp])))
      (is (= 1 (get-in out [:raid :participantes "ash" :active-party-idx])))
      (is (= "B" (get-in out [:raid :participantes "ash" :pokemon :nome]))))))

(deftest solo-sem-reserva-derrota
  (let [party [{:pokemon (mon "Solo" 50) :collection-index 0}]
        r0 (-> (raid-combate party) (forcar-hp-ativo "ash" 1))
        out (raid/atacar r0 "ash" 0 (+ agora 10))]
    (is (true? (:derrota? out)))
    (is (= "derrota" (:fase (:raid out))))))

(deftest efetividade-super-aumenta-dano
  (let [neutro (raid/calcular-dano
                {:poder 80 :classe :fisico :tipo "normal"}
                {:ataque 80}
                {:hp-chefe 1000 :defesa 50.0
                 :chefe {:tipos ["normal"]}})
        super (raid/calcular-dano
               {:poder 80 :classe :fisico :tipo "fire"}
               {:ataque 80}
               {:hp-chefe 1000 :defesa 50.0
                :chefe {:tipos ["grass"]}})]
    (is (= 1.0 (:multiplicador neutro)))
    (is (= 2.0 (:multiplicador super)))
    (is (= (:base neutro) (:base super)))
    (is (= (* 2 (:base super)) (:dano super)))
    (is (> (:dano super) (:dano neutro)))))

(deftest imunidade-zera-dano
  (let [r (raid/calcular-dano
           {:poder 80 :classe :fisico :tipo "normal"}
           {:ataque 80}
           {:hp-chefe 1000 :defesa 50.0
            :chefe {:tipos ["ghost"]}})]
    (is (zero? (:multiplicador r)))
    (is (zero? (:dano r)))))
