(ns pokemon.domain.raid-auto
  "Agenda de aparições automáticas — regras puras (Fase 3 / 3.1 / 12.7 / 12.7.1 / 12.7.2).
  Desligada por padrão; não altera combate/economia.
  Chefe: catálogo dinâmico (raid-boss), não lista fixa de ginásio.
  Cadência 12.7.2: intervalo-base (~1h) + cooldown por categoria; lembretes
  T-30/15/5 (ovo) só para eventos especiais (lendário/mítico)."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-boss :as raid-boss])
  (:import [java.time Instant ZoneId]))

;; Cadência base entre slots exclusivos (1 evento por grupo por vez).
;; Cooldowns por categoria vivem em category-interval-ms-default.
(def default-interval-ms (* 60 60 1000))
(def default-enabled false)
;; Intervalo mínimo = duração do lobby: evita reaparição no mesmo tick pós-expire.
(def min-interval-ms raid/duracao-inscricoes)
(def max-interval-ms (* 48 60 60 1000))

(def category-interval-ms-default
  "Frequência desejada por categoria (cooldown desde o último spawn da cat)."
  {"raro" (* 1 60 60 1000)
   "epico" (* 3 60 60 1000)
   "lendario" (* 12 60 60 1000)
   "mitico" (* 24 60 60 1000)})

(def category-order
  "Ordem estável para desempate determinístico."
  ["mitico" "lendario" "epico" "raro"])

