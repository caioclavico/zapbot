(ns zapbot.pokemon.aventuras-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [zapbot.pokemon.aventuras :as aventuras]))

(deftest desbloqueio-de-ginasios-respeita-a-ordem
  (is (aventuras/desbloqueado? [] "pedra"))
  (is (not (aventuras/desbloqueado? [] "agua")))
  (is (aventuras/desbloqueado? ["pedra"] "agua"))
  (is (not (aventuras/desbloqueado? ["pedra"] "fogo")))
  (is (not (aventuras/desbloqueado? ["pedra"] "inexistente"))))

(deftest pedra-solar-contem-as-evolucoes-suportadas
  (let [evolucoes (get-in aventuras/pedras ["pedra-solar" :evolucoes])]
    (is (= "bellossom" (get evolucoes "gloom")))
    (is (= "sunflora" (get evolucoes "sunkern")))
    (is (= "whimsicott" (get evolucoes "cottonee")))
    (is (= "lilligant" (get evolucoes "petilil")))
    (is (= "heliolisk" (get evolucoes "helioptile")))))

(deftest validacao-de-troca-detecta-expiracao-e-alteracoes
  (let [a {"nome" "Kadabra"}
        b {"nome" "Machoke"}
        proposta {:expira 2000 :registro-a a :registro-b b}]
    (is (aventuras/troca-valida? proposta 1999 a b))
    (is (not (aventuras/troca-valida? proposta 2000 a b)))
    (is (not (aventuras/troca-valida? proposta 1999 (assoc a "nivel" 2) b)))
    (is (not (aventuras/troca-valida? nil 1999 a b)))))

(deftest eventos-possuem-periodos-deterministicos
  (let [primeiro (aventuras/evento-atual 0)
        segundo (aventuras/evento-atual aventuras/duracao-evento-ms)]
    (is (= "Festival das Águas" (:nome primeiro)))
    (is (= aventuras/duracao-evento-ms (:fim primeiro)))
    (is (= "Faíscas no Campo" (:nome segundo)))
    (is (= 50 (:chance segundo)))))
