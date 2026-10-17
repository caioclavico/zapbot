(ns pokemon.domain.damage
  "Cálculo de dano — fórmulas exatas de zapbot.pokemon.core.
  Sem STAB no dano (só preferência de moveset no legado)."
  (:require [pokemon.domain.rng :as rng]
            [pokemon.domain.types :as types]))

(def fator-dano 0.22)
(def chance-critico 10)

(def habilidades-impulso-hp-baixo
  {"blaze" "fire" "torrent" "water" "overgrow" "grass"})

(defn multiplicador-estagio
  [estagio]
  (let [s (long (or estagio 0))]
    (if (neg? s)
      (/ 2.0 (- 2 s))
      (/ (+ 2.0 s) 2.0))))

(defn stat-efetivo
  [pokemon estagios atributo]
  (* (double (or (get pokemon atributo) 0))
     (multiplicador-estagio (get estagios atributo 0))))

(defn velocidade-efetiva
  [pokemon estagios status]
  (* (stat-efetivo pokemon estagios :veloc)
     (if (= :paralisado status) 0.5 1.0)))

(defn chance-esquiva
  [velocidade-efetiva*]
  (min 50 (quot (long velocidade-efetiva*) 2)))

(defn impulso-habilidade
  "Blaze/Torrent/Overgrow: ×1.5 se tipo do golpe bate e HP ≤ 1/3."
  [atacante hp-atual tipo-golpe]
  (let [tipo-alvo (get habilidades-impulso-hp-baixo
                       (or (:habilidade atacante) (get atacante "habilidade")))]
    (if (and tipo-alvo
             (= tipo-alvo tipo-golpe)
             (<= (long hp-atual) (quot (long (or (:hp atacante) 1)) 3)))
      1.5
      1.0)))

(defn calcular-dano
  "max(1, round(0.22 * poder * atk/def) + U{0..10})."
  [poder-golpe poder-ataque poder-defesa rng]
  (let [variancia (rng/roll rng :damage-var 11)
        bruto (* fator-dano (double poder-golpe)
                 (/ (double poder-ataque) (double (max 1 poder-defesa))))]
    (max 1 (+ (Math/round bruto) variancia))))

(defn resolver-ataque
  "Espelha core/resolver-ataque. Retorna {:dano :acertou? :critico? :multiplicador :errou? :esquivou?}."
  [{:keys [golpe atacante defensor
           estagios-atacante estagios-defensor
           status-defensor
           defendendo? hp-atacante-atual
           rng]}]
  (let [fisico? (= :fisico (let [c (:classe golpe)]
                             (if (keyword? c) c (keyword c))))
        item-atacante (or (:item atacante) (get atacante "item"))
        atributo-atq (if fisico? :ataque :atq-esp)
        atributo-def (if fisico? :defesa :def-esp)
        poder-ataque (stat-efetivo atacante (or estagios-atacante {}) atributo-atq)
        poder-defesa (stat-efetivo defensor (or estagios-defensor {}) atributo-def)
        precisao (:precisao golpe)
        errou? (and precisao (>= (rng/roll rng :accuracy 100) (long precisao)))
        vel-def (velocidade-efetiva defensor (or estagios-defensor {}) status-defensor)
        esquivou? (and (not errou?) defendendo?
                       (< (rng/roll rng :dodge 100) (chance-esquiva vel-def)))
        multiplicador (types/multiplicador-vs-tipos
                       (:tipo golpe)
                       (or (:tipos defensor) [])
                       (or (:habilidade defensor) (get defensor "habilidade")))
        critico? (< (rng/roll rng :crit 100) chance-critico)
        impulso (impulso-habilidade atacante hp-atacante-atual (:tipo golpe))
        impulso-item (if (or (and fisico? (= item-atacante "banda"))
                             (and (not fisico?) (= item-atacante "oculos")))
                       1.15 1.0)
        acertos (if (:max-acertos golpe)
                  (+ (long (:min-acertos golpe 1))
                     (rng/roll rng :multi-hit
                               (inc (- (long (:max-acertos golpe))
                                       (long (:min-acertos golpe 1))))))
                  1)
        fator (* multiplicador impulso impulso-item (if critico? 1.5 1.0))
        base (calcular-dano (or (:poder golpe) 0) poder-ataque poder-defesa rng)
        dano-com-tudo (* acertos fator base)
        dano (cond
               errou? 0
               esquivou? 0
               (zero? dano-com-tudo) 0
               defendendo? (max 1 (Math/round (/ dano-com-tudo 2.0)))
               :else (max 1 (Math/round (double dano-com-tudo))))]
    {:dano (long dano)
     :acertou? (and (not errou?) (not esquivou?) (pos? dano))
     :critico? critico?
     :multiplicador multiplicador
     :errou? (boolean errou?)
     :esquivou? (boolean esquivou?)
     :acertos acertos
     :impulso impulso}))
