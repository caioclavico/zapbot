(ns pokemon.domain.pvp
  "Regras de desafio PvP — espelha pokemon-service zapbot.pokemon.pvp.
  Sem I/O; expiração absoluta é só checagem de timestamp (timers Node adiados)."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]))

(def diferenca-maxima-niveis 3)
(def minutos-espera 5)
(def tempo-espera-ms (* minutos-espera 60 1000))
(def moedas-por-vitoria 10)
(def max-trocas 3)
(def intervalo-trocas-ms 60000)
;; Até 3 Pokémon distintos por treinador por partida (substituição após nocaute).
(def max-participantes 3)

(defn as-side
  "Normaliza marcador de lado para :x|:o (aceita keyword, string ou nil)."
  [v]
  (cond
    (#{:x :o} v) v
    (string? v) (case (str/lower-case (str/trim v))
                  ("x" ":x") :x
                  ("o" ":o") :o
                  nil)
    :else nil))

(defn jogador
  "Player-id no lado :x|:o — tolera chaves keyword ou string no mapa :jogadores."
  [battle side]
  (when-let [s (as-side side)]
    (let [jog (or (:jogadores battle) {})]
      (or (get jog s) (get jog (name s))))))

(defn side-of-player
  "Retorna :x | :o | nil."
  [battle player-id]
  (cond
    (and player-id (= player-id (jogador battle :x))) :x
    (and player-id (= player-id (jogador battle :o))) :o
    :else nil))

(defn normalize-battle-sides
  "Garante :vez keyword e :jogadores com chaves :x/:o após round-trip EDN/JSON."
  [battle]
  (when battle
    (cond-> battle
      (contains? battle :vez)
      (assoc :vez (as-side (:vez battle)))
      (map? (:jogadores battle))
      (assoc :jogadores
             (let [j (:jogadores battle)]
               (cond-> {}
                 (or (get j :x) (get j "x")) (assoc :x (or (get j :x) (get j "x")))
                 (or (get j :o) (get j "o")) (assoc :o (or (get j :o) (get j "o")))))))))

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
       (some? (jogador battle :x))
       (map? (get-in battle [:pokemons :x]))
       (nil? (jogador battle :o))))

(defn expirado?
  [battle agora]
  (and (aguardando? battle)
       (number? (:desafio-expira-em battle))
       (<= (long (:desafio-expira-em battle)) (long agora))))

(defn faixa-desafio
  [battle]
  (or (:faixa-niveis battle) (faixa-niveis (get-in battle [:pokemons :x]))))

(defn dentro-da-faixa?
  [{:keys [min max]} pokemon]
  (<= min (long (or (:nivel pokemon) 1)) max))

(defn compativel?
  [battle pokemon]
  (dentro-da-faixa? (faixa-desafio battle) pokemon))

(defn usados
  [battle side]
  (set (or (get-in battle [:usados (as-side side)]) #{})))

(defn eliminados
  [battle side]
  (set (or (get-in battle [:eliminados (as-side side)]) #{})))

(defn aguardando-substituicao-de
  "Lado que precisa trocar obrigatoriamente (:x|:o|:both|nil)."
  [battle]
  (let [v (:aguardando-substituicao battle)]
    (cond
      (#{:x :o :both} v) v
      (= "both" (str v)) :both
      :else (as-side v))))

(defn aguardando-substituicao?
  [battle side]
  (let [w (aguardando-substituicao-de battle)
        s (as-side side)]
    (or (= w :both) (and s (= w s)))))

(defn pode-adicionar-participante?
  "Novo índice só se ainda não usou 3 distintos (reentrar usado/vivo ok)."
  [battle side collection-index]
  (let [idx (long collection-index)
        u (usados battle side)]
    (or (contains? u idx)
        (< (count u) max-participantes))))

(defn pokemon-elegivel-substituicao?
  "HP>0, não eliminado, faixa ±3 da âncora, dentro do limite de 3."
  [battle side pokemon collection-index]
  (let [idx (long collection-index)
        hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))]
    (and (some? pokemon)
         (pos? hp)
         (not (contains? (eliminados battle side) idx))
         (not= idx (long (or (get-in battle [:indices-ativos (as-side side)]) -1)))
         (dentro-da-faixa? (faixa-desafio battle) pokemon)
         (pode-adicionar-participante? battle side idx))))

(defn tem-substituicao-elegivel?
  "Algum mon da coleção pode entrar agora no lado."
  [battle side colecao]
  (boolean
   (some (fn [[idx poke]]
           (pokemon-elegivel-substituicao? battle side poke idx))
         (map-indexed vector (or colecao [])))))

(defn estado-troca
  [battle side]
  (let [usadas (get-in battle [:trocas side :usadas] 0)
        forced? (aguardando-substituicao? battle side)]
    {:restantes (- max-trocas usadas)
     :disponivel-em (get-in battle [:trocas side :disponivel-em] 0)
     :faixa (faixa-desafio battle)
     :obrigatoria? forced?
     :participantes (count (usados battle side))
     :max-participantes max-participantes
     :eliminados (vec (sort (eliminados battle side)))}))

(defn xp-por-nocautes
  "Espelha treinador/xp-por-nocautes."
  [n]
  (+ 1 (* 2 (min 3 (long (or n 0))))))

(defn assert-healthy-active!
  [pokemon hp-atual]
  (when-not pokemon
    (throw (err/error :no_active_pokemon {})))
  (when-not (pos? (long (or hp-atual (:hp-atual pokemon) 0)))
    (throw (err/error :active_pokemon_fainted {})))
  pokemon)
