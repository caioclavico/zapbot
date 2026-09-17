(ns zapbot.pokemon.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [zapbot.pokemon.core :as core]))

(def pikachu
  {:nome "Pikachu" :tipos ["electric"] :habilidade "static"
   :hp 80 :ataque 70 :defesa 50 :atq-esp 75 :def-esp 60 :veloc 100})

(def geodude
  {:nome "Geodude" :tipos ["rock" "ground"] :habilidade "rock-head"
   :hp 100 :ataque 80 :defesa 100 :atq-esp 30 :def-esp 40 :veloc 20})

(defn jogo-base [x o]
  {:pokemons {:x x :o o}
   :hp {:x (:hp x) :o (:hp o)}
   :status {:x nil :o nil}
   :estagios {:x {} :o {}}
   :defendendo {:x false :o false}})

(deftest efetividade-de-tipos-considera-duplo-tipo-e-habilidade
  (testing "vantagem e resistência"
    (is (= 2 (core/multiplicador-vs-tipos "water" ["fire"] nil)))
    (is (= 0.5 (core/multiplicador-vs-tipos "fire" ["water"] nil))))
  (testing "efeitos de dois tipos são multiplicados"
    (is (= 4 (core/multiplicador-vs-tipos "water" ["rock" "ground"] nil)))
    (is (= 0.25 (core/multiplicador-vs-tipos "fire" ["water" "dragon"] nil))))
  (testing "imunidades de tipo e Levitate anulam o dano"
    (is (zero? (core/multiplicador-vs-tipos "electric" ["ground"] nil)))
    (is (zero? (core/multiplicador-vs-tipos "ground" ["electric"] "levitate")))))

(deftest estagios-alteram-stats-e-respeitam-limites
  (is (= 1.5 (core/multiplicador-estagio 1)))
  (is (= (/ 2 3) (core/multiplicador-estagio -1)))
  (is (= 4 (core/multiplicador-estagio 6)))
  (is (= 0.25 (core/multiplicador-estagio -6)))
  (let [jogo (jogo-base pikachu geodude)
        aumentado (core/aplicar-alteracoes jogo :x [{:atributo :ataque :estagios 10}])
        reduzido (core/aplicar-alteracoes aumentado :x [{:atributo :ataque :estagios -20}])]
    (is (= 6 (get-in aumentado [:estagios :x :ataque])))
    (is (= -6 (get-in reduzido [:estagios :x :ataque])))))

(deftest dano-de-status-e-cura-de-restos
  (is (= 10 (core/dano-por-status :queimado 160)))
  (is (= 20 (core/dano-por-status :envenenado 160)))
  (is (= 1 (core/dano-por-status :queimado 5)))
  (is (zero? (core/dano-por-status :paralisado 160)))
  (let [portador (assoc pikachu :hp 160 :item "restos")
        jogo (assoc-in (jogo-base portador geodude) [:hp :x] 100)
        [curado cura] (core/aplicar-restos jogo :x)]
    (is (= 10 cura))
    (is (= 110 (get-in curado [:hp :x]))))
  (let [portador (assoc pikachu :hp 160 :item "restos")
        jogo (-> (jogo-base portador geodude)
                 (assoc-in [:hp :x] 100)
                 (assoc-in [:status :x] :envenenado))
        [final texto] (core/aplicar-fim-de-turno jogo :x)]
    (is (= 90 (get-in final [:hp :x])))
    (is (str/includes? texto "Restos recuperou 10 HP"))
    (is (str/includes? texto "sofreu 20 de dano"))))

(deftest intimidacao-reduz-o-ataque-correto
  (let [intimidador (assoc pikachu :habilidade "intimidate")
        [resultado mensagem] (core/aplicar-intimidacao (jogo-base intimidador geodude))]
    (is (= 53 (get-in resultado [:pokemons :o :ataque])))
    (is (= (:ataque intimidador) (get-in resultado [:pokemons :x :ataque])))
    (is (str/includes? mensagem "intimidou")))
  (let [x (assoc pikachu :habilidade "intimidate")
        o (assoc geodude :habilidade "intimidate")
        [resultado _] (core/aplicar-intimidacao (jogo-base x o))]
    (is (= 47 (get-in resultado [:pokemons :x :ataque])))
    (is (= 53 (get-in resultado [:pokemons :o :ataque])))))

