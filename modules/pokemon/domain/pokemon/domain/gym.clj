(ns pokemon.domain.gym
  "Ginásios — catálogo, motivação, recompensas e ocupação (puro).
  Espelha aventuras.cljs + ginasios.cljs + finalizar-ginasio do core."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.xp :as xp])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

(def motivacao-maxima 100)
(def motivacao-minima 20)
(def perda-motivacao-por-hora 5)
(def perda-motivacao-por-defesa 12)

;; --- Permanência / moedas (política canônica; crédito gated por flag) ---
(def ^:private segundo-ms 1000)
(def permanencia-minima-ms (* 30 60 segundo-ms))    ;; 30 min
(def permanencia-max-remunerada-ms (* 8 60 60 segundo-ms)) ;; 8 h
(def permanencia-teto-ocupacao 100)                 ;; moedas / ocupação
(def permanencia-teto-diario 100)                   ;; moedas / dia / treinador
(def permanencia-tz-padrao "America/Campo_Grande")

;; Legado (docs/compat): antiga política flat 6h→50 e slot 10min.
(def permanencia-moedas-ms (* 6 60 60 1000))
(def permanencia-moedas 50)
(def permanencia-slot-ms (* 10 60 1000))

(def xp-permanencia-slot-ms (* 30 60 1000))

(defn permanence-rewards-enabled?
  "Kill-switch econômico. Default false até homologação em produção."
  []
  (= "true"
     (some-> (System/getenv "POKEMON_GYM_PERMANENCE_REWARDS_ENABLED")
             str/lower-case
             str/trim)))

(defn challenges-enabled?
  "Gate de contenção para novos desafios 3×3. Default true.
  Contenção: POKEMON_GYM_CHALLENGES_ENABLED=false|0|off|no.
  Consultas, ranking, histórico, raids e temáticos não usam este gate."
  []
  (let [v (some-> (System/getenv "POKEMON_GYM_CHALLENGES_ENABLED")
                  str/lower-case
                  str/trim)]
    (not (#{"0" "false" "no" "off"} v))))

(defn permanence-timezone
  []
  (or (some-> (System/getenv "POKEMON_GYM_PERMANENCE_TZ") str/trim not-empty)
      permanencia-tz-padrao))

(defn day-key
  "Chave do teto diário no fuso da política (default America/Campo_Grande)."
  ([agora-ms] (day-key agora-ms (permanence-timezone)))
  ([agora-ms tz]
   (-> (Instant/ofEpochMilli (long agora-ms))
       (.atZone (ZoneId/of tz))
       (.toLocalDate)
       (.format DateTimeFormatter/ISO_LOCAL_DATE))))

(defn occupation-reward-id
  "Identidade estável da recompensa de uma ocupação (não reutiliza pokemon-id)."
  [occupation-key desde-ms]
  (str "gym-reward:" (str occupation-key) ":" (long desde-ms)))

(defn permanencia-bruta-moedas
  "Moedas brutas por ocupação (sem teto diário, sem flag).
   < 30 min → 0;
   senão min(100, floor(segundos * 100 / 28800)) — 8h = 100; além de 8h não sobe."
  [duration-ms]
  (let [ms (max 0 (long (or duration-ms 0)))]
    (if (< ms permanencia-minima-ms)
      0
      (let [seg (quot (min ms permanencia-max-remunerada-ms) segundo-ms)
            ;; floor(seg * 100 / 28800) com aritmética inteira
            bruta (quot (* seg permanencia-teto-ocupacao) 28800)]
        (min permanencia-teto-ocupacao (long bruta))))))

(defn aplicar-teto-diario
  "Aplica teto compartilhado entre ginásios do mesmo treinador no dia."
  [bruta ja-hoje]
  (let [bruta (max 0 (long (or bruta 0)))
        ja (max 0 (long (or ja-hoje 0)))
        restante (max 0 (- permanencia-teto-diario ja))]
    (min bruta restante)))

(defn plano-recompensa-permanencia
  "Plano determinístico de pagamento (puro).
   :payable é 0 quando a flag econômica está off."
  [{:keys [duration-ms already-today economy-enabled?]}]
  (let [bruta (permanencia-bruta-moedas duration-ms)
        capped (aplicar-teto-diario bruta already-today)
        enabled? (if (nil? economy-enabled?)
                   (permanence-rewards-enabled?)
                   (boolean economy-enabled?))
        eligible? (and enabled? (pos? capped))]
    {:gross bruta
     :capped capped
     :payable (if eligible? capped 0)
     :eligible? eligible?
     :below-minimum? (and (pos? (long (or duration-ms 0)))
                          (< (long duration-ms) permanencia-minima-ms))
     :daily-remaining (max 0 (- permanencia-teto-diario (max 0 (long (or already-today 0)))))
     :economy-enabled? enabled?}))

(def ginasios
  [{:id "pedra" :nome "🪨 Pedra" :lider "Brock" :nivel 10 :item "pedra-lua"
    :time ["geodude" "sandshrew" "onix"]}
   {:id "agua" :nome "💧 Água" :lider "Misty" :nivel 20 :item "pedra-agua"
    :time ["psyduck" "staryu" "starmie"]}
   {:id "eletrico" :nome "⚡ Elétrico" :lider "Lt. Surge" :nivel 30 :item "pedra-trovao"
    :time ["voltorb" "pikachu" "raichu"]}
   {:id "planta" :nome "🍃 Planta" :lider "Erika" :nivel 40 :item "pedra-folha"
    :time ["tangela" "weepinbell" "vileplume"]}
   {:id "fogo" :nome "🔥 Fogo" :lider "Blaine" :nivel 50 :item "pedra-fogo"
    :time ["ponyta" "magmar" "arcanine"]}])

(defn obter-ginasio
  [id]
  (some #(when (= id (:id %)) %) ginasios))

(defn desbloqueado?
  "Insígnias = conjunto/mapa de ids já conquistados."
  [insignias id]
  (let [ids (set (if (map? insignias) (keys insignias) insignias))
        anteriores (take-while #(not= id (:id %)) ginasios)]
    (and (obter-ginasio id)
         (every? #(contains? ids (:id %)) anteriores))))

(defn escalar-nivel
  "Escala stats base ao nível do ginásio (fator 1.03^(nivel-1))."
  [pokemon nivel]
  (let [nivel (long nivel)
        fator (Math/pow xp/fator-crescimento-por-nivel (dec nivel))
        scale (fn [v] (Math/round (* (double (or v 0)) fator)))]
    (-> pokemon
        (assoc :nivel nivel)
        (update :hp scale)
        (update :ataque scale)
        (update :defesa scale)
        (update :atq-esp scale)
        (update :def-esp scale)
        (update :veloc scale))))

(defn motivacoes
  "Queda por tempo sob demanda (sem cron)."
  [ocupacao agora]
  (let [quantidade (count (or (:time ocupacao) (get ocupacao "time") []))
        base (vec (or (seq (or (:motivacao ocupacao) (get ocupacao "motivacao")))
                      (repeat quantidade motivacao-maxima)))
        atualizado-em (or (:motivacao-em ocupacao)
                          (get ocupacao "motivacao-em")
                          (:desde ocupacao)
                          (get ocupacao "desde")
                          agora)
        horas (long (Math/floor (/ (max 0 (- (long agora) (long atualizado-em)))
                                   (* 60 60 1000))))
        perda (* horas perda-motivacao-por-hora)]
    (mapv #(max motivacao-minima (- (long (or % motivacao-maxima)) perda)) base)))

(defn fator-motivacao
  [motivacao]
  (+ 0.5 (* 0.5 (/ (double motivacao) motivacao-maxima))))

(defn aplicar-motivacao-pokemon
  "Cópia de combate; não altera coleção do dono.
   HP efetivo de defesa = max motivado; hp-atual da cópia começa cheio nesse max
   (evita barras vazias quando o snapshot tinha hp-atual 0/stale)."
  [pokemon motivacao]
  (let [fator (fator-motivacao motivacao)
        scale (fn [v] (max 1 (Math/round (* (double (or v 0)) fator))))
        hp' (scale (:hp pokemon))]
    (-> pokemon
        (assoc :hp hp' :hp-atual hp')
        (update :ataque scale)
        (update :defesa scale)
        (update :atq-esp scale)
        (update :def-esp scale)
        (update :veloc scale)
        (assoc :motivacao-ginasio motivacao))))

(defn time-defensor
  [ocupacao agora]
  (let [regs (or (:time ocupacao) (get ocupacao "time") [])
        mots (motivacoes ocupacao agora)]
    (mapv (fn [registro motivacao]
            (let [poke (if (:hp registro)
                         registro
                         ;; registro string-keyed legado
                         {:nome (get registro "nome")
                          :hp (get registro "hp")
                          :ataque (get registro "ataque")
                          :defesa (get registro "defesa")
                          :atq-esp (get registro "atq-esp")
                          :def-esp (get registro "def-esp")
                          :veloc (get registro "veloc")
                          :tipos (get registro "tipos")
                          :nivel (get registro "nivel" 1)
                          :habilidade (get registro "habilidade")
                          :golpes (get registro "golpes")
                          :shiny (get registro "shiny")
                          :id-pokemon (get registro "id-pokemon")})]
              (aplicar-motivacao-pokemon poke motivacao)))
          regs
          mots)))

(defn desgastar-motivacao
  [ocupacao agora]
  (let [novas (mapv #(max motivacao-minima (- % perda-motivacao-por-defesa))
                    (motivacoes ocupacao agora))]
    (assoc ocupacao :motivacao novas :motivacao-em agora
           "motivacao" novas "motivacao-em" agora)))

(defn recuperar-motivacao
  [ocupacao indice agora pontos]
  (let [atuais (motivacoes ocupacao agora)]
    (when-not (and (integer? indice) (<= 0 indice (dec (count atuais))))
      (throw (err/error :invalid_motivation_index {:idx indice})))
    (when (>= (get atuais indice) motivacao-maxima)
      (throw (err/error :motivation_full {:idx indice})))
    (let [antes (get atuais indice)
          depois (min motivacao-maxima (+ antes (long pontos)))
          novas (assoc atuais indice depois)]
      {:ocupacao (assoc ocupacao :motivacao novas :motivacao-em agora
                        "motivacao" novas "motivacao-em" agora)
       :antes antes :depois depois})))

(defn duracao-ms
  [ocupacao agora]
  (let [desde (or (:desde ocupacao) (get ocupacao "desde") agora)]
    (max 0 (- (long agora) (long desde)))))

(defn recompensa-permanencia
  "Bruta pela duração (fórmula 8h→100). Sem teto diário.
   Pagamento real e teto ficam em `plano-recompensa-permanencia` + gym-store."
  [ocupacao agora]
  (if ocupacao
    (permanencia-bruta-moedas (duracao-ms ocupacao agora))
    0))

(defn recompensa-permanencia-pagamento
  "Valor a creditar no encerramento (0 se flag off ou abaixo do mínimo)."
  [ocupacao agora already-today]
  (:payable (plano-recompensa-permanencia
             {:duration-ms (duracao-ms ocupacao agora)
              :already-today already-today
              :economy-enabled? (permanence-rewards-enabled?)})))

(defn xp-permanencia
  [ocupacao agora]
  (let [ms (duracao-ms ocupacao agora)]
    (if (pos? ms)
      (min 24 (max 1 (long (Math/floor (/ ms xp-permanencia-slot-ms)))))
      0)))

(defn registro-apos-derrota
  [registro]
  (-> registro
      (assoc :hp-atual 0 :status nil)
      (assoc "hp-atual" 0 "status" nil)))

(defn xp-ginasio-participante
  [venceu? premio nocautes]
  (+ (cond
       (not venceu?) 3
       (= premio :primeira) 7
       (= premio :revanche) 4
       :else 2)
     (max 0 (long (or nocautes 0)))))

(defn registrar-insignia
  "Retorna :primeira | :revanche | nil (já resgatou hoje)."
  [insignias-map gym-id dia]
  (let [anterior (get insignias-map gym-id)]
    (when (not= anterior dia)
      (if anterior :revanche :primeira))))

(defn recompensa-vitoria
  "PE/pedra/insígnia conforme premio — **sem moedas** (economia só por permanência).
   pedra-roll 0–99 injetável."
  [premio pedra-roll]
  (when premio
    (let [primeira? (= premio :primeira)]
      {:moedas 0
       :pe (if primeira? 5 2)
       :vitoria-treinador? true
       :pedra? (or primeira? (< (long (or pedra-roll 100)) 25))})))

(defn pe-derrota [] 1)

(defn validar-time-ocupacao!
  [indices registros]
  (when-not (and (= 3 (count indices))
                 (= 3 (count (set indices)))
                 (= 3 (count registros))
                 (every? some? registros))
    (throw (err/error :invalid_gym_team
                      {:indices indices :count (count registros)})))
  true)

(defn nova-ocupacao
  [{:keys [player-id save-id nome time agora]}]
  {:pid player-id
   :save-id save-id
   :nome nome
   :time (vec time)
   :desde agora
   :motivacao (vec (repeat 3 motivacao-maxima))
   :motivacao-em agora})

(defn assert-can-occupy!
  "CAS lógico: anterior ainda é o líder; desafiante ≠ líder atual."
  [atual esperado-anterior desafiante-pid]
  (let [atual-pid (or (:pid atual) (get atual "pid"))
        exp-pid (or (:pid esperado-anterior) (get esperado-anterior "pid"))]
    (when-not (= atual-pid exp-pid)
      (throw (err/error :gym_cas_conflict
                        {:expected exp-pid :actual atual-pid})))
    (when (= desafiante-pid atual-pid)
      (throw (err/error :cannot_challenge_own_gym {:pid desafiante-pid})))
    true))
