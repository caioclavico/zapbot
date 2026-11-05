(ns pokemon.tests.gym-battle-hp-a6-test
  "Caracterização/correção: HP inicial em batalhas de ginásio."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.gym :as gym]))

(defn- mon
  [m]
  (merge {:nome "X" :species "x" :hp 100 :ataque 50 :defesa 50
          :atq-esp 50 :def-esp 50 :veloc 50 :tipos ["normal"] :nivel 10
          :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40
                    :classe :fisico :precisao 100}]}
         m))

(defn- gym-battle
  [team-x team-o]
  (battle/create-gym-battle
   {:battle-id "b-hp" :chat-id "c" :player-id "p" :save-id "a"
    :fence-generation 0 :gym-id "pedra" :gym-nome "Pedra"
    :team-x (mapv #(assoc % :collection-index (:collection-index % 0)) team-x)
    :team-o team-o}))

(deftest hp-inicial-cheio-parcial-zero-e-ausente
  (testing "hp-atual nil → max"
    (let [b (gym-battle
             [(assoc (mon {:hp 80 :hp-atual nil}) :collection-index 0)
              (assoc (mon {}) :collection-index 1)
              (assoc (mon {}) :collection-index 2)]
             [(mon {:hp 70}) (mon {}) (mon {})])]
      (is (= 80 (get-in b [:hp :x])))
      (is (= 70 (get-in b [:hp :o])))))
  (testing "hp-atual parcial preservado e clampado"
    (let [b (gym-battle
             [(assoc (mon {:hp 80 :hp-atual 40}) :collection-index 0)
              (assoc (mon {}) :collection-index 1)
              (assoc (mon {}) :collection-index 2)]
             [(mon {:hp 90 :hp-atual 120}) (mon {}) (mon {})])]
      (is (= 40 (get-in b [:hp :x])))
      (is (= 90 (get-in b [:hp :o])) "acima do max → clamp")))
  (testing "hp-atual 0 legítimo (desmaiado) não cura"
    (let [b (gym-battle
             [(assoc (mon {:hp 80 :hp-atual 0}) :collection-index 0)
              (assoc (mon {:hp 80 :hp-atual 80}) :collection-index 1)
              (assoc (mon {}) :collection-index 2)]
             [(mon {:hp 50 :hp-atual 0}) (mon {:hp 50 :hp-atual 50}) (mon {})])]
      (is (zero? (get-in b [:hp :x])))
      (is (zero? (get-in b [:hp :o]))))))

(deftest motivacao-copia-comeca-com-hp-efetivo-cheio
  (testing "snapshot com hp-atual 0 stale não zera barra do defensor"
    (let [poke (mon {:hp 100 :hp-atual 0})
          scaled (gym/aplicar-motivacao-pokemon poke 100)]
      (is (= (:hp scaled) (:hp-atual scaled)))
      (is (pos? (:hp-atual scaled)))))
  (testing "motivação baixa reduz resistência sem HP zero"
    (let [poke (mon {:hp 100 :hp-atual 100})
          scaled (gym/aplicar-motivacao-pokemon poke 20)]
      (is (= 60 (:hp scaled)))
      (is (= 60 (:hp-atual scaled)))
      (is (pos? (:hp-atual scaled)))))
  (testing "time-defensor aplica motivação e batalha abre com HP efetivo"
    (let [          agora 1000000000000
          ocup {:desde agora :motivacao-em agora
                :motivacao [100 100 100]
                :time [(mon {:hp 100 :hp-atual 0 :nome "A"})
                       (mon {:hp 100 :hp-atual 0 :nome "B"})
                       (mon {:hp 100 :hp-atual 0 :nome "C"})]}
          defs (gym/time-defensor ocup agora)
          b (gym-battle
             [(assoc (mon {:hp 80 :hp-atual 80}) :collection-index 0)
              (assoc (mon {}) :collection-index 1)
              (assoc (mon {}) :collection-index 2)]
             defs)]
      (is (pos? (get-in b [:hp :o])))
      (is (= (get-in b [:hp :o]) (get-in b [:pokemons :o :hp])))
      (is (= 80 (get-in b [:hp :x]))))))

(deftest motivacao-nao-muta-consulta
  (let [agora 2000000000000
        ocup {:desde (- agora (* 10 60 60 1000))
              :motivacao-em (- agora (* 10 60 60 1000))
              :motivacao [100 100 100]
              :time [(mon {}) (mon {}) (mon {})]}
        a (gym/motivacoes ocup agora)
        b (gym/motivacoes ocup agora)]
    (is (= [50 50 50] a))
    (is (= a b))
    (is (= [100 100 100] (:motivacao ocup)))))
