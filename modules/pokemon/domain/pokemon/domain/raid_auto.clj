(ns pokemon.domain.raid-auto
  "Agenda de aparições automáticas — regras puras
  (Fase 3 / 3.1 / 12.7 / 12.7.1 / 12.7.2 / 12.7.3 / 12.8.2).
  Desligada por padrão; não altera combate/economia.
  Chefe: catálogo dinâmico (raid-boss), não lista fixa de ginásio.
  Cadência 12.7.2: intervalo-base (~1h) + cooldown por categoria; lembretes
  T-30/15/5 (ovo) só para eventos especiais (lendário/mítico).
  12.7.3: intent do slot atual planejado antes de T-30 (não só pós-spawn).
  12.8.2: distribuição uniforme de até dailyLimit slots na janela
  [window-start, window-end) no timezone da agenda (default America/Sao_Paulo);
  ativação controlada via :distribution \"uniform\" (default legado \"interval\")."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-boss :as raid-boss]
            [pokemon.domain.raid-gym-theme :as gym-theme])
  (:import [java.time Instant LocalDate LocalTime ZoneId]))
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

;; Janela civil no timezone da agenda (não no TZ do servidor).
;; Legado 12.7: [08:00, 22:00). Uniform 12.8.2: [08:00, 23:00).
(def day-hour-start-brt 8)
(def day-hour-end-brt 22)
(def default-window-start-hour 8)
(def default-window-end-hour-legacy 22)
(def default-window-end-hour-uniform 23)
(def default-timezone "America/Sao_Paulo")
(def brt-zone (ZoneId/of default-timezone))
(def distribution-interval "interval")
(def distribution-uniform "uniform")
(def default-distribution distribution-interval)
;; Grace para slot atrasado: além disso → skip-missed (sem catch-up).
(def slot-stale-grace-ms (* 10 60 1000))

;; Cap diário piloto (metas de categoria ≠ obrigação absoluta).
(def default-daily-limit 12)
;; Até N slots/dia preferem especial due (ocupam o orçamento diário; não 13ª).
(def default-special-reserve 2)
(def min-daily-limit 1)
(def max-daily-limit 48)
(def min-window-hour 0)
(def max-window-hour 24)

;; Compat 12.7.1: agendas sem next-categoria e interval longo ainda lembram.
(def legacy-reminder-interval-ms (* 3 60 60 1000))

;; Offsets de lembrete visual (Fase 12.7.1) — vinculados ao next-at da agenda.
(def reminder-offset-30-ms (* 30 60 1000))
(def reminder-offset-15-ms (* 15 60 1000))
(def reminder-offset-5-ms (* 5 60 1000))

;; 12.7.3: planejar categoria com folga antes do primeiro ovo (T-30).
;; Tick típico ~30s → lead 40min garante intent persistido antes da janela.
(def intent-planning-lead-ms (* 40 60 1000))
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

(defn zone-id
  "Timezone da agenda — nunca o TZ implícito do servidor."
  [schedule-or-opts]
  (let [tz (str (or (when (map? schedule-or-opts)
                      (or (:timezone schedule-or-opts)
                          (get schedule-or-opts "timezone")))
                    default-timezone))]
    (try (ZoneId/of tz)
         (catch Exception _
           brt-zone))))

