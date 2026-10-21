(ns pokemon.infrastructure.datomic.mission-queries
  "Snapshots consistentes e inicialização CAS, sem progresso/recompensas."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.model :as model]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.xp :as xp])
  (:import [java.time Instant]))

(defn- state [save attr]
  (if-let [value (get save attr)] (edn/read-string value) {}))

(defn trainer-level [save]
  ;; Mesmo perfil usado pela bridge; considera componentes e XP legado.
  (:nivel (xp/progresso-treinador
           (max (long (:save/trainer-xp save 0))
                (xp/xp-treinador-total
                 (into {} (for [k [:vitorias-treinador :pe-ginasios :pe-raids :xp-insignias :xp-missoes]]
                            [k (get save (keyword "save" (name k)) 0)])))))))

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

(defn- view [ctx chat player {:keys [instant timezone]}]
  (let [save (:save ctx)]
    (merge (catalog/snapshot (state save :save/missoes-diarias-edn)
                             (state save :save/missoes-semanais-edn)
                             (state save :save/missoes-eventos-edn)
                             (trainer-level save) instant timezone)
           {:chat-id chat :player-id player :save-id (:slot ctx)
            :progress-integration-enabled true
            :progress-events ["wild-defeated" "wild-captured" "pvp-won" "gym-won"]
            :unavailable-progress-events ["gift-sent"]})))

(defn read-missions [conn chat player slot opts]
  (view (context (d/db conn) chat player slot) chat player (options opts)))

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
          (view ctx chat-id player-id opts)
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
