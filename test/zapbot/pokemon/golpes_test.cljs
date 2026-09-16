(ns zapbot.pokemon.golpes-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [zapbot.pokemon.golpes :as golpes]))

(deftest identificador-aceita-slug-e-nome-traduzido
  (is (= "water-gun" (golpes/identificador "water-gun")))
  (is (= "water-gun" (golpes/identificador "Revólver d'Água")))
  (is (= "vine-whip" (golpes/identificador "Chicote de Vinha")))
  (is (= "golpe-desconhecido" (golpes/identificador "Golpe Desconhecido"))))

(deftest traducao-preserva-dados-do-golpe
  (let [traduzido (golpes/traduzir {:slug "water-gun" :poder 40 :tipo :agua})]
    (is (= "Jato de Água" (:nome-exibicao traduzido)))
    (is (= 40 (:poder traduzido)))
    (is (= :agua (:tipo traduzido)))))

(deftest golpes-unicos-usam-o-identificador-normalizado
  (let [resultado (golpes/unicos [{:slug "water-gun" :poder 40}
                                  {:nome-exibicao "Revólver d'Água" :poder 50}
                                  {:slug "vine-whip" :poder 45}])]
    (is (= 2 (count resultado)))
    (is (= ["water-gun" "vine-whip"] (mapv :slug resultado)))))

(deftest ataque-do-tipo-exige-tipo-classe-e-poder
  (is (golpes/ataque-do-tipo? {:tipo :agua :classe :especial :poder 40} [:agua]))
  (is (not (golpes/ataque-do-tipo? {:tipo :agua :classe :status :poder 40} [:agua])))
  (is (not (golpes/ataque-do-tipo? {:tipo :agua :classe :especial :poder 0} [:agua])))
  (is (not (golpes/ataque-do-tipo? {:tipo :fogo :classe :especial :poder 40} [:agua]))))
