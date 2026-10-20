(ns pokemon.domain.pvp
  "Regras de desafio PvP — espelha pokemon-service zapbot.pokemon.pvp.
  Sem I/O; expiração absoluta é só checagem de timestamp (timers Node adiados)."
  (:require [pokemon.domain.errors :as err]))

(def diferenca-maxima-niveis 3)
(def minutos-espera 5)
(def tempo-espera-ms (* minutos-espera 60 1000))
(def moedas-por-vitoria 10)

(defn bola-premio
  "Categoria de bola por nocaute — nível do desafiante (lado :x)."
  [pokemon]
  (let [nivel (long (or (:nivel pokemon) 1))]
    (cond (<= nivel 25) "pokebola"
          (<= nivel 60) "grande-bola"
          :else "ultra-bola")))

(defn faixa-niveis
  [pokemon]
  (let [nivel (long (or (:nivel pokemon) 1))]
    {:min (max 1 (- nivel diferenca-maxima-niveis))
     :max (+ nivel diferenca-maxima-niveis)}))

(defn aguardando?
  [battle]
  (and (= :pvp (:kind battle))
       (= :waiting (:phase battle))
       (some? (get-in battle [:jogadores :x]))
       (map? (get-in battle [:pokemons :x]))
       (nil? (get-in battle [:jogadores :o]))))

(defn expirado?
  [battle agora]
  (and (aguardando? battle)
       (number? (:desafio-expira-em battle))
       (<= (long (:desafio-expira-em battle)) (long agora))))

(defn faixa-desafio
  [battle]
  (or (:faixa-niveis battle) (faixa-niveis (get-in battle [:pokemons :x]))))

(defn compativel?
  [battle pokemon]
  (let [{:keys [min max]} (faixa-desafio battle)
        nivel (long (or (:nivel pokemon) 1))]
    (<= min nivel max)))

(defn xp-por-nocautes
  "Espelha treinador/xp-por-nocautes."
  [n]
  (+ 1 (* 2 (min 3 (long (or n 0))))))

(defn side-of-player
  "Retorna :x | :o | nil."
  [battle player-id]
  (cond
    (= player-id (get-in battle [:jogadores :x])) :x
    (= player-id (get-in battle [:jogadores :o])) :o
    :else nil))

(defn assert-healthy-active!
  [pokemon hp-atual]
  (when-not pokemon
    (throw (err/error :no_active_pokemon {})))
  (when-not (pos? (long (or hp-atual (:hp-atual pokemon) 0)))
    (throw (err/error :active_pokemon_fainted {})))
  pokemon)
