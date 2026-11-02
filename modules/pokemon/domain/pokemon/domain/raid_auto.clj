(ns pokemon.domain.raid-auto
  "Agenda de aparições automáticas — regras puras (Fase 3 / 3.1 / 12.7 / 12.7.1).
  Desligada por padrão; não altera combate/economia.
  Chefe: catálogo dinâmico (raid-boss), não lista fixa de ginásio.
  Lembretes T-30/15/5: outbox com PNG de ovo — sem revelar o chefe."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-boss :as raid-boss]))

(def default-interval-ms raid/intervalo-proxima)
(def default-enabled false)
;; Intervalo mínimo = duração do lobby: evita reaparição no mesmo tick pós-expire.
(def min-interval-ms raid/duracao-inscricoes)
(def max-interval-ms (* 48 60 60 1000))

;; Offsets de lembrete visual (Fase 12.7.1) — vinculados ao next-at da agenda.
(def reminder-offset-30-ms (* 30 60 1000))
(def reminder-offset-15-ms (* 15 60 1000))
(def reminder-offset-5-ms (* 5 60 1000))

;; Janela de atraso tolerada após fire-at. Tick típico 30s; deploy/restart no
;; meio da janela NÃO deve disparar lembretes já vencidos há minutos
;; (ex.: cutover em T-20 não envia o T-30 perdido).
(def reminder-late-grace-ms (* 2 60 1000))

(def reminder-specs
  "Specs estáveis: offset → kind outbox → minutos no PNG."
  [{:offset-ms reminder-offset-30-ms :minutes 30 :kind "raid-reminder-30"}
   {:offset-ms reminder-offset-15-ms :minutes 15 :kind "raid-reminder-15"}
   {:offset-ms reminder-offset-5-ms :minutes 5 :kind "raid-reminder-5"}])

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

(defn outbox-reminder-id
  "Idempotência por (chat, due/nextAt, offset). Reinício não recria se já existir."
  [chat-id due-at offset-ms]
  (str "raid-reminder:" chat-id ":" (long due-at) ":" (long offset-ms)))

(defn reminder-kind?
  [kind]
  (boolean (some #(= (str kind) (:kind %)) reminder-specs)))

(defn parse-reminder-id
  "Extrai {:chat-id :due-at :offset-ms} de um id estável, ou nil."
  [event-id]
  (when (string? event-id)
    (let [m (re-matches #"raid-reminder:(.+):(\d+):(\d+)" event-id)]
      (when m
        {:chat-id (nth m 1)
         :due-at (Long/parseLong (nth m 2))
         :offset-ms (Long/parseLong (nth m 3))}))))

(defn default-schedule
  [chat-id agora]
  {:chat-id (str chat-id)
   :enabled default-enabled
   :interval-ms default-interval-ms
   :next-at (long agora)
   :ginasio-index 0
   :revision 0
   :last-spawn-id nil
   :last-raid-id nil
   ;; Histórico curto em memória/opts (anti-repetição). Persistência opcional
   ;; via recent-bosses no tick — sem migration obrigatória.
   :recent-bosses []})

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
        interval (normalize-interval (:interval-ms m))
        recent (vec (take raid-boss/recent-window-default
                          (map str (or (:recent-bosses m) []))))]
    (-> m
        (assoc :chat-id (str chat-id)
               :enabled (boolean (:enabled m))
               :interval-ms interval
               :next-at (long (or (:next-at m) agora 0))
               :ginasio-index (long (or (:ginasio-index m) 0))
               :revision (long (or (:revision m) 0))
               :recent-bosses recent))))

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
   Garante next-at > due + duração do lobby, mesmo se interval == lobby.
   `lobby-ms` = duração real do lobby deste spawn (dinâmico por categoria)."
  ([schedule due-at]
   (advance-next-at schedule due-at raid/duracao-inscricoes))
  ([schedule due-at lobby-ms]
   (let [due (long due-at)
         interval (normalize-interval (:interval-ms schedule))
         lobby (long (or lobby-ms raid/duracao-inscricoes))
         after-lobby (inc (+ due lobby))]
     (max (+ due interval) after-lobby))))

(defn ocupa-slot-contra-spawn?
  "Qualquer raide em inscrição/combate bloqueia spawn (manual nunca é substituída).
   Expire do tick deve rodar antes e liberar/transformar o ponteiro."
  [active-raid]
  (boolean
   (and active-raid
        (contains? #{"inscricoes" "combate"} (str (:fase active-raid))))))

(defn format-opened
  "Anúncio oficial pós-eclosão. Revela chefe/categoria/lobby dinâmico."
  [raid]
  (let [chefe (:chefe raid)
        cat (str (or (:boss-categoria raid)
                     (:categoria raid)
                     (:raridade chefe)
                     (get chefe "raridade")
                     "raro"))
        lobby (or (:lobby raid) (raid/lobby-params-for-categoria cat))
        minutos (long (or (:lobby-minutos raid)
                          (:minutos lobby)
                          5))
        mn (long (or (:min-jogadores raid) (:min-jogadores lobby) 2))
        mx (long (or (:max-jogadores raid) (:max-jogadores lobby) 5))
        n (count (or (:ordem raid) []))
        nome (or (:nome chefe) (get chefe "nome") "?")
        hp (or (:hp chefe) (get chefe "hp") "?")
        label (or (:label lobby) cat)
        stars (or (:estrelas lobby) "⭐")]
    (str "⚡ *UMA RAID " (str/upper-case (str label)) " APARECEU!*\n"
         "\n"
         "👑 Chefe: *" nome "*\n"
         stars " Dificuldade: *" label "*\n"
         "❤️ HP base: " hp "\n"
         "🏛️ " (or (:nome-ginasio raid) (:ginasio raid) "Ginásio")
         " · liga " (or (:liga raid) "?") "\n"
         "👥 Participantes: " n "/" mx " (mín. " mn ")\n"
         "⏳ Lobby aberto por *" minutos " minutos*.\n"
         "\n"
         "*Comandos:*\n"
         "`!pk raide entrar` — Pokémon ativo\n"
         "`!pk raide entrar auto` — melhor elegível\n"
         "`!pk raide entrar <n>` — índice da coleção\n"
         "`!pk raide iniciar` — começar agora (se ≥ mín.)")))

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

(defn format-reminder
  "Legenda do lembrete — sem nome/tipo/raridade do chefe."
  [minutes]
  (case (long minutes)
    30 (str "🥚 *Uma raid está se aproximando!*\n"
            "O ovo já apareceu no ginásio.\n"
            "⏳ Faltam *30 minutos*.")
    15 (str "🥚 *A raid está quase começando!*\n"
            "Prepare seu time.\n"
            "⏳ Faltam *15 minutos*.")
    5 (str "🥚 *Últimos minutos antes da raid!*\n"
           "Se organize para entrar assim que abrir.\n"
           "⏳ Faltam *5 minutos*.")
    (str "🥚 *Uma raid está se aproximando!*\n"
         "⏳ Faltam *" minutes " minutos*.")))

(defn reminder-fire-at
  [next-at offset-ms]
  (- (long next-at) (long offset-ms)))

(defn reminder-window-due?
  "True quando o offset venceu há pouco, o spawn ainda não chegou, e o disparo
   cai dentro do intervalo da agenda.
   Descarte explícito se agora - fire-at > reminder-late-grace-ms (anti-retroativo
   em deploy/restart atrasado)."
  ([next-at offset-ms agora]
   (reminder-window-due? next-at offset-ms agora default-interval-ms))
  ([next-at offset-ms agora interval-ms]
   (let [due (long next-at)
         now (long agora)
         fire (reminder-fire-at due offset-ms)
         interval (normalize-interval interval-ms)
         window-start (- due interval)
         late (- now fire)]
     (and (< now due)
          (<= fire now)
          (<= late reminder-late-grace-ms)
          ;; estritamente dentro do intervalo: offset deve ser < interval-ms
          (> fire window-start)))))

(defn plan-reminders
  "Lembretes due para a agenda atual. Puro — store cria outbox se id ainda não existe.
   Não revela chefe; vincula event-id ao next-at (due) da raid agendada."
  [schedule agora]
  (let [schedule (normalize-schedule schedule (:chat-id schedule) agora)
        agora (long agora)
        due (long (:next-at schedule 0))
        interval (:interval-ms schedule)]
    (if-not (:enabled schedule)
      []
      (->> reminder-specs
           (filter #(reminder-window-due? due (:offset-ms %) agora interval))
           (mapv (fn [{:keys [offset-ms minutes kind]}]
                   {:acao :remind
                    :chat-id (:chat-id schedule)
                    :due-at due
                    :offset-ms offset-ms
                    :minutes minutes
                    :kind kind
                    :event-id (outbox-reminder-id (:chat-id schedule) due offset-ms)
                    :texto (format-reminder minutes)
                    :media-key (str "raid-egg-" minutes "min")}))))))

(defn remember-boss
  "Atualiza anel de espécies recentes (mais nova primeiro)."
  [recent species]
  (let [sp (str species)]
    (vec (take raid-boss/recent-window-default
               (cons sp (remove #{sp} (map str (or recent []))))))))

(defn plan-spawn
  "Decide o que o tick deve fazer. Puro — store aplica CAS.
   Retorna {:acao :spawn|:skip-busy|:skip-disabled|:noop ...}.
   Opts: :rng (fn [] double), :recent-species, :catalog, :category-weights."
  ([schedule active-raid agora]
   (plan-spawn schedule active-raid agora {}))
  ([schedule active-raid agora opts]
   (let [agora (long agora)
         schedule (normalize-schedule schedule (:chat-id schedule) agora)
         recent (vec (or (:recent-species opts) (:recent-bosses schedule) []))]
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
             boss (raid-boss/select-boss-for-ginasio
                   (:ginasio gym)
                   (cond-> {:recent-species recent}
                     (contains? opts :rng) (assoc :rng (:rng opts))
                     (contains? opts :catalog) (assoc :catalog (:catalog opts))
                     (contains? opts :category-weights)
                     (assoc :category-weights (:category-weights opts))))
             sid (spawn-id (:chat-id schedule) due)
             raid-id sid
             species (or (get-in boss [:chefe :species])
                         (get-in boss [:chefe "species"]))
             recent' (remember-boss recent species)
             cat (str (or (:categoria boss)
                          (get-in boss [:chefe :raridade])
                          "raro"))
             lobby (raid/lobby-params-for-categoria cat)]
         {:acao :spawn
          :schedule (assoc schedule :recent-bosses recent')
          :spawn-id sid
          :due-at due
          :next-at (advance-next-at schedule due (:duracao-ms lobby))
          :next-ginasio-index (:next-ginasio-index gym)
          :raid-id raid-id
          :ginasio (:ginasio boss)
          :nome-ginasio (:nome-ginasio boss)
          :nivel-ginasio (:nivel-ginasio boss)
          :liga (:liga boss)
          :chefe (:chefe boss)
          :boss-categoria cat
          :lobby lobby
          :duracao-ms (:duracao-ms lobby)
          :min-jogadores (:min-jogadores lobby)
          :max-jogadores (:max-jogadores lobby)
          :recent-bosses recent'})))))