(def special-categories #{"lendario" "mitico"})

;; Dia BRT: prioriza raro/épico. Noite: prioriza especiais quando due.
(def day-hour-start-brt 8)
(def day-hour-end-brt 22)
(def brt-zone (ZoneId/of "America/Sao_Paulo"))

;; Cap diário piloto (metas de categoria ≠ obrigação absoluta).
(def default-daily-limit 12)
;; Últimos N slots do dia preferem especial due (anti-starvation).
(def default-special-reserve 2)
(def min-daily-limit 1)
(def max-daily-limit 48)

;; Compat 12.7.1: agendas sem next-categoria e interval longo ainda lembram.
(def legacy-reminder-interval-ms (* 3 60 60 1000))

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

(defn intent-receipt-id
  [chat-id]
  (str "raid-auto-intent:" chat-id))

(defn cadence-receipt-id
  [chat-id]
  (str "raid-auto-cadence:" chat-id))

(defn special-category?
  [categoria]
  (contains? special-categories (str categoria)))

(defn hour-of-day-brt
  [epoch-ms]
  (.getHour (.atZone (Instant/ofEpochMilli (long epoch-ms)) brt-zone)))

(defn day-window-brt?
  "True em [08:00, 22:00) America/Sao_Paulo."
  [epoch-ms]
  (let [h (hour-of-day-brt epoch-ms)]
    (and (<= day-hour-start-brt h) (< h day-hour-end-brt))))

(defn day-key-brt
  "Chave de dia civil BRT: \"yyyy-MM-dd\"."
  [epoch-ms]
  (let [zdt (.atZone (Instant/ofEpochMilli (long epoch-ms)) brt-zone)]
    (str (.getYear zdt) "-"
         (format "%02d" (.getMonthValue zdt)) "-"
         (format "%02d" (.getDayOfMonth zdt)))))

(defn start-of-day-brt
  [epoch-ms]
  (-> (Instant/ofEpochMilli (long epoch-ms))
      (.atZone brt-zone)
      (.toLocalDate)
      (.atStartOfDay brt-zone)
      (.toInstant)
      (.toEpochMilli)))

(defn next-day-morning-brt
  "Próximo 08:00 BRT estritamente após o dia civil de epoch-ms (retoma filler)."
  [epoch-ms]
  (let [next-date (-> (Instant/ofEpochMilli (long epoch-ms))
                      (.atZone brt-zone)
                      (.toLocalDate)
                      (.plusDays 1))]
    (-> next-date
        (.atTime day-hour-start-brt 0)
        (.atZone brt-zone)
        (.toInstant)
        (.toEpochMilli))))

(defn normalize-daily-limit
  [n]
  (let [v (long (or n default-daily-limit))]
    (when-not (<= min-daily-limit v max-daily-limit)
      (throw (err/error :raid_auto_daily_limit_invalid
                        {:daily-limit v
                         :min min-daily-limit
                         :max max-daily-limit})))
    v))

(defn normalize-special-reserve
  [n limit]
  (let [lim (normalize-daily-limit limit)
        v (long (or n default-special-reserve))]
    (when-not (<= 0 v lim)
      (throw (err/error :raid_auto_special_reserve_invalid
                        {:special-reserve v :daily-limit lim})))
    v))

(defn normalize-category-intervals
  "Valida mapa cat→ms. Chaves desconhecidas são ignoradas; faltantes usam default."
  [raw]
  (let [base category-interval-ms-default
        raw (or raw {})]
    (reduce
     (fn [acc cat]
       (let [v (or (get raw cat)
                   (get raw (keyword cat))
                   (get base cat))
             n (long v)]
         (when-not (pos? n)
           (throw (err/error :raid_auto_category_interval_invalid
                             {:categoria cat :interval-ms n})))
         (assoc acc cat n)))
     {}
     (keys base))))

(defn count-spawns-on-day
  "Quantos timestamps caem no mesmo dia BRT que `agora`."
  [spawn-epochs agora]
  (let [key (day-key-brt agora)]
    (count (filter #(= key (day-key-brt %)) (or spawn-epochs [])))))

(defn specials-spawned-on-day
  "Contagem de especiais (lendario/mitico) no dia BRT de `agora`.
   `spawn-cats` = seq de {:at epoch :categoria cat}."
  [spawn-cats agora]
  (let [key (day-key-brt agora)]
    (count (filter (fn [row]
                     (and (= key (day-key-brt (:at row)))
                          (special-category? (:categoria row))))
                   (or spawn-cats [])))))

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
   :recent-bosses []
   :category-intervals category-interval-ms-default
   :daily-limit default-daily-limit
   :special-reserve default-special-reserve
   ;; next-categoria / next-reminders? vêm do intent (receipt); nil = legado.
   :next-categoria nil})

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
                          (map str (or (:recent-bosses m) []))))
        intervals (normalize-category-intervals
                   (or (:category-intervals m) category-interval-ms-default))
        daily-limit (normalize-daily-limit
                     (or (:daily-limit m) default-daily-limit))
        special-reserve (normalize-special-reserve
                         (or (:special-reserve m) default-special-reserve)
                         daily-limit)
        next-cat (when-let [c (:next-categoria m)]
                   (let [s (str/lower-case (str c))]
                     (when (contains? (set (keys category-interval-ms-default)) s)
                       s)))
        raw (or raw {})]
    (cond-> (-> m
                (assoc :chat-id (str chat-id)
                       :enabled (boolean (:enabled m))
                       :interval-ms interval
                       :next-at (long (or (:next-at m) agora 0))
                       :ginasio-index (long (or (:ginasio-index m) 0))
                       :revision (long (or (:revision m) 0))
                       :recent-bosses recent
                       :category-intervals intervals
                       :daily-limit daily-limit
                       :special-reserve special-reserve
                       :next-categoria next-cat)
                (dissoc :next-reminders?))
      (contains? raw :next-reminders?)
      (assoc :next-reminders? (boolean (:next-reminders? raw)))
      (and (not (contains? raw :next-reminders?))
           (special-category? next-cat))
      (assoc :next-reminders? true))))

