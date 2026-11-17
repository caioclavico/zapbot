(ns pokemon.domain.battle-idle
  "Timeout de inatividade de batalhas — puro, sem I/O.

  Contador absoluto: last-activity-at + idle-timeout → expires-at.
  Só ações válidas aceitas renovam (caller decide o que é válido).
  Expiração não concede recompensas nem aplica derrota/rank."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]))

(def idle-timeout-padrao-segundos 300)

(defn environment
  "Indireção testável para env do processo JVM."
  [name]
  (System/getenv name))

(defn idle-timeout-segundos
  "POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS. Zero só em staging; produção
   preserva 300s se inválido/inseguro."
  []
  (let [raw (some-> (environment "POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS") str)
        seconds (try (Long/parseLong raw) (catch Exception _ idle-timeout-padrao-segundos))
        staging? (= "staging" (some-> (environment "APP_ENV") str/lower-case str/trim))]
    (cond
      (neg? seconds) idle-timeout-padrao-segundos
      (and (zero? seconds) (not staging?)) idle-timeout-padrao-segundos
      :else seconds)))

(defn idle-timeout-ms
  []
  (* 1000 (idle-timeout-segundos)))

(defn touch
  "Nova janela a partir de `agora` (epoch ms)."
  ([agora] (touch agora (idle-timeout-ms)))
  ([agora timeout-ms]
   (let [now (long agora)
         timeout (long timeout-ms)]
     {:last-activity-at now
      :idle-expires-at (+ now timeout)})))

(defn expired?
  "True se expires-at ≤ agora. Nil/ausente → não expirado (legado sem attr)."
  [expires-at agora]
  (when (some? expires-at)
    (<= (long expires-at) (long agora))))

(defn remaining-ms
  [expires-at agora]
  (max 0 (- (long (or expires-at 0)) (long agora))))

(defn assert-not-expired!
  [expires-at agora]
  (when (expired? expires-at agora)
    (throw (err/error :battle_idle_expired
                      {:expires-at expires-at :agora agora}))))

(defn expire-battle
  "Transição pura → finished / :idle-timeout. Sem prêmios.
   Inclui aguardando-captura e desafio waiting como ainda abertos."
  [battle]
  (when-not battle
    (throw (err/error :battle_not_found {})))
  (let [already-idle? (or (= :idle-timeout (:outcome battle))
                          (true? (:idle-expired? battle)))
        still-open? (or (:active? battle)
                        (:aguardando-captura? battle)
                        (= :waiting (:phase battle)))]
    (cond
      already-idle?
      (assoc battle
             :phase :finished
             :active? false
             :aguardando-captura? false
             :outcome :idle-timeout
             :idle-expired? true
             :desafio-expira-em nil)

      still-open?
      (assoc battle
             :phase :finished
             :outcome :idle-timeout
             :active? false
             :aguardando-captura? false
             :idle-expired? true
             :desafio-expira-em nil)

      :else
      ;; Já encerrada por vitória/derrota/leave — só marca idle-expired.
      (assoc battle
             :phase :finished
             :active? false
             :aguardando-captura? false
             :outcome (or (:outcome battle) :idle-timeout)
             :idle-expired? true
             :desafio-expira-em nil))))

(defn expire-raid
  "Encerra combate de raid por inatividade (sem vitória)."
  [raid]
  (when-not raid
    (throw (err/error :raid_not_found {})))
  (if (contains? #{:vitoria :derrota :expirada :idle-timeout} (keyword (:fase raid)))
    (assoc raid :fase :idle-timeout :idle-expired? true)
    (assoc raid
           :fase :idle-timeout
           :idle-expired? true
           :ativo? false)))

(defn notification-payload
  "Texto/contexto anonimizável para outbox WhatsApp."
  [{:keys [battle-id kind chat-id player-ids timeout-seconds wild-name]}]
  {:kind "battle-idle-timeout"
   :battle-id battle-id
   :battle-kind (name (or kind "wild"))
   :chat-id chat-id
   :player-ids (vec player-ids)
   :timeout-seconds (long (or timeout-seconds (idle-timeout-segundos)))
   :wild-name wild-name
   :message-key :battle-idle-timeout})

(defn receipt-operation-id
  "ID estável — segunda expiração vira duplicate via receipt único."
  [battle-id]
  (str "battle-idle-expire|" (str battle-id)))

(defn notify-id
  [battle-id player-id]
  (str "battle-idle-notify|" battle-id "|" player-id))
