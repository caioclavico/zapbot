(ns pokemon.tests.moves-audit-a6-test
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.moves-audit :as audit]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.species-hp :as species-hp]))

(deftest legado-nome-string-roundtrip
  (let [legado {"nome" "Investida" "tipo" "normal" "poder" 40 "classe" "fisico"}
        reg (moves/golpe->registro legado)
        back (moves/registro->golpe reg)
        slim (battle/slim-golpe-for-battle legado)]
    (is (= "investida" (get reg "slug")))
    (is (= "Investida" (get reg "nome-exibicao")))
    (is (= "Investida" (:nome-exibicao slim)))
    (is (= 40 (long (:poder slim))))
    (is (not= "?" (:nome-exibicao slim)))
    (is (audit/roundtrip-legado-ok? legado))
    (is (= "Investida" (:nome-exibicao back)))))

(deftest wartortle-primeiro-golpe-legado
  (let [wart {:species "wartortle" :nome "Wartortle" :nivel 16
              :golpes [{"nome" "Tackle" "tipo" "normal" "poder" 40 "classe" "fisico"}
                       {"nome" "Water Gun" "tipo" "water" "poder" 40 "classe" "especial"}
                       {"nome" "Tail Whip" "tipo" "normal" "poder" 0 "classe" "status"}]}
        slim (battle/slim-pokemon-for-battle wart)
        g0 (first (:golpes slim))
        a (audit/analisar-pokemon wart)]
    (is (seq (:nome-exibicao g0)))
    (is (not= "?" (:nome-exibicao g0)))
    (is (pos? (long (:poder g0 0))))
    (is (:ok? a))))

(deftest explosion-preserva-autodesmaio-no-registro
  (let [g {:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
           :poder 250 :classe :fisico :autodesmaio true}
        reg (moves/golpe->registro g)
        back (moves/registro->golpe reg)]
    (is (true? (get reg "autodesmaio")))
    (is (true? (:autodesmaio back)))))

(deftest status-poder-zero-nao-e-problema
  (let [g {"nome" "Protect" "tipo" "normal" "poder" 0 "classe" "status"}
        a (audit/analisar-golpe g)]
    (is (= :ok (:status a)))))

(deftest aggregate-catalogo-amostra
  (let [pool [{"nome" "Tackle" "poder" 40 "tipo" "normal" "classe" "fisico"}
              {"nome" "Growl" "poder" 0 "tipo" "normal" "classe" "status"}
              {}]
        pokes (mapv (fn [g] {:species "bulbasaur" :golpes [g]}) pool)
        agg (audit/aggregate pokes)]
    (is (pos? (:com-problemas agg)))
    (is (pos? (get-in agg [:problemas-freq :sem-nome] 0)))))

(deftest species-hp-intacto
  (is (= "species-hp-v1" species-hp/formula-version))
  (is (= 320 raid/hp-por-jogador))
  (is (= 1 raid/max-party)))
