(ns pokemon.tests.move-effects-capture-a6-test
  "Tradução, estágios de status e curva de Pokébola. Sem Datomic."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.move-effects :as fx]
            [pokemon.domain.move-names :as nomes]))

(def ^:private rng-certo
  {:accuracy 0 :crit 99 :damage-var 0 :dodge 99 :flee 99
   :contagio 99 :status-golpe 0 :status-accuracy 0 :wild-move 0
   :impede-para 99 :cura-sono 99 :cura-gelo 99 :cura-confusao 99
   :auto-confusao 99})

(defn- mon [nome golpes]
  {:nome nome :species nome :hp 400 :hp-atual 400
   :ataque 80 :defesa 80 :atq-esp 80 :def-esp 80 :veloc 80
   :tipos ["normal"] :raridade "comum" :golpes golpes})

(deftest catalogo-pt-br-e-ambiguo
  (is (= 937 (count nomes/nomes)))
  (is (= "Recolher-se" (nomes/apresentar {:slug "withdraw" :nome-exibicao "Withdraw"})))
  (is (= "Investida" (nomes/apresentar {:nome-exibicao "Tackle"})))
  (is (= "water-gun" (:slug (nomes/resolver "Jato d'Água"))))
  (is (:ambiguo (nomes/resolver "furacao")))
  (is (= ["drill-run" "hurricane"] (:candidatos (nomes/resolver "furacao"))))
  (is (every? #(and (seq %) (string? %)) (vals nomes/nomes))))

(deftest withdraw-sobe-defesa-e-o-dano-cai
  (let [golpe {:poder 40 :classe :fisico :tipo "normal" :precisao 100}
        atk {:ataque 100 :defesa 50 :atq-esp 40 :def-esp 40 :veloc 40 :hp 100 :tipos ["normal"]}
        def-mon {:ataque 40 :defesa 80 :atq-esp 40 :def-esp 40 :veloc 40 :hp 100 :tipos ["normal"]}
        rng {:accuracy 0 :crit 99 :damage-var 0}
        sem (dmg/resolver-ataque {:golpe golpe :atacante atk :defensor def-mon
                                  :estagios-defensor {} :rng rng})
        com (dmg/resolver-ataque {:golpe golpe :atacante atk :defensor def-mon
                                  :estagios-defensor {:defesa 2} :rng rng})]
    (is (> (:dano sem) (:dano com)))
    (is (= 2 (-> (fx/enriquecer {:slug "withdraw" :classe :status})
                 :alteracoes first :estagios))))
  (let [eu (mon "Wartortle" [{:slug "withdraw" :nome-exibicao "Withdraw"
                              :tipo "water" :classe :status :poder 0}])
        rival (mon "Rattata" [{:slug "tackle" :nome-exibicao "Tackle"
                               :tipo "normal" :classe :fisico :poder 40 :precisao 100}])
        batalha (battle/create-wild-battle
                 {:battle-id "b1" :chat-id "c" :player-id "p" :save-id "s"
                  :fence-generation 1 :pokemon-x eu :pokemon-o rival})
        depois (battle/apply-player-turn batalha {:action :attack :move-index 0} rng-certo)
        msg (:mensagem (:last-action depois))]
    (is (= 2 (get-in depois [:estagios :x :defesa])))
    (is (re-find #"Recolher-se" msg))
    (is (re-find #"Defesa" msg))
    (is (re-find #"2 estágios" msg))
    (is (not (re-find #"efeito especial" (or msg ""))))))

(deftest estagio-trava-em-seis-e-troca-zera
  (let [eu (mon "Squirtle" [{:slug "withdraw" :nome-exibicao "Withdraw"
                             :tipo "water" :classe :status :poder 0}])
        rival (mon "Rattata" [{:slug "tackle" :nome-exibicao "Tackle"
                               :tipo "normal" :classe :fisico :poder 40 :precisao 100}])
        inicial (battle/create-wild-battle
                 {:battle-id "b2" :chat-id "c" :player-id "p" :save-id "s"
                  :fence-generation 1 :pokemon-x eu :pokemon-o rival})
        quarto (reduce (fn [b _]
                         (battle/apply-player-turn b {:action :attack :move-index 0} rng-certo))
                       inicial (range 4))]
    (is (= 6 (get-in quarto [:estagios :x :defesa])))
    (is (re-find #"não pode aumentar" (:mensagem (:last-action quarto)))))
  (let [b {:kind :gym
           :reservas {:o [{:nome "Próximo" :hp 30 :golpes []}]}
           :pokemons {:o {:nome "Atual" :hp 30} :x {:nome "Eu" :hp 30}}
           :hp {:o 0 :x 30}
           :estagios {:o {:defesa 4} :x {:ataque 2}}
           :status {:o nil :x nil}
           :defendendo {:o false :x false}
           :itens-usados {:o {} :x {}}}
        out (#'battle/enviar-proximo b :o)]
    (is (= {} (get-in out [:estagios :o])))
    (is (= {:ataque 2} (get-in out [:estagios :x])))))

(deftest referencia-de-captura-comum
  (let [comum {:raridade "comum" :hp 75 :ataque 75 :defesa 75
               :atq-esp 75 :def-esp 75 :veloc 75}
        raro {:raridade "raro" :hp 75 :ataque 75 :defesa 75
              :atq-esp 75 :def-esp 75 :veloc 75}]
    (is (= 65 (capture/chance-captura comum)))
    (is (= 65 (capture/chance-com-bola 65 "pokebola")))
    (is (= 80 (capture/chance-com-bola 65 "grande-bola")))
    (is (= 95 (capture/chance-com-bola 65 "ultra-bola")))
    (is (< (capture/chance-com-bola 65 "pokebola")
           (capture/chance-com-bola 65 "grande-bola")
           (capture/chance-com-bola 65 "ultra-bola")))
    (is (< (capture/chance-captura raro) (capture/chance-captura comum)))
    (is (= 70 (capture/chance-com-bola 35 "ultra-bola" capture/chance-captura-maxima-raid)))
    (testing "fracasso e sucesso determinísticos"
      (is (true? (:capturou? (capture/attempt-capture
                              {:pokemon comum :bola "pokebola" :chance-base 65
                               :roll 0 :fuga-roll 99}))))
      (is (false? (:capturou? (capture/attempt-capture
                               {:pokemon comum :bola "pokebola" :chance-base 65
                                :roll 99 :fuga-roll 99})))))))
