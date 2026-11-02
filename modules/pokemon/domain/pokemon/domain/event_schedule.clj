(ns pokemon.domain.event-schedule
  "Agenda automática de eventos temáticos (regras puras).
  Frequências configuráveis; sem I/O. Flag POKEMON_THEMATIC_EVENTS_ENABLED
  controla se o tick opera (default false até GO 12.8.1)."
  (:require [clojure.string :as str]
            [pokemon.domain.events :as events])
  (:import [java.time Duration Instant ZoneId ZonedDateTime]
           [java.time.temporal ChronoUnit]))

(def default-policy
  "Parâmetros iniciais (configuráveis via env/admin)."
  {:timezone "America/Sao_Paulo"
   :max-principal-ativos 1
   :anti-retroativo-grace-ms (* 2 60 60 1000) ;; 2h — não ativa se next-at atrasou demais
   :categorias
   {"comum" {:freq-por-semana 2 :duracao-h 24}
    "especial" {:freq-por-semana 1 :duracao-h 48}
    "raro" {:freq-por-mes 1 :duracao-h 72}}
   ;; Intervalo mínimo entre inícios de eventos principais no chat (ms).
   :intervalo-minimo-ms (* 72 60 60 1000)
   :catalog-ids-por-categoria
   {"comum" ["tempestade-eletrica" "mare-misteriosa" "despertar-floresta"]
    "especial" ["festival-dragoes" "noite-sombras" "era-fosseis"]
    "raro" ["eclipse-mistico"]}})

(defn policy-from-env
  "Sobrescreve defaults com env opcional (valores longos em horas/ms)."
  ([] (policy-from-env default-policy))
  ([base]
   (let [g #(some-> (System/getenv %) str/trim not-empty)
         long* (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))]
     (cond-> base
       (g "POKEMON_EVENTS_TIMEZONE")
       (assoc :timezone (g "POKEMON_EVENTS_TIMEZONE"))
       (g "POKEMON_EVENTS_MIN_INTERVAL_HOURS")
       (assoc :intervalo-minimo-ms
              (* 60 60 1000 (long* (g "POKEMON_EVENTS_MIN_INTERVAL_HOURS"))))
       (g "POKEMON_EVENTS_ANTIRETRO_GRACE_HOURS")
       (assoc :anti-retroativo-grace-ms
              (* 60 60 1000 (long* (g "POKEMON_EVENTS_ANTIRETRO_GRACE_HOURS"))))))))

(defn occurrence-id
  [chat-id catalog-id start-ms]
  (str chat-id ":" catalog-id ":" (long start-ms)))

(defn- zone [tz]
  (ZoneId/of (or tz "America/Sao_Paulo")))

(defn category-of
  [catalog-id]
  (or (some (fn [[cat ids]] (when (some #{catalog-id} ids) cat))
            (:catalog-ids-por-categoria default-policy))
      (get (events/by-id catalog-id) :categoria)
      "comum"))

(defn duration-ms
  ([catalog-id] (duration-ms catalog-id default-policy))
  ([catalog-id policy]
   (let [cat (category-of catalog-id)
         h (get-in policy [:categorias cat :duracao-h] 24)]
     (* h 60 60 1000))))

(defn eligible-catalog-ids
  "Ids elegíveis excluindo o último evento (anti-consecutivo) quando houver alternativas."
  ([last-catalog-id] (eligible-catalog-ids last-catalog-id default-policy))
  ([last-catalog-id policy]
   (let [all (mapv :id events/catalog)
         alts (if (and last-catalog-id (> (count all) 1))
                (filterv #(not= % last-catalog-id) all)
                all)]
     (or (not-empty alts) all))))

(defn pick-catalog-id
  "Sorteio determinístico a partir de draw (0..n-1) sobre elegíveis."
  [last-catalog-id draw]
  (let [ids (eligible-catalog-ids last-catalog-id)
        n (count ids)]
    (when (pos? n)
      (nth ids (mod (long (or draw 0)) n)))))

(defn conflicts-with-active?
  "Um único evento principal por chat."
  [active-occurrences]
  (>= (count (filter #(= "active" (:status %)) active-occurrences))
      (long (get default-policy :max-principal-ativos 1))))

(defn within-anti-retroactive?
  "true se o atraso de next-at ainda está dentro da graça."
  [next-at agora-ms policy]
  (let [grace (long (:anti-retroativo-grace-ms policy))
        due (long next-at)
        now (long agora-ms)]
    (<= (- now due) grace)))

(defn plan-activation
  "Decide se o tick deve abrir uma ocorrência. Retorna map ou nil.
  Não ativa se flag off, conflito, anti-retroativo ou intervalo mínimo."
  [{:keys [enabled? agora-ms next-at last-catalog-id last-start-ms
           active-occurrences draw policy]}]
  (let [policy (or policy (policy-from-env))
        agora (long agora-ms)
        next (long (or next-at 0))]
    (when (and enabled?
               (pos? next)
               (<= next agora)
               (within-anti-retroactive? next agora policy)
               (not (conflicts-with-active? active-occurrences))
               (or (nil? last-start-ms)
                   (>= (- agora (long last-start-ms))
                       (long (:intervalo-minimo-ms policy)))))
      (let [cid (pick-catalog-id last-catalog-id draw)
            start agora
            end (+ start (duration-ms cid policy))]
        (when cid
          {:catalog-id cid
           :occurrence-id (occurrence-id (:chat-id policy "chat") cid start)
           :inicio-ms start
           :fim-ms end
           :categoria (category-of cid)
           :status "active"})))))

(defn plan-activation-for-chat
  [chat-id opts]
  (let [base (plan-activation (assoc opts :policy (assoc (or (:policy opts) (policy-from-env))
                                                         :chat-id chat-id)))]
    (when base
      (assoc base :occurrence-id (occurrence-id chat-id (:catalog-id base) (:inicio-ms base))
                  :chat-id chat-id))))

(defn should-end?
  [occurrence agora-ms]
  (and occurrence
       (= "active" (:status occurrence))
       (>= (long agora-ms) (long (:fim-ms occurrence 0)))))

(defn next-slot-after
  "Próximo next-at sugerido após um início (intervalo mínimo da policy)."
  ([start-ms] (next-slot-after start-ms default-policy))
  ([start-ms policy]
   (+ (long start-ms) (long (:intervalo-minimo-ms policy)))))

(defn remaining-ms
  [occurrence agora-ms]
  (max 0 (- (long (:fim-ms occurrence 0)) (long agora-ms))))

(defn format-remaining
  [ms]
  (let [h (quot (long ms) (* 60 60 1000))
        m (quot (mod (long ms) (* 60 60 1000)) (* 60 1000))]
    (cond (pos? h) (str h "h" (when (pos? m) (str " " m "min")))
          (pos? m) (str m "min")
          :else "menos de 1min")))