(defn safe-cadence-patch
  "Migração 6h→1h (ou qualquer mudança de interval): nunca puxa next-at para
   o passado nem para 'agora'. Preserva next-at futuro e next-categoria especial.
   Retorna patch para merge em upsert — não aplica sozinho."
  [schedule {:keys [interval-ms daily-limit special-reserve category-intervals
                    next-at next-categoria agora]}]
  (let [agora (long (or agora (System/currentTimeMillis)))
        cur (normalize-schedule schedule (:chat-id schedule) agora)
        keep-next (long (:next-at cur 0))
        ;; Só aceita next-at explícito se for >= next atual (não antecipa) e > agora.
        next' (cond
                (nil? next-at) keep-next
                (and (> (long next-at) agora)
                     (>= (long next-at) keep-next))
                (long next-at)
                :else keep-next)
        cat' (cond
               (special-category? (:next-categoria cur))
               (:next-categoria cur) ;; nunca dropar especial já planejado
               (some? next-categoria) (str next-categoria)
               :else (:next-categoria cur))]
    (cond-> {:chat-id (:chat-id cur)
             :next-at next'
             :revision (:revision cur)}
      (some? interval-ms) (assoc :interval-ms (normalize-interval interval-ms))
      (some? daily-limit) (assoc :daily-limit (normalize-daily-limit daily-limit))
      (some? special-reserve)
      (assoc :special-reserve
             (normalize-special-reserve special-reserve
                                        (or daily-limit (:daily-limit cur))))
      (some? category-intervals)
      (assoc :category-intervals (normalize-category-intervals category-intervals))
      (some? cat') (assoc :next-categoria cat'
                          :next-reminders? (special-category? cat')))))

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

(defn reminders-allowed?
  "12.7.2: ovo só para especiais. Compat: agenda legada (sem next-categoria,
   interval ≥ 3h) mantém lembretes 12.7.1."
  [schedule]
  (let [cat (:next-categoria schedule)
        explicit (:next-reminders? schedule)
        interval (long (:interval-ms schedule default-interval-ms))]
    (cond
      (true? explicit) true
      (false? explicit) false
      (special-category? cat) true
      (and (nil? cat) (>= interval legacy-reminder-interval-ms)) true
      :else false)))

(defn plan-reminders
  "Lembretes due para a agenda atual. Puro — store cria outbox se id ainda não existe.
   Não revela chefe; vincula event-id ao next-at (due) da raid agendada.
   12.7.2: só especiais (ou agenda legada 12.7.1)."
  [schedule agora]
  (let [schedule (normalize-schedule schedule (:chat-id schedule) agora)
        agora (long agora)
        due (long (:next-at schedule 0))
        interval (:interval-ms schedule)]
    (if-not (and (:enabled schedule) (reminders-allowed? schedule))
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

(defn category-due?
  "True se a categoria nunca spawnou ou o cooldown já passou em `agora`."
  ([categoria agora last-by-cat]
   (category-due? categoria agora last-by-cat category-interval-ms-default))
  ([categoria agora last-by-cat intervals]
   (let [cat (str categoria)
         interval (long (or (get intervals cat)
                            (get category-interval-ms-default cat)
                            default-interval-ms))
         last (get last-by-cat cat)]
     (or (nil? last)
         (>= (- (long agora) (long last)) interval)))))

(defn category-overdue?
  "True se o atraso ≥ factor × intervalo (ex.: 1.5 = 50% atrasado)."
  ([categoria agora last-by-cat]
   (category-overdue? categoria agora last-by-cat category-interval-ms-default 1.5))
  ([categoria agora last-by-cat intervals factor]
   (let [cat (str categoria)
         interval (long (or (get intervals cat)
                            (get category-interval-ms-default cat)
                            default-interval-ms))
         last (get last-by-cat cat)]
     (and (some? last)
          (>= (- (long agora) (long last))
              (long (* (double factor) interval)))))))

(defn pick-categoria
  "Escolhe categoria due com viés dia/noite + anti-starvation de especiais.
   Metas de frequência ≠ obrigação: cap diário e reserva moldam a escolha.
   Dia: épico/raro; noite/overdue/reserva: especiais entram."
  ([agora last-by-cat]
   (pick-categoria agora last-by-cat {}))
  ([agora last-by-cat opts]
   (let [intervals (normalize-category-intervals
                    (or (:category-intervals opts) category-interval-ms-default))
         daily-limit (normalize-daily-limit
                      (or (:daily-limit opts) default-daily-limit))
         reserve (normalize-special-reserve
                  (or (:special-reserve opts) default-special-reserve)
                  daily-limit)
         spawns-today (long (or (:spawns-today opts) 0))
         specials-today (long (or (:specials-today opts) 0))
         remaining (max 0 (- daily-limit spawns-today))
         due-set (set (filter #(category-due? % agora last-by-cat intervals)
                              category-order))
         day? (day-window-brt? agora)
         special-due (filterv #(contains? due-set %) ["mitico" "lendario"])
         special-overdue (filterv #(category-overdue? % agora last-by-cat intervals 1.5)
                                  special-due)
         in-reserve? (and (pos? reserve)
                          (<= remaining reserve))
         ;; Sem especial hoje + restante apertado + especial due → força.
         starved? (and (zero? specials-today)
                       (seq special-due)
                       (<= remaining (max reserve 2)))]
     (cond
       (empty? due-set) "raro"

       (and (seq special-due)
            (or starved? in-reserve?
                (not day?) (seq special-overdue)))
       (first special-due)

       day?
       (or (when (contains? due-set "epico") "epico")
           (when (contains? due-set "raro") "raro")
           (first special-due)
           "raro")

       :else
       (or (first special-due)
           (when (contains? due-set "epico") "epico")
           (when (contains? due-set "raro") "raro")
           "raro")))))

(defn force-category-weights
  "Pesos 100% em uma categoria (seleção dinâmica ainda usa o catálogo)."
  [categoria]
  {(str (or categoria "raro")) 1.0})

(defn plan-next-intent
  "Planeja categoria do próximo slot (após spawn/skip). Puro."
  [next-at last-by-cat opts]
  (let [cat (or (:preserve-categoria opts)
                (pick-categoria next-at last-by-cat opts))]
    {:next-at (long next-at)
     :categoria (str cat)
     :reminders? (special-category? cat)}))

(defn- budget-opts
  [schedule opts]
  (let [daily-limit (normalize-daily-limit
                     (or (:daily-limit opts)
                         (:daily-limit schedule)
                         default-daily-limit))
        reserve (normalize-special-reserve
                 (or (:special-reserve opts)
                     (:special-reserve schedule)
                     default-special-reserve)
                 daily-limit)]
    {:category-intervals (normalize-category-intervals
                          (or (:category-intervals opts)
                              (:category-intervals schedule)
                              category-interval-ms-default))
     :daily-limit daily-limit
     :special-reserve reserve
     :spawns-today (long (or (:spawns-today opts) 0))
     :specials-today (long (or (:specials-today opts) 0))}))

(defn plan-spawn
  "Decide o que o tick deve fazer. Puro — store aplica CAS.
   Retorna {:acao :spawn|:skip-busy|:skip-daily-cap|:skip-disabled|:noop ...}.
   Opts: :rng, :recent-species, :catalog, :category-weights,
         :last-by-categoria, :forced-categoria, :category-intervals,
         :spawns-today, :specials-today, :daily-limit, :special-reserve,
         :preserve-next-categoria (skip-busy: não troca especial já planejado)."
  ([schedule active-raid agora]
   (plan-spawn schedule active-raid agora {}))
  ([schedule active-raid agora opts]
   (let [agora (long agora)
         schedule (normalize-schedule schedule (:chat-id schedule) agora)
         recent (vec (or (:recent-species opts) (:recent-bosses schedule) []))
         last-by (or (:last-by-categoria opts) {})
         cat-opts (budget-opts schedule opts)
         daily-limit (:daily-limit cat-opts)
         spawns-today (:spawns-today cat-opts)
         cap-reached? (>= spawns-today daily-limit)
         forced-or-planned (or (:forced-categoria opts)
                               (:next-categoria schedule))
         special-bypass? (and cap-reached?
                              (special-category? forced-or-planned)
                              (category-due? forced-or-planned agora last-by
                                             (:category-intervals cat-opts))
                              (zero? (:specials-today cat-opts)))]
     (cond
       (not (:enabled schedule))
       {:acao :skip-disabled :schedule schedule}

       (not (due? schedule agora))
       {:acao :noop :schedule schedule}

       (ocupa-slot-contra-spawn? active-raid)
       (let [due (long (:next-at schedule))
             next-at' (advance-next-at schedule due)
             preserve (or (:preserve-next-categoria opts)
                          (:next-categoria schedule))
             intent (plan-next-intent
                     next-at' last-by
                     (cond-> cat-opts
                       (special-category? preserve)
                       (assoc :preserve-categoria preserve)))]
         {:acao :skip-busy
          :schedule schedule
          :spawn-id (spawn-id (:chat-id schedule) due)
          :due-at due
          :next-at next-at'
          :next-categoria (:categoria intent)
          :next-reminders? (:reminders? intent)
          :intent intent
          :active-raid-id (:raid-id active-raid)
          :active-origem (:origem active-raid)
          :active-fase (:fase active-raid)})

       ;; Cap diário: salta para 08:00 BRT do dia seguinte (sem fila).
       ;; Exceção anti-starvation: 1 especial due ainda sem especial hoje.
       (and cap-reached? (not special-bypass?))
       (let [due (long (:next-at schedule))
             next-at' (max (advance-next-at schedule due)
                           (next-day-morning-brt agora))
             preserve (or (:preserve-next-categoria opts)
                          (:next-categoria schedule))
             intent (plan-next-intent
                     next-at' last-by
                     (cond-> (assoc cat-opts :spawns-today 0 :specials-today 0)
                       (special-category? preserve)
                       (assoc :preserve-categoria preserve)))]
         {:acao :skip-daily-cap
          :schedule schedule
          :spawn-id (spawn-id (:chat-id schedule) due)
          :due-at due
          :next-at next-at'
          :next-categoria (:categoria intent)
          :next-reminders? (:reminders? intent)
          :intent intent
          :spawns-today spawns-today
          :daily-limit daily-limit})

       :else
       (let [due (long (:next-at schedule))
             cat (str (or forced-or-planned
                          (pick-categoria due last-by cat-opts)
                          "raro"))
             weights (or (:category-weights opts)
                         (force-category-weights cat))
             gym (pick-ginasio (:ginasio-index schedule))
             boss (raid-boss/select-boss-for-ginasio
                   (:ginasio gym)
                   (cond-> {:recent-species recent
                            :category-weights weights}
                     (contains? opts :rng) (assoc :rng (:rng opts))
                     (contains? opts :catalog) (assoc :catalog (:catalog opts))))
             sid (spawn-id (:chat-id schedule) due)
             raid-id sid
             species (or (get-in boss [:chefe :species])
                         (get-in boss [:chefe "species"]))
             recent' (remember-boss recent species)
             actual-cat (str (or (:categoria boss)
                                 (get-in boss [:chefe :raridade])
                                 cat))
             lobby (raid/lobby-params-for-categoria actual-cat)
             next-at' (advance-next-at schedule due (:duracao-ms lobby))
             last-by' (assoc last-by actual-cat due)
             specials-today' (cond-> (:specials-today cat-opts)
                               (special-category? actual-cat) inc)
             intent (plan-next-intent
                     next-at' last-by'
                     (assoc cat-opts
                            :spawns-today (inc spawns-today)
                            :specials-today specials-today'))]
         {:acao :spawn
          :schedule (assoc schedule :recent-bosses recent')
          :spawn-id sid
          :due-at due
          :next-at next-at'
          :next-categoria (:categoria intent)
          :next-reminders? (:reminders? intent)
          :intent intent
          :next-ginasio-index (:next-ginasio-index gym)
          :raid-id raid-id
          :ginasio (:ginasio boss)
          :nome-ginasio (:nome-ginasio boss)
          :nivel-ginasio (:nivel-ginasio boss)
          :liga (:liga boss)
          :chefe (:chefe boss)
          :boss-categoria actual-cat
          :lobby lobby
          :duracao-ms (:duracao-ms lobby)
          :min-jogadores (:min-jogadores lobby)
          :max-jogadores (:max-jogadores lobby)
          :recent-bosses recent'
          :spawns-today spawns-today
          :daily-limit daily-limit})))))

(defn estimate-wa-messages
  "Estimativa de mensagens WA por dia dado mix de categorias.
   Fácil: opened + started/cancelled (~2). Especial: +3 lembretes ovo (~5)."
  [{:keys [raro epico lendario mitico]
    :or {raro 0 epico 0 lendario 0 mitico 0}}]
  (let [easy (+ (long raro) (long epico))
        special (+ (long lendario) (long mitico))]
    {:opened (+ easy special)
     :reminders (* special 3)
     :started-or-cancelled (+ easy special)
     :total-est (+ (* easy 2) (* special 5))}))

(defn simulate-cadence
  "Simula N dias de spawns em memória (sem Datomic). Retorna distribuição.
   Assume slot sempre livre (pior caso de volume)."
  [{:keys [start-ms days daily-limit special-reserve interval-ms]
    :or {days 7
         daily-limit default-daily-limit
         special-reserve default-special-reserve
         interval-ms default-interval-ms}}]
  (let [start (long start-ms)
        end (+ start (* (long days) 24 60 60 1000))
        sch0 (normalize-schedule
              {:enabled true
               :interval-ms interval-ms
               :next-at start
               :daily-limit daily-limit
               :special-reserve special-reserve
               :ginasio-index 0}
              "sim@g.us" start)]
    (loop [sch sch0
           agora start
           last-by {}
           events []
           spawn-cats []]
      (if (>= agora end)
        (let [by-cat (frequencies (map :boss-categoria events))
              by-day (->> events
                          (group-by #(day-key-brt (:due-at %)))
                          (map (fn [[d xs]] [d (count xs)]))
                          (into (sorted-map)))
              wa (estimate-wa-messages
                  {:raro (get by-cat "raro" 0)
                   :epico (get by-cat "epico" 0)
                   :lendario (get by-cat "lendario" 0)
                   :mitico (get by-cat "mitico" 0)})]
          {:days (long days)
           :daily-limit daily-limit
           :total-spawns (count events)
           :por-categoria by-cat
           :por-dia by-day
           :max-por-dia (if (seq by-day) (apply max (vals by-day)) 0)
           :avg-por-dia (if (pos? (long days))
                          (/ (double (count events)) (double days))
                          0.0)
           :wa-messages-total wa
           :wa-messages-per-day-est
           (into {}
                 (map (fn [[k v]]
                        [k (long (Math/round (/ (double v) (double days))))])
                      wa))
           :events events})
        (let [today-spawns (count-spawns-on-day (map :at spawn-cats) agora)
              today-specials (specials-spawned-on-day spawn-cats agora)
              plan (plan-spawn sch nil agora
                               {:last-by-categoria last-by
                                :spawns-today today-spawns
                                :specials-today today-specials
                                :daily-limit daily-limit
                                :special-reserve special-reserve
                                :forced-categoria (:next-categoria sch)
                                :rng (constantly 0.01)})]
          (case (:acao plan)
            :spawn
            (let [cat (:boss-categoria plan)
                  sch' (assoc sch
                              :next-at (:next-at plan)
                              :ginasio-index (:next-ginasio-index plan)
                              :next-categoria (:next-categoria plan)
                              :next-reminders? (:next-reminders? plan)
                              :revision (inc (long (:revision sch 0))))
                  row {:due-at (:due-at plan)
                       :boss-categoria cat
                       :next-at (:next-at plan)
                       :reminders? (:next-reminders? plan)}]
              (recur sch' (:next-at plan)
                     (assoc last-by cat (:due-at plan))
                     (conj events row)
                     (conj spawn-cats {:at (:due-at plan) :categoria cat})))

            (:skip-daily-cap :skip-busy)
            (let [sch' (assoc sch
                              :next-at (:next-at plan)
                              :next-categoria (:next-categoria plan)
                              :next-reminders? (:next-reminders? plan)
                              :revision (inc (long (:revision sch 0))))]
              (recur sch' (:next-at plan) last-by events spawn-cats))

            ;; noop / disabled — salta para next-at ou +1 min
            (recur sch
                   (max (+ agora 60000) (long (:next-at sch agora)))
                   last-by events spawn-cats)))))))
