(ns pokemon.domain.gym
  "Ginásios — catálogo, motivação, recompensas e ocupação (puro).
  Espelha aventuras.cljs + ginasios.cljs + finalizar-ginasio do core."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.xp :as xp]))

(def motivacao-maxima 100)
(def motivacao-minima 20)
(def perda-motivacao-por-hora 5)
(def perda-motivacao-por-defesa 12)

(def permanencia-moedas-ms (* 6 60 60 1000))
(def permanencia-moedas 50)
(def xp-permanencia-slot-ms (* 30 60 1000))

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
  "Cópia de combate; não altera coleção do dono."
  [pokemon motivacao]
  (let [fator (fator-motivacao motivacao)
        scale (fn [v] (max 1 (Math/round (* (double (or v 0)) fator))))]
    (-> pokemon
        (update :hp scale)
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

(defn recompensa-permanencia
  [ocupacao agora]
  (let [desde (or (:desde ocupacao) (get ocupacao "desde") agora)]
    (if (and ocupacao (> (- (long agora) (long desde)) permanencia-moedas-ms))
      permanencia-moedas
      0)))

(defn duracao-ms
  [ocupacao agora]
  (let [desde (or (:desde ocupacao) (get ocupacao "desde") agora)]
    (max 0 (- (long agora) (long desde)))))

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
  "Moedas/PE/pedra conforme premio. pedra-roll 0–99 injetável."
  [premio pedra-roll]
  (when premio
    (let [primeira? (= premio :primeira)]
      {:moedas (if primeira? 100 25)
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
