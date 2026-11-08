(ns pokemon.domain.damage
  "Cálculo de dano — fórmulas exatas de zapbot.pokemon.core.
  Sem STAB no dano (só preferência de moveset no legado)."
  (:require [clojure.string :as str]
            [pokemon.domain.rng :as rng]
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

(defn- emoji-golpe
  [golpe]
  (case (let [c (:classe golpe)] (if (keyword? c) c (keyword (or c "fisico"))))
    :fisico "💥"
    :especial "🔮"
    :status "📊"
    :transform "🧬"
    "❓"))

(defn- texto-efetividade
  "Emoji no FINAL, colado ao texto (sem espaço) — evita quebra solitária no WhatsApp."
  [multiplicador]
  (cond
    (zero? (double multiplicador)) "\nSem efeito!🚫"
    (> (double multiplicador) 1.0) "\nSuper efetivo!🔥"
    (< (double multiplicador) 1.0) "\nPouco efetivo!🛡️"
    :else ""))

(defn- texto-critico
  [critico?]
  (if critico? "\n🎯 Acerto crítico!" ""))

(defn- texto-impulso
  [impulso nome-habilidade]
  (if (and impulso (> (double impulso) 1.0) (not (str/blank? (str nome-habilidade))))
    (str "\n🌟 " (str/capitalize (str nome-habilidade)) " ativou e turbinou o ataque!")
    ""))

(defn mensagem-ataque
  "Texto legado de core/resolver-ataque (`causou N de dano` + sufixos)."
  [golpe atacante defensor resolved defendendo?]
  (let [nome-golpe (str (emoji-golpe golpe) " *"
                        (or (:nome-exibicao golpe) (:slug golpe) "golpe") "*")
        nome-atk (or (:nome atacante) (:species atacante) "Pokémon")
        nome-def (or (:nome defensor) (:species defensor) "adversário")
        dano (long (:dano resolved 0))
        acertos (long (:acertos resolved 1))
        sufixo (str (texto-efetividade (:multiplicador resolved 1))
                    (texto-critico (:critico? resolved))
                    (texto-impulso (:impulso resolved)
                                   (or (:habilidade atacante) (get atacante "habilidade"))))]
    (cond
      (:errou? resolved)
      (str nome-golpe " de *" nome-atk "* errou o alvo!")

      (:esquivou? resolved)
      (str "💨 *" nome-def "* esquivou completamente de " nome-golpe
           " de *" nome-atk "*! Nenhum dano.")

      (zero? dano)
      (str nome-golpe " de *" nome-atk "* não teve efeito em *"
           nome-def "*! 🚫")

      defendendo?
      (str nome-golpe " de *" nome-atk "*! *" nome-def
           "* estava se defendendo e sofreu só " dano " de dano." sufixo)

      :else
      (str nome-golpe " de *" nome-atk "* causou " dano
           " de dano em *" nome-def "*!" sufixo
           (when (> acertos 1) (str "\n🎯 Acertou " acertos " vezes!"))))))

(defn last-action-ataque
  "Payload de :last-action para turno ofensivo (present/UI).
  Campos canônicos do motor — o Node só exibe, não recalcula dano."
  [golpe atacante defensor resolved defendendo?
   & {:keys [move-index side hp-antes hp-depois hp-atk-antes hp-atk-depois
             autodesmaio?]}]
  (let [golpe (or golpe {})
        resolved (or resolved {})
        auto? (boolean autodesmaio?)
        msg0 (mensagem-ataque golpe atacante defensor resolved defendendo?)
        msg (cond-> msg0
              auto? (str "\n💀 Seu Pokémon desmaiou após o golpe!"))]
    (cond-> {:action :attack
             :dano (long (:dano resolved 0))
             :acertou? (boolean (:acertou? resolved))
             :critico? (boolean (:critico? resolved))
             :multiplicador (:multiplicador resolved 1)
             :errou? (boolean (:errou? resolved))
             :esquivou? (boolean (:esquivou? resolved))
             :acertos (long (:acertos resolved 1))
             :tipo-golpe (:tipo golpe)
             :golpe-nome (or (:nome-exibicao golpe) (:slug golpe))
             :autodesmaio? auto?
             :mensagem msg}
      (some? move-index) (assoc :move-index move-index)
      (some? side) (assoc :side side)
      (some? hp-antes) (assoc :hp-antes (long hp-antes))
      (some? hp-depois) (assoc :hp-depois (long hp-depois))
      (some? hp-atk-antes) (assoc :hp-atk-antes (long hp-atk-antes))
      (some? hp-atk-depois) (assoc :hp-atk-depois (long hp-atk-depois)))))