(defn normalize-distribution
  [raw]
  (let [s (str/lower-case (str (or raw default-distribution)))]
    (if (#{"uniform" "slots" "distributed"} s)
      distribution-uniform
      distribution-interval)))

(defn uniform-distribution?
  [schedule-or-opts]
  (= distribution-uniform
     (normalize-distribution
      (when (map? schedule-or-opts)
        (or (:distribution schedule-or-opts)
            (get schedule-or-opts "distribution"))))))

(defn normalize-window-hour
  [n label]
  (let [v (long n)]
    (when-not (<= min-window-hour v max-window-hour)
      (throw (err/error :raid_auto_window_invalid
                        {:field label :hour v
                         :min min-window-hour :max max-window-hour})))
    v))

(defn window-config
  "Extrai {:start :end :distribution :timezone :zone} da agenda/opts."
  [schedule-or-opts]
  (let [m (if (map? schedule-or-opts) schedule-or-opts {})
        dist (normalize-distribution
              (or (:distribution m) (get m "distribution")))
        start (normalize-window-hour
               (or (:window-start-hour m) (:window-start m)
                   (get m "windowStart") (get m "window-start")
                   default-window-start-hour)
               :window-start)
        end-default (if (= dist distribution-uniform)
                      default-window-end-hour-uniform
                      default-window-end-hour-legacy)
        end (normalize-window-hour
             (or (:window-end-hour m) (:window-end m)
                 (get m "windowEnd") (get m "window-end")
                 end-default)
             :window-end)
        tz (str (or (:timezone m) (get m "timezone") default-timezone))]
    (when-not (< start end)
      (throw (err/error :raid_auto_window_invalid
                        {:window-start start :window-end end})))
    {:start start :end end :distribution dist :timezone tz
     :zone (zone-id {:timezone tz})}))

(defn hour-of-day-brt
  ([epoch-ms]
   (hour-of-day-brt epoch-ms brt-zone))
  ([epoch-ms zone]
   (.getHour (.atZone (Instant/ofEpochMilli (long epoch-ms))
                      (or zone brt-zone)))))

(defn day-window-brt?
  "True em [window-start, window-end) no timezone da agenda.
   Aridade 1: janela legada [08:00, 22:00) America/Sao_Paulo."
  ([epoch-ms]
   (day-window-brt? epoch-ms {:distribution distribution-interval
                              :window-start-hour day-hour-start-brt
                              :window-end-hour day-hour-end-brt}))
  ([epoch-ms schedule-or-opts]
   (let [{:keys [start end zone]} (window-config schedule-or-opts)
         h (hour-of-day-brt epoch-ms zone)]
     (and (<= start h) (< h end)))))

(defn day-key-brt
  "Chave de dia civil no timezone: \"yyyy-MM-dd\"."
  ([epoch-ms]
   (day-key-brt epoch-ms brt-zone))
  ([epoch-ms zone]
   (let [zdt (.atZone (Instant/ofEpochMilli (long epoch-ms))
                      (or zone brt-zone))]
     (str (.getYear zdt) "-"
          (format "%02d" (.getMonthValue zdt)) "-"
          (format "%02d" (.getDayOfMonth zdt))))))

(defn start-of-day-brt
  ([epoch-ms]
   (start-of-day-brt epoch-ms brt-zone))
  ([epoch-ms zone]
   (-> (Instant/ofEpochMilli (long epoch-ms))
       (.atZone (or zone brt-zone))
       (.toLocalDate)
       (.atStartOfDay (or zone brt-zone))
       (.toInstant)
       (.toEpochMilli))))

(defn- local-date-of
  [epoch-ms zone]
  (-> (Instant/ofEpochMilli (long epoch-ms))
      (.atZone zone)
      (.toLocalDate)))

(defn- at-local-hm
  "Epoch ms de y-m-d h:min no zone."
  [^LocalDate date hour minute zone]
  (-> date
      (.atTime (LocalTime/of (int hour) (int minute)))
      (.atZone zone)
      (.toInstant)
      (.toEpochMilli)))

(defn window-start-ms
  "Início da janela no dia civil de epoch-ms."
  [epoch-ms schedule-or-opts]
  (let [{:keys [start zone]} (window-config schedule-or-opts)]
    (at-local-hm (local-date-of epoch-ms zone) start 0 zone)))

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

(defn next-day-morning-brt
  "Próximo início de janela estritamente após o dia civil de epoch-ms."
  ([epoch-ms]
   (next-day-morning-brt epoch-ms {:distribution distribution-interval
                                   :window-start-hour day-hour-start-brt
                                   :window-end-hour day-hour-end-brt}))
  ([epoch-ms schedule-or-opts]
   (let [{:keys [start zone]} (window-config schedule-or-opts)
         next-date (.plusDays (local-date-of epoch-ms zone) 1)]
     (at-local-hm next-date start 0 zone))))

(defn day-slots
  "Vetor de epoch-ms dos slots do dia civil de `epoch-ms`.
   Distribuição uniforme: slot[i] = window-start + i * span / daily-limit.
   Ex. 08–23 / 12 → passo 75min → 08:00 … 21:45."
  [epoch-ms schedule-or-opts]
  (let [{:keys [start end zone]} (window-config schedule-or-opts)
        limit (normalize-daily-limit
               (or (when (map? schedule-or-opts)
                     (or (:daily-limit schedule-or-opts)
                         (get schedule-or-opts "dailyLimit")))
                   default-daily-limit))
        date (local-date-of epoch-ms zone)
        win0 (at-local-hm date start 0 zone)
        span-ms (* (- (long end) (long start)) 60 60 1000)
        step (quot span-ms (long limit))]
    (mapv (fn [i] (+ win0 (* (long i) step))) (range limit))))

(defn next-slot-at-or-after
  "Primeiro slot >= agora no dia; senão primeiro slot do dia seguinte."
  [agora schedule-or-opts]
  (let [agora (long agora)
        today (day-slots agora schedule-or-opts)
        future (filterv #(>= (long %) agora) today)]
    (if (seq future)
      (long (first future))
      (long (first (day-slots (next-day-morning-brt agora schedule-or-opts)
                              schedule-or-opts))))))

(defn next-slot-strictly-after
  "Próximo slot com due > due-at; senão primeiro do dia seguinte."
  [due-at schedule-or-opts]
  (let [due (long due-at)
        today (day-slots due schedule-or-opts)
        later (filterv #(> (long %) due) today)]
    (if (seq later)
      (long (first later))
      (long (first (day-slots (next-day-morning-brt due schedule-or-opts)
                              schedule-or-opts))))))

(defn slot-index-on-day
  "Índice 0..limit-1 do slot que casa com due-at (tolerância 1s), ou nil."
  [due-at schedule-or-opts]
  (let [due (long due-at)
        slots (day-slots due schedule-or-opts)]
    (first
     (keep-indexed
      (fn [i s] (when (<= (Math/abs (- due (long s))) 1000) i))
      slots))))

(defn special-prefer-slot?
  "Últimos `special-reserve` índices do dia preferem especial (se elegível)."
  [due-at schedule-or-opts]
  (let [idx (slot-index-on-day due-at schedule-or-opts)
        limit (normalize-daily-limit
               (or (when (map? schedule-or-opts) (:daily-limit schedule-or-opts))
                   default-daily-limit))
        reserve (normalize-special-reserve
                 (or (when (map? schedule-or-opts) (:special-reserve schedule-or-opts))
                     default-special-reserve)
                 limit)]
    (boolean
     (and (some? idx)
          (pos? reserve)
          (>= (long idx) (- limit reserve))))))

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
   ;; 12.8.2 — default legado (interval); uniform só via config explícita.
   :distribution default-distribution
   :window-start-hour default-window-start-hour
   :window-end-hour default-window-end-hour-legacy
   :timezone default-timezone
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
        dist (normalize-distribution
              (or (:distribution m) (get m "distribution") default-distribution))
        win (window-config (assoc m :distribution dist))
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
                       :distribution dist
                       :window-start-hour (:start win)
                       :window-end-hour (:end win)
                       :timezone (:timezone win)
                       :next-categoria next-cat)
                (dissoc :next-reminders? :window-start :window-end))
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
                    next-at next-categoria agora distribution
                    window-start-hour window-end-hour timezone]}]
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
      (some? distribution)
      (assoc :distribution (normalize-distribution distribution))
      (some? window-start-hour)
      (assoc :window-start-hour (normalize-window-hour window-start-hour :window-start))
      (some? window-end-hour)
      (assoc :window-end-hour (normalize-window-hour window-end-hour :window-end))
      (some? timezone) (assoc :timezone (str timezone))
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
   Legado (interval): due+interval, com piso pós-lobby.
   Uniform (12.8.2): próximo slot estritamente após due (sem catch-up / compressão).
   `lobby-ms` = duração real do lobby deste spawn (dinâmico por categoria)."
  ([schedule due-at]
   (advance-next-at schedule due-at raid/duracao-inscricoes))
  ([schedule due-at lobby-ms]
   (let [due (long due-at)
         lobby (long (or lobby-ms raid/duracao-inscricoes))
         after-lobby (inc (+ due lobby))]
     (if (uniform-distribution? schedule)
       (let [slot (next-slot-strictly-after due schedule)]
         ;; Lobby longo (mítico 30m) não pode colidir com o próximo slot.
         (if (> after-lobby slot)
           (next-slot-at-or-after after-lobby schedule)
           slot))
       (let [interval (normalize-interval (:interval-ms schedule))]
         (max (+ due interval) after-lobby))))))

