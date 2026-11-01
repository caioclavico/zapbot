(ns pokemon.domain.raid-auto
  "Agenda de aparições automáticas — regras puras (Fase 3 / 3.1).
  Desligada por padrão; não altera combate/economia."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.raid :as raid]))

(def default-interval-ms raid/intervalo-proxima)
(def default-enabled false)
;; Intervalo mínimo = duração do lobby: evita reaparição no mesmo tick pós-expire.
(def min-interval-ms raid/duracao-inscricoes)
(def max-interval-ms (* 48 60 60 1000))

(defn spawn-id
  "Chave idempotente por chat + due-at lógico (anti-duplicata pós-restart/retry)."
  [chat-id due-at]
  (str "raid-auto:" chat-id ":" (long due-at)))

(defn outbox-opened-id
  [raid-id]
  (str "raid-opened:" raid-id))

(defn outbox-started-id
  [raid-id lobby-expira]
  (str "raid-started:" raid-id ":" (long lobby-expira)))

(defn outbox-cancelled-id
  [raid-id lobby-expira]
  (str "raid-cancelled:" raid-id ":" (long lobby-expira)))

(defn default-schedule
  [chat-id agora]
  {:chat-id (str chat-id)
   :enabled default-enabled
   :interval-ms default-interval-ms
   :next-at (long agora)
   :ginasio-index 0
   :revision 0
   :last-spawn-id nil
   :last-raid-id nil})

(defn normalize-interval
  [interval-ms]
  (let [interval (long (or interval-ms default-interval-ms))]
    (when-not (<= min-interval-ms interval max-interval-ms)
      (throw (err/error :raid_auto_interval_invalid
                        {:interval-ms interval
                         :min min-interval-ms
                         :max max-interval-ms})))
    interval))

(defn normalize-schedule
  [raw chat-id agora]
  (let [base (default-schedule chat-id (or agora 0))
        m (merge base (or raw {}))
        interval (normalize-interval (:interval-ms m))]
    (-> m
        (assoc :chat-id (str chat-id)
               :enabled (boolean (:enabled m))
               :interval-ms interval
               :next-at (long (or (:next-at m) agora 0))
               :ginasio-index (long (or (:ginasio-index m) 0))
               :revision (long (or (:revision m) 0))))))

(defn due?
  [schedule agora]
  (and (boolean (:enabled schedule))
       (<= (long (:next-at schedule 0)) (long agora))))

(defn pick-ginasio
  "Seleciona ginásio A6 por cursor round-robin."
  [ginasio-index]
  (let [catalog gym/ginasios
        n (count catalog)
        idx (mod (long (or ginasio-index 0)) n)
        g (nth catalog idx)]
    {:ginasio (:id g)
     :nome-ginasio (:nome g)
     :nivel-ginasio (long (:nivel g))
     :ginasio-index idx
     :next-ginasio-index (mod (inc idx) n)}))

(defn advance-next-at
  "Próximo due determinístico a partir do vencimento lógico (não do relógio de parede).
   Garante next-at > due + duração do lobby, mesmo se interval == lobby."
  [schedule due-at]
  (let [due (long due-at)
        interval (normalize-interval (:interval-ms schedule))
        after-lobby (inc (+ due raid/duracao-inscricoes))]
    (max (+ due interval) after-lobby)))

(defn ocupa-slot-contra-spawn?
  "Qualquer raide em inscrição/combate bloqueia spawn (manual nunca é substituída).
   Expire do tick deve rodar antes e liberar/transformar o ponteiro."
  [active-raid]
  (boolean
   (and active-raid
        (contains? #{"inscricoes" "combate"} (str (:fase active-raid))))))

(defn format-opened
  [raid]
  (let [chefe (:chefe raid)]
    (str "📣 *Raide automática* em " (or (:nome-ginasio raid) (:ginasio raid)) "!\n"
         "Chefe: *" (or (:nome chefe) "?") "* · liga " (or (:liga raid) "?") "\n"
         "Inscrições por 2 minutos · máx. 5 treinadores.\n"
         "Entre: !pk raide entrar auto")))

(defn format-started
  [raid]
  (str "⚔️ Raide iniciada automaticamente em "
       (or (:nome-ginasio raid) (:ginasio raid)) "!\n"
       "Chefe HP: " (:hp-chefe raid) "/" (:hp-max raid)))

(defn format-cancelled
  [raid]
  (str "🚫 Raide automática cancelada em "
       (or (:nome-ginasio raid) (:ginasio raid))
       " — poucos inscritos."))

(defn plan-spawn
  "Decide o que o tick deve fazer. Puro — store aplica CAS.
   Retorna {:acao :spawn|:skip-busy|:skip-disabled|:noop ...}."
  [schedule active-raid agora]
  (let [agora (long agora)
        schedule (normalize-schedule schedule (:chat-id schedule) agora)]
    (cond
      (not (:enabled schedule))
      {:acao :skip-disabled :schedule schedule}

      (not (due? schedule agora))
      {:acao :noop :schedule schedule}

      (ocupa-slot-contra-spawn? active-raid)
      (let [due (long (:next-at schedule))]
        {:acao :skip-busy
         :schedule schedule
         :spawn-id (spawn-id (:chat-id schedule) due)
         :due-at due
         :next-at (advance-next-at schedule due)
         :active-raid-id (:raid-id active-raid)
         :active-origem (:origem active-raid)
         :active-fase (:fase active-raid)})

      :else
      (let [due (long (:next-at schedule))
            gym (pick-ginasio (:ginasio-index schedule))
            boss (raid/boss-do-ginasio (:ginasio gym))
            sid (spawn-id (:chat-id schedule) due)
            raid-id sid]
        {:acao :spawn
         :schedule schedule
         :spawn-id sid
         :due-at due
         :next-at (advance-next-at schedule due)
         :next-ginasio-index (:next-ginasio-index gym)
         :raid-id raid-id
         :ginasio (:ginasio boss)
         :nome-ginasio (:nome-ginasio boss)
         :nivel-ginasio (:nivel-ginasio boss)
         :liga (:liga boss)
         :chefe (:chefe boss)}))))
