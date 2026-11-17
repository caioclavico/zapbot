(ns pokemon.infrastructure.datomic.mission-queries
  "Snapshots consistentes e inicialização CAS, sem progresso/recompensas."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.errors :as err]
            [pokemon.domain.events :as events]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.model :as model]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.thematic-event-store :as thematic])
  (:import [java.time Instant]))

(defn- state [save attr]
  (if-let [value (get save attr)] (edn/read-string value) {}))

(defn trainer-level [save]
  ;; Nível/resíduo persistidos preservam saves migrados de curvas históricas.
  (let [stored (long (:save/trainer-level save 1))
        total (max (long (:save/trainer-xp save 0))
                   (xp/xp-treinador-total
                    (into {} (for [k [:vitorias-treinador :pe-ginasios :pe-raids :xp-insignias :xp-missoes]]
                               [k (get save (keyword "save" (name k)) 0)]))))]
    (if (some? (:save/trainer-xp-level-progress save))
      stored
      (max stored (:nivel (xp/progresso-treinador-legado total))
           (:nivel (xp/progresso-treinador total))))))

(defn- context [db chat player slot]
  (let [p (d/pull db '[*] [:player/key (id/player-key chat player)])]
    (when-not (:player/key p)
      (throw (err/error :player_not_found {:chat-id chat :player-id player})))
    (let [slot (or slot (:player/active-save-id p))]
      (when-not slot (throw (err/error :no_active_save {})))
      (model/validate-save-slot! slot)
      (let [sk (id/save-key chat player slot)
            save (d/pull db '[*] [:save/key sk])]
        (when-not (:save/key save) (throw (err/error :save_not_found {:save-id slot})))
        {:player p :save save :slot slot :key sk}))))

(defn- options [opts]
  {:instant (or (:instant opts) (Instant/now))
   :timezone (or (:timezone opts) (not-empty (System/getenv "MISSOES_TIMEZONE"))
                 mundo/default-timezone)})

(defn- view [db ctx chat player {:keys [instant timezone]}]
  (let [save (:save ctx)
        agora (.toEpochMilli instant)
        occs (when (events/thematic-events-enabled?)
               (thematic/active-occurrences db chat agora))]
    (merge (catalog/snapshot (state save :save/missoes-diarias-edn)
                             (state save :save/missoes-semanais-edn)
                             (state save :save/missoes-eventos-edn)
                             (trainer-level save) instant timezone occs)
           {:chat-id chat :player-id player :save-id (:slot ctx)
            :progress-integration-enabled true
            :claims-enabled true
            :pending-rewards (mapv (fn [[id qty]] {:item-id id :qty qty})
                                   (sort-by key (state save :save/recompensas-pendentes-edn)))
            :progress-events ["wild-defeated" "wild-captured" "pvp-won" "gym-won" "gift-sent"
                              "evolved" "raid-joined" "raid-won"]
            :unavailable-progress-events []})))

(defn read-missions [conn chat player slot opts]
  (let [db (d/db conn)]
    (view db (context db chat player slot) chat player (options opts))))

(defn prepare! [conn {:keys [chat-id player-id save-id fence-generation]} opts]
  (when (or (nil? save-id) (nil? fence-generation))
    (throw (err/error :missing_writer_context {})))
  (let [opts (options opts)
        {:keys [day week]} (catalog/periods (:instant opts) (:timezone opts))]
    (loop [attempt 0]
      (let [{:keys [player save key] :as ctx} (context (d/db conn) chat-id player-id save-id)
            ds (state save :save/missoes-diarias-edn)
            ws (state save :save/missoes-semanais-edn)
            revision (:save/revision save)
            tx (cond-> []
                 (not= day (get ds "dia"))
                 (conj [:db/add [:save/key key] :save/missoes-diarias-edn
                        (pr-str (catalog/daily-state ds day (trainer-level save)))])
                 (not= week (get ws "semana"))
                 (conj [:db/add [:save/key key] :save/missoes-semanais-edn
                        (pr-str (catalog/weekly-state ws week))]))]
        (when (or (not= save-id (:player/active-save-id player))
                  (not= fence-generation (:player/fence-generation player)))
          (throw (err/error :stale_writer_fence {})))
        (if (empty? tx)
          (view (d/db conn) ctx chat-id player-id opts)
          (let [result (try
                         (d/transact conn
                                     {:tx-data (into tx
                                                     [[:db/cas [:save/key key] :save/revision revision (inc (or revision 0))]
                                                      [:db/cas [:player/key (id/player-key chat-id player-id)]
                                                       :player/fence-generation fence-generation fence-generation]
                                                      [:db/cas [:player/key (id/player-key chat-id player-id)]
                                                       :player/active-save-id save-id save-id]])})
                         :ok
                         (catch Exception ex
                           (if (= :cognitect.anomalies/conflict
                                  (:cognitect.anomalies/category (ex-data ex)))
                             :conflict
                             (throw ex))))]
            (cond
              (= result :ok) (read-missions conn chat-id player-id save-id opts)
              (< attempt 8) (recur (inc attempt))
              :else (throw (err/error :writer_fence_conflict {})))))))))