(defn ocupa-slot-contra-spawn?
  "Qualquer raide em inscrição/combate bloqueia spawn (manual nunca é substituída).
   Expire do tick deve rodar antes e liberar/transformar o ponteiro."
  [active-raid]
  (boolean
   (and active-raid
        (contains? #{"inscricoes" "combate"} (str (:fase active-raid))))))

(defn format-opened
  "Anúncio oficial pós-eclosão. Revela chefe/categoria/lobby dinâmico.
   Recomendados são informativos — mínimo real é 1 (não bloqueia solo)."
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
        rec (long (or (:recomendados raid)
                      (:recomendados lobby)
                      1))
        mx (long (or (:max-jogadores raid) (:max-jogadores lobby) 5))
        n (count (or (:ordem raid) []))
        nome (or (:nome chefe) (get chefe "nome") "?")
        hp (or (:hp chefe) (get chefe "hp") "?")
        label (or (:label lobby) cat)
        stars (or (:estrelas lobby) "⭐")
        solo-hint (when (> rec 1)
                    (str "💪 Você pode tentar sozinho, mas esta raid será muito difícil!\n"))]
    (str "⚡ *UMA RAID " (str/upper-case (str label)) " APARECEU!*\n"
         "\n"
         "👑 Chefe: *" nome "*\n"
         stars " Dificuldade: *" label "*\n"
         "❤️ HP base: " hp "\n"
         "🏛️ " (or (:nome-ginasio raid) (:ginasio raid) "Ginásio")
         " · liga " (or (:liga raid) "?") "\n"
         "👥 Jogadores recomendados: " rec "\n"
         "⚔️ Participantes: " n "/" mx "\n"
         (or solo-hint "")
         "⏳ Lobby aberto por *" minutos " minutos*.\n"
         "\n"
         "*Comandos:*\n"
         "`!pk raide entrar` — Pokémon ativo\n"
         "`!pk raide entrar auto` — melhor elegível\n"
         "`!pk raide entrar <n>` — índice da coleção\n"
         "`!pk raide iniciar` — começar agora (1+ inscrito)")))

(defn- golpe-label
  [g]
  (or (:nome-exibicao g) (get g "nomeExibicao") (get g "nome-exibicao")
      (:nome g) (get g "nome") "?"))

(defn- golpe-poder
  [g]
  (or (:poder g) (get g "poder") "?"))

(defn- format-started-turn-lines
  "Bloco de turno alinhado a formatRaidTurnBlock (Node) — sem IDs técnicos."
  [raid]
  (let [parts (:participantes raid {})
        vez (:vez raid)
        vp (when vez (get parts vez))
        poke (or (:pokemon vp) {})
        nome (let [n (or (:nome vp) "Treinador")]
               (if (or (re-find #"@(?:lid|c\.us)\b" (str n))
                       (re-find #"^\d{8,}$" (str n)))
                 "Treinador"
                 n))
        poke-nome (or (:nome poke) (:species poke) (get poke "nome") "?")
        hp (or (:hp vp) (:hp-atual poke) (get poke "hpAtual") "?")
        golpes (vec (or (:golpes poke) (get poke "golpes") []))
        move-lines
        (map-indexed
         (fn [i g]
           (str (inc i) ". " (golpe-label g) " (" (golpe-poder g) ")"
                " → `!pk raide atacar " (inc i) "`"))
         (take 4 golpes))
        vivos (filterv #(pos? (long (get-in parts [% :hp] 0))) (:ordem raid []))
        proxima (when (and vez (seq vivos))
                  (first (concat (rest (drop-while #(not= % vez) vivos))
                                 (take-while #(not= % vez) vivos))))
        prox-nome (when proxima
                    (let [n (get-in parts [proxima :nome])]
                      (when (and n (not (re-find #"@(?:lid|c\.us)\b" (str n))))
                        n)))]
    (cond-> [(str "👉 Vez de *" nome "* — " poke-nome " (HP " hp ")")]
      (seq move-lines) (into move-lines)
      (empty? move-lines) (conj "_sem golpes ofensivos listados_")
      prox-nome (conj (str "⏭️ Próximo: *" prox-nome "*"))
      true (conj "⏳ Aguarde a vez do treinador indicado — ou ataque se for você."))))

(defn format-started
  "Anúncio de combate automático — mesma informação do início manual
  (HP do chefe + vez + golpes + comando atacar). Degrada se ainda sem :vez."
  [raid]
  (let [ginasio (or (:nome-ginasio raid) (:ginasio raid) "ginásio")
        chefe (or (:chefe raid) {})
        chefe-nome (or (:nome chefe) (get chefe "nome") "Chefe")
        hp-c (or (:hp-chefe raid) "?")
        hp-m (or (:hp-max raid) hp-c)
        header [(str "⚔️ *RAID INICIADA AUTOMATICAMENTE!*")
                (str "🏛️ " ginasio)
                (str "🔥 Chefe: *" chefe-nome "*")
                (str "❤️ HP do chefe: " hp-c "/" hp-m)]
        turn (when (and (= "combate" (str (:fase raid))) (:vez raid))
               (format-started-turn-lines raid))]
    (->> (concat header [""] (or turn
                                 ["⚔️ O combate começou — use `!pk raide status`."]))
         (str/join "\n"))))

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
  "12.7.2 / 12.8.2: ovo só para especiais. Compat: agenda legada (sem
   next-categoria, interval ≥ 3h) mantém lembretes 12.7.1.
   `next-reminders?=true` sem categoria especial NÃO libera lembrete (anti-fantasma)."
  [schedule]
  (let [cat (:next-categoria schedule)
        explicit (:next-reminders? schedule)
        interval (long (:interval-ms schedule default-interval-ms))]
    (cond
      (false? explicit) false
      (special-category? cat) true
      (and (nil? cat) (>= interval legacy-reminder-interval-ms)) true
      :else false)))

(defn reminder-opportunity-viable?
  "12.8.2: lembrete só se a oportunidade especial ainda puder eclosionar.
   Em distribution=uniform exige due dentro da janela e slot válido.
   Modo interval (legado): mantém só enabled/especial/cap.
   Opts opcionais: :spawns-today (default 0)."
  ([schedule agora]
   (reminder-opportunity-viable? schedule agora {}))
  ([schedule agora opts]
   (let [schedule (normalize-schedule schedule (:chat-id schedule) agora)
         due (long (:next-at schedule 0))
         agora (long agora)
         spawns (long (or (:spawns-today opts) 0))
         limit (long (:daily-limit schedule default-daily-limit))
         uniform? (uniform-distribution? schedule)]
     (boolean
      (and (:enabled schedule)
           (reminders-allowed? schedule)
           (pos? due)
           (< agora due)
           (< spawns limit)
           (or (not uniform?)
               (and (day-window-brt? due schedule)
                    (some? (slot-index-on-day due schedule)))))))))

(defn plan-reminders
  "Lembretes due para a agenda atual. Puro — store cria outbox se id ainda não existe.
   Não revela chefe; vincula event-id ao next-at (due) da raid agendada.
   12.7.2: só especiais (ou agenda legada 12.7.1).
   12.8.2: exige oportunidade viável (janela/cap/slot)."
  ([schedule agora]
   (plan-reminders schedule agora {}))
  ([schedule agora opts]
   (let [schedule (normalize-schedule schedule (:chat-id schedule) agora)
         agora (long agora)
         due (long (:next-at schedule 0))
         interval (:interval-ms schedule)]
     (if-not (reminder-opportunity-viable? schedule agora opts)
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
                     :media-key (str "raid-egg-" minutes "min")})))))))

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

(defn- common-pick
  [due-set]
  (or (when (contains? due-set "epico") "epico")
      (when (contains? due-set "raro") "raro")
      "raro"))

(defn pick-categoria
  "Escolhe categoria due com viés dia/noite + anti-starvation de especiais.
   Metas de frequência ≠ obrigação: cap diário e reserva moldam a escolha.
   Dia: épico/raro; noite/overdue/reserva/slots preferenciais: especiais entram.
   12.8.2: reserva esgotada NÃO bloqueia comuns; especiais nunca excedem reserve."
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
         sch-opts (cond-> opts
                    (nil? (:daily-limit opts)) (assoc :daily-limit daily-limit)
                    (nil? (:special-reserve opts)) (assoc :special-reserve reserve))
         day? (day-window-brt? agora sch-opts)
         special-budget? (< specials-today reserve)
         special-due (filterv #(and special-budget? (contains? due-set %))
                              ["mitico" "lendario"])
         special-overdue (filterv #(category-overdue? % agora last-by-cat intervals 1.5)
                                  special-due)
         in-reserve? (and (pos? reserve)
                          special-budget?
                          (<= remaining reserve))
         prefer-slot? (and special-budget?
                           (uniform-distribution? sch-opts)
                           (special-prefer-slot? agora sch-opts))
         ;; Sem especial hoje + restante apertado + especial due → força.
         starved? (and (zero? specials-today)
                       (seq special-due)
                       (<= remaining (max reserve 2)))]
     (cond
       (empty? due-set) "raro"

       (and (seq special-due)
            (or starved? in-reserve? prefer-slot?
                (not day?) (seq special-overdue)))
       (first special-due)

       day?
       (or (common-pick due-set)
           (first special-due)
           "raro")

       :else
       (or (first special-due)
           (common-pick due-set)
           "raro")))))

(defn resolve-categoria
  "Seleciona categoria elegível. Se `preferred` inelegível, faz fallback comum
   sem inflar especiais. Retorna {:categoria :fallback-from? :fallback-reason?}."
  [preferred agora last-by-cat opts]
  (let [intervals (normalize-category-intervals
                   (or (:category-intervals opts) category-interval-ms-default))
        preferred (when preferred (str preferred))
        picked (pick-categoria agora last-by-cat opts)
        due? (fn [c] (category-due? c agora last-by-cat intervals))
        reserve (normalize-special-reserve
                 (or (:special-reserve opts) default-special-reserve)
                 (or (:daily-limit opts) default-daily-limit))
        specials-today (long (or (:specials-today opts) 0))
        special-ok? (fn [c]
                      (or (not (special-category? c))
                          (< specials-today reserve)))]
    (cond
      (and preferred (due? preferred) (special-ok? preferred))
      {:categoria preferred}

      (and preferred (not (due? preferred)))
      (let [fb (if (and (due? picked) (special-ok? picked))
                 picked
                 (common-pick (set (filter due? ["epico" "raro"]))))]
        {:categoria (str fb)
         :fallback-from preferred
         :fallback-reason :category-cooldown})

      (and preferred (special-category? preferred) (not (special-ok? preferred)))
      {:categoria (common-pick (set (filter due? ["epico" "raro"])))
       :fallback-from preferred
       :fallback-reason :special-reserve-exhausted}

      :else
      {:categoria (str (or picked "raro"))})))

(defn force-category-weights
  "Pesos 100% em uma categoria (seleção dinâmica ainda usa o catálogo)."
  [categoria]
  {(str (or categoria "raro")) 1.0})

(defn plan-next-intent
  "Planeja categoria de um slot (next-at alvo). Puro.
   Usado no pós-spawn/skip (slot seguinte) e no planejamento antecipado 12.7.3
   (slot atual)."
  [next-at last-by-cat opts]
  (let [resolved (if-let [p (:preserve-categoria opts)]
                   (resolve-categoria p next-at last-by-cat opts)
                   (resolve-categoria nil next-at last-by-cat opts))
        cat (:categoria resolved)]
    (cond-> {:next-at (long next-at)
             :categoria (str cat)
             :reminders? (special-category? cat)}
      (:fallback-reason resolved)
      (assoc :fallback-from (:fallback-from resolved)
             :fallback-reason (:fallback-reason resolved)))))

(defn intent-matches-next-at?
  "True se o receipt de intent aponta para o mesmo next-at da agenda."
  [intent next-at]
  (and (map? intent)
       (some? (:next-at intent))
       (some? next-at)
       (= (long (:next-at intent)) (long next-at))))

(defn needs-intent-planning?
  "12.7.3: agenda enabled com next-at futuro e sem categoria durável para esse
   slot. Planeja assim que o slot entra na janela (agora + lead >= next-at) ou
   já está dentro de T-30 — nunca após o due (spawn assume).
   Não re-sorteia se next-categoria já veio do intent (enrich) ou PUT."
  ([schedule agora]
   (needs-intent-planning? schedule agora intent-planning-lead-ms))
  ([schedule agora lead-ms]
   (let [agora (long agora)
         due (long (:next-at schedule 0))
         lead (long (or lead-ms intent-planning-lead-ms))]
     (boolean
      (and (map? schedule)
           (:enabled schedule)
           (pos? due)
           (< agora due)
           ;; dentro da janela de planejamento (lead) ou já em T-30+
           (<= due (+ agora (max lead reminder-offset-30-ms)))
           (nil? (:next-categoria schedule)))))))

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

(defn plan-slot-intent
  "Planeja categoria do slot ATUAL (next-at da agenda). Puro — sem spawn.
   Preserva especial já reservado em :next-categoria / :preserve-categoria."
  [schedule last-by-cat opts]
  (let [schedule (normalize-schedule schedule (:chat-id schedule)
                                     (or (:next-at schedule) 0))
        due (long (:next-at schedule))
        cat-opts (budget-opts schedule opts)
        preserve (or (:preserve-categoria opts)
                     (when (special-category? (:next-categoria schedule))
                       (:next-categoria schedule)))]
    (plan-next-intent
     due last-by-cat
     (cond-> cat-opts
       (special-category? preserve)
       (assoc :preserve-categoria (str preserve))))))

(defn- intent-after
  [next-at last-by cat-opts preserve]
  (plan-next-intent
   next-at last-by
   (cond-> cat-opts
     (special-category? preserve)
     (assoc :preserve-categoria (str preserve)))))

(defn- skip-plan
  "Resultado comum de skip (não altera last-spawn-id no store)."
  [acao schedule due next-at' intent extra]
  (merge
   {:acao acao
    :schedule schedule
    :spawn-id (spawn-id (:chat-id schedule) due)
    :due-at due
    :next-at next-at'
    :next-categoria (:categoria intent)
    :next-reminders? (:reminders? intent)
    :intent intent
    :skip-reason acao}
   extra))

(defn plan-spawn
  "Decide o que o tick deve fazer. Puro — store aplica CAS.
   Retorna {:acao :spawn|:skip-busy|:skip-daily-cap|:skip-outside-window|
                   :skip-missed|:skip-disabled|:noop ...}.
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
                              (zero? (:specials-today cat-opts))
                              (< (:specials-today cat-opts)
                                 (:special-reserve cat-opts)))
         due (long (:next-at schedule 0))
         uniform? (uniform-distribution? schedule)
         preserve (or (:preserve-next-categoria opts)
                      (:next-categoria schedule))]
     (cond
       (not (:enabled schedule))
       {:acao :skip-disabled :schedule schedule}

       (not (due? schedule agora))
       {:acao :noop :schedule schedule}

       ;; 12.8.2 uniform: fora da janela não abre nem consome orçamento.
       ;; Modo interval (legado): permite especiais noturnos como antes.
       (and uniform? (not (day-window-brt? due schedule)))
       (let [next-at' (next-slot-at-or-after agora schedule)
             intent (intent-after next-at' last-by cat-opts preserve)]
         (skip-plan :skip-outside-window schedule due next-at' intent {}))

       ;; Slot velho demais (restart/downtime): sem catch-up — pula ao futuro.
       (and uniform? (> (- agora due) slot-stale-grace-ms))
       (let [next-at' (next-slot-at-or-after agora schedule)
             intent (intent-after next-at' last-by cat-opts preserve)]
         (skip-plan :skip-missed schedule due next-at' intent
                    {:stale-by-ms (- agora due)}))

       (ocupa-slot-contra-spawn? active-raid)
       (let [next-at' (advance-next-at schedule due)
             intent (intent-after next-at' last-by cat-opts preserve)]
         (skip-plan :skip-busy schedule due next-at' intent
                    {:active-raid-id (:raid-id active-raid)
                     :active-origem (:origem active-raid)
                     :active-fase (:fase active-raid)}))

       ;; Cap diário: salta para início da janela do dia seguinte (sem fila).
       ;; Exceção anti-starvation: 1 especial due ainda sem especial hoje.
       (and cap-reached? (not special-bypass?))
       (let [next-at' (if uniform?
                        (long (first (day-slots
                                      (next-day-morning-brt agora schedule)
                                      (assoc schedule :spawns-today 0))))
                        (max (advance-next-at schedule due)
                             (next-day-morning-brt agora schedule)))
             intent (intent-after
                     next-at' last-by
                     (assoc cat-opts :spawns-today 0 :specials-today 0)
                     preserve)]
         (skip-plan :skip-daily-cap schedule due next-at' intent
                    {:spawns-today spawns-today :daily-limit daily-limit}))

       :else
       (let [resolved (resolve-categoria forced-or-planned due last-by cat-opts)
             cat (str (or (:categoria resolved) "raro"))
             weights (or (:category-weights opts)
                         (force-category-weights cat))
             gym (pick-ginasio (:ginasio-index schedule))
             boss0 (raid-boss/select-boss-for-ginasio
                    (:ginasio gym)
                    (cond-> {:recent-species recent
                             :category-weights weights}
                      (contains? opts :rng) (assoc :rng (:rng opts))
                      (contains? opts :catalog) (assoc :catalog (:catalog opts))))
             ;; Dificuldade (nível/liga) do cursor; identidade visual pelo tipo do chefe.
             boss (gym-theme/apply-boss-venue boss0 (:chefe boss0))
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
         (cond-> {:acao :spawn
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
                  :boss-tipo (:boss-tipo boss)
                  :lobby lobby
                  :duracao-ms (:duracao-ms lobby)
                  :min-jogadores (:min-jogadores lobby)
                  :recomendados (:recomendados lobby)
                  :max-jogadores (:max-jogadores lobby)
                  :recent-bosses recent'
                  :spawns-today spawns-today
                  :daily-limit daily-limit}
           (:fallback-reason resolved)
           (assoc :fallback-from (:fallback-from resolved)
                  :fallback-reason (:fallback-reason resolved))))))))

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

(defn schedule-diagnostics
  "Snapshot administrativo puro (GET / observabilidade). Sem PII além de chat-id."
  [schedule agora opts]
  (let [schedule (normalize-schedule schedule (:chat-id schedule) agora)
        agora (long agora)
        win (window-config schedule)
        spawns (long (or (:spawns-today opts) 0))
        specials (long (or (:specials-today opts) 0))
        limit (long (:daily-limit schedule))
        reserve (long (:special-reserve schedule))
        due (long (:next-at schedule 0))
        slots (when (uniform-distribution? schedule)
                (day-slots agora schedule))]
    {:chat-id (:chat-id schedule)
     :enabled (boolean (:enabled schedule))
     :revision (long (:revision schedule 0))
     :distribution (:distribution schedule)
     :timezone (:timezone win)
     :window-start-hour (:start win)
     :window-end-hour (:end win)
     :window-open? (day-window-brt? agora schedule)
     :daily-limit limit
     :special-reserve reserve
     :spawns-today spawns
     :specials-today specials
     :slots-remaining (max 0 (- limit spawns))
     :special-remaining (max 0 (- reserve specials))
     :next-at due
     :next-categoria (:next-categoria schedule)
     :next-reminders? (boolean (:next-reminders? schedule))
     :last-spawn-id (:last-spawn-id schedule)
     :last-raid-id (:last-raid-id schedule)
     :slots-today slots
     :next-slot-index (slot-index-on-day due schedule)
     :last-skip-reason (:last-skip-reason opts)
     :last-tick-at (:last-tick-at opts)}))

(defn simulate-cadence
  "Simula N dias de spawns em memória (sem Datomic). Retorna distribuição.
   Assume slot sempre livre (pior caso de volume).
   12.8.2: passe :distribution \"uniform\" + window-* para a nova política."
  [{:keys [start-ms days daily-limit special-reserve interval-ms
           distribution window-start-hour window-end-hour timezone]
    :or {days 7
         daily-limit default-daily-limit
         special-reserve default-special-reserve
         interval-ms default-interval-ms
         distribution default-distribution}}]
  (let [start (long start-ms)
        end (+ start (* (long days) 24 60 60 1000))
        sch0 (normalize-schedule
              (cond-> {:enabled true
                       :interval-ms interval-ms
                       :next-at start
                       :daily-limit daily-limit
                       :special-reserve special-reserve
                       :distribution distribution
                       :ginasio-index 0}
                (some? window-start-hour)
                (assoc :window-start-hour window-start-hour)
                (some? window-end-hour)
                (assoc :window-end-hour window-end-hour)
                (some? timezone) (assoc :timezone timezone))
              "sim@g.us" start)
        ;; Uniform: ancora no primeiro slot do dia se start cair fora/antes.
        sch0 (if (uniform-distribution? sch0)
               (assoc sch0 :next-at (next-slot-at-or-after
                                     (window-start-ms start sch0) sch0))
               sch0)
        skips (atom [])]
    (loop [sch sch0
           agora (long (:next-at sch0))
           last-by {}
           events []
           spawn-cats []]
      (if (>= agora end)
        (let [by-cat (frequencies (map :boss-categoria events))
              by-day (->> events
                          (group-by #(day-key-brt (:due-at %) (zone-id sch0)))
                          (map (fn [[d xs]] [d (count xs)]))
                          (into (sorted-map)))
              by-hour (->> events
                           (map (fn [e]
                                  (hour-of-day-brt (:due-at e) (zone-id sch0))))
                           frequencies
                           (into (sorted-map)))
              wa (estimate-wa-messages
                  {:raro (get by-cat "raro" 0)
                   :epico (get by-cat "epico" 0)
                   :lendario (get by-cat "lendario" 0)
                   :mitico (get by-cat "mitico" 0)})]
          {:days (long days)
           :daily-limit daily-limit
           :distribution (:distribution sch0)
           :window-start-hour (:window-start-hour sch0)
           :window-end-hour (:window-end-hour sch0)
           :timezone (:timezone sch0)
           :total-spawns (count events)
           :por-categoria by-cat
           :por-dia by-day
           :por-hora-brt by-hour
           :skips @skips
           :skip-counts (frequencies (map :acao @skips))
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
           :sample-day-slots (day-slots start sch0)
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

            (:skip-daily-cap :skip-busy :skip-outside-window :skip-missed)
            (let [sch' (assoc sch
                              :next-at (:next-at plan)
                              :next-categoria (:next-categoria plan)
                              :next-reminders? (:next-reminders? plan)
                              :revision (inc (long (:revision sch 0))))]
              (swap! skips conj {:acao (:acao plan)
                                 :due-at (:due-at plan)
                                 :next-at (:next-at plan)})
              (recur sch' (:next-at plan) last-by events spawn-cats))

            ;; noop / disabled — salta para next-at ou +1 min
            (recur sch
                   (max (+ agora 60000) (long (:next-at sch agora)))
                   last-by events spawn-cats)))))))