(deftest habilidades-de-hp-baixo-so-impulsionam-o-tipo-correto
  (let [charizard (assoc pikachu :hp 99 :habilidade "blaze")]
    (is (= 1.5 (core/impulso-habilidade charizard 33 "fire")))
    (is (= 1 (core/impulso-habilidade charizard 34 "fire")))
    (is (= 1 (core/impulso-habilidade charizard 20 "flying")))))

(deftest imunidade-produz-ataque-sem-dano
  (let [jogo (jogo-base pikachu geodude)
        golpe {:nome-exibicao "Choque do Trovão" :tipo "electric"
               :classe :especial :poder 40}
        resultado (core/resolver-ataque jogo golpe :x :o false (:hp pikachu))]
    (is (zero? (:dano resultado)))
    (is (false? (:acertou? resultado)))
    (is (str/includes? (:mensagem resultado) "não teve efeito"))))

(deftest bonus-das-bolas-respeita-o-limite
  (is (= 70 (core/chance-com-bola 70 "pokebola")))
  (is (= 88 (core/chance-com-bola 70 "grande-bola")))
  (is (= 95 (core/chance-com-bola 70 "ultra-bola"))))

(deftest barra-de-hp-nao-exibe-valor-negativo
  (is (= "[█████░░░░░] 50/100" (core/barra-hp 50 100)))
  (is (= "[░░░░░░░░░░] 0/100" (core/barra-hp -10 100))))

(deftest atalhos-pokemon-sao-expandidos
  (testing "atalhos de batalha"
    (is (= "atacar" (core/expandir-atalho "atk")))
    (is (= "defender" (core/expandir-atalho "def")))
    (is (= "curar" (core/expandir-atalho "cur")))
    (is (= "pocao" (core/expandir-atalho "pot"))))
  (testing "atalhos de navegação e gerenciamento"
    (is (= "ginasio" (core/expandir-atalho "gin")))
    (is (= "cacar" (core/expandir-atalho "cac")))
    (is (= "time" (core/expandir-atalho "tm")))
    (is (= "pokedex" (core/expandir-atalho "dex"))))
  (testing "comandos completos permanecem inalterados"
    (is (= "atacar" (core/expandir-atalho "atacar")))
    (is (= "raid" (core/expandir-atalho "raid")))))

(deftest identifica-ataques-que-devem-levar-foto-do-ginasio
  (let [ginasio {:ginasio {:id "pedra"}}
        pvp {:jogadores {:x "a" :o "b"}}]
    (is (true? (core/ataque-ginasio? ginasio "atk 1")))
    (is (true? (core/ataque-ginasio? ginasio "atacar 2")))
    (is (false? (core/ataque-ginasio? ginasio "def 1")))
    (is (false? (core/ataque-ginasio? pvp "atk 1")))))

(deftest identifica-imagens-da-cacada-e-fugas
  (let [caca {:pokemons {:x pikachu :o geodude}}]
    (is (true? (core/ataque-cacada? caca "atk 1")))
    (is (true? (core/ataque-cacada? caca "atacar 2")))
    (is (false? (core/ataque-cacada? caca "def")))
    (is (false? (core/ataque-cacada? nil "atk 1"))))
  (is (true? (core/fuga-selvagem-na-resposta? "💨 Pikachu fugiu durante a batalha")))
  (is (true? (core/fuga-selvagem-na-resposta? "Geodude escapou e sua sequência acabou")))
  (is (false? (core/fuga-selvagem-na-resposta? "O selvagem continua aqui"))))

(deftest arena-da-cacada-tem-grama-e-fumaca-apenas-na-fuga
  (let [normal (core/svg-arena-cacada false)
        fuga (core/svg-arena-cacada true)]
    (is (str/includes? normal "id='grama'"))
    (is (not (str/includes? normal "fill-opacity='.92'")))
    (is (str/includes? fuga "id='grama'"))
    (is (str/includes? fuga "fill-opacity='.92'"))))
