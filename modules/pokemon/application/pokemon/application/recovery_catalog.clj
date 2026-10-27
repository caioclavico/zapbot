(ns pokemon.application.recovery-catalog
  "Catálogo offline de recuperação A6.4.

  Carrega um dump autorizado em memória. Nunca abre conexão Cassandra.
  Solicitações de jogador ficam em atom local (reinício exige nova prévia).
  A importação só ocorre com token administrativo distinto do Bearer da bridge."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.migration.export :as export]
            [pokemon.recovery :as recovery]))

(def ^:private request-ttl-ms (* 10 60 1000))

(defn- now-ms [] (System/currentTimeMillis))

(defn- blank? [s] (or (nil? s) (str/blank? (str s))))

(defn load-catalog
  "Lê dump autorizado. Sem path → catálogo vazio (disponibilidade = nenhum).
   import-token opcional: necessário para POST /v1/recovery/import."
  [{:keys [dump-path import-token]}]
  (if (blank? dump-path)
    {:export nil :path nil :loaded-at nil :import-token import-token :requests (atom {})}
    (let [dump (export/load-edn-file dump-path)
          exp (export/from-authorized-offline-dump dump)]
      {:export exp
       :path dump-path
       :loaded-at (now-ms)
       :import-token import-token
       :requests (atom {})})))

(defn available?
  [catalog]
  (some? (:export catalog)))

(defn- prune-requests!
  [requests]
  (let [cutoff (- (now-ms) request-ttl-ms)]
    (swap! requests
           (fn [m]
             (into {}
                   (filter (fn [[_ r]]
                             (and (number? (:created-at r))
                                  (>= (:created-at r) cutoff)))
                           m))))))

(defn discover
  "Prévia pública para o jogador atual (com aliases do dump, se houver)."
  [catalog chat-id player-id]
  (if-not (available? catalog)
    {:catalog-ready false
     :available []
     :identity nil
     :message "Catálogo histórico indisponível. A recuperação aguarda dump autorizado."}
    (let [resolved (recovery/resolve-identity (:export catalog) chat-id player-id)
          available (recovery/discover (:export catalog)
                                       (:chat-id resolved)
                                       (:player-id resolved))]
      {:catalog-ready true
       :identity (dissoc resolved :chat-id :player-id
                         :current-chat-id :current-player-id)
       :alias-applied? (boolean (:alias-applied? resolved))
       :available (mapv recovery/public-preview available)
       :versions (mapv :version available)})))

(defn- request-id
  [chat-id player-id choice created-at]
  (str "rec-req|" chat-id "|" player-id "|" (name choice) "|" created-at))

(defn register-request!
  "Registra seleção do jogador. Não importa. Não ativa slots."
  [catalog {:keys [chat-id player-id choice]}]
  (when-not (available? catalog)
    (throw (err/error :recovery_catalog_unavailable {})))
  (when (or (blank? chat-id) (blank? player-id))
    (throw (err/error :invalid_identity {:chat-id chat-id :player-id player-id})))
  (let [resolved (recovery/resolve-identity (:export catalog) chat-id player-id)
        available (recovery/discover (:export catalog)
                                     (:chat-id resolved)
                                     (:player-id resolved))
        selected (recovery/select available choice)
        created (now-ms)
        token (recovery/confirmation-token (:chat-id resolved) (:player-id resolved) selected)
        rid (request-id chat-id player-id choice created)
        entry {:request-id rid
               :status :pending-player-confirm
               :choice (keyword (str/lower-case (name choice)))
               :current-chat-id chat-id
               :current-player-id player-id
               :historical-chat-id (:chat-id resolved)
               :historical-player-id (:player-id resolved)
               :alias-applied? (boolean (:alias-applied? resolved))
               :selected (mapv recovery/public-preview selected)
               :selected-full selected
               :confirmation-token token
               :created-at created
               :player-confirmed-at nil}]
    (prune-requests! (:requests catalog))
    (swap! (:requests catalog) assoc rid entry)
    (select-keys entry [:request-id :status :choice :selected :confirmation-token
                        :alias-applied? :created-at])))

(defn- find-open-request
  [catalog chat-id player-id rid]
  (prune-requests! (:requests catalog))
  (let [m @(:requests catalog)]
    (if-not (blank? rid)
      (get m rid)
      (->> (vals m)
           (filter #(and (= chat-id (:current-chat-id %))
                         (= player-id (:current-player-id %))
                         (contains? #{:pending-player-confirm :awaiting-admin}
                                    (:status %))))
           (sort-by :created-at >)
           first))))

(defn player-confirm!
  "Confirma intenção do jogador. Não importa saves."
  [catalog {:keys [chat-id player-id request-id confirmation-token]}]
  (let [entry (find-open-request catalog chat-id player-id request-id)]
    (when (nil? entry)
      (throw (err/error :recovery_request_not_found {})))
    (when-not (and (= chat-id (:current-chat-id entry))
                   (= player-id (:current-player-id entry)))
      (throw (err/error :recovery_request_owner_mismatch {})))
    (when (and (not (blank? confirmation-token))
               (not= confirmation-token (:confirmation-token entry)))
      (throw (err/error :recovery_confirmation_mismatch {})))
    (let [updated (assoc entry
                         :status :awaiting-admin
                         :player-confirmed-at (now-ms))]
      (swap! (:requests catalog) assoc (:request-id entry) updated)
      (select-keys updated [:request-id :status :choice :selected
                            :player-confirmed-at]))))

(defn- assert-import-token!
  [catalog provided]
  (let [expected (:import-token catalog)]
    (when (or (blank? expected) (not= expected provided))
      (throw (err/error :recovery_import_unauthorized {})))))

(defn snapshots-for-request
  "Recupera snapshots originais do dump para a seleção confirmada."
  [catalog request]
  (let [exp (:export catalog)
        wanted (set (map :snapshot-id (:selected-full request)))]
    (->> (or (:historical_snapshots exp) [])
         (filter #(contains? wanted (:snapshot-id %)))
         vec)))

(defn admin-import!
  "Importa somente após confirmação do jogador + token administrativo.
   Preserva save ativo; slots recovery-* não são ativados."
  [catalog store {:keys [request-id import-token import-batch-id]}]
  (assert-import-token! catalog import-token)
  (when-not (available? catalog)
    (throw (err/error :recovery_catalog_unavailable {})))
  (let [entry (get @(:requests catalog) request-id)]
    (when (nil? entry)
      (throw (err/error :recovery_request_not_found {:request-id request-id})))
    (when-not (= :awaiting-admin (:status entry))
      (throw (err/error :recovery_request_not_confirmed
                        {:status (:status entry)})))
    (let [snapshots (snapshots-for-request catalog entry)
          batch (or import-batch-id
                    (str "a64|" (:request-id entry)))
          result (recovery/confirm!
                  store snapshots
                  {:chat-id (:historical-chat-id entry)
                   :player-id (:historical-player-id entry)
                   :target-chat-id (:current-chat-id entry)
                   :target-player-id (:current-player-id entry)
                   :selected (:selected-full entry)
                   :token (:confirmation-token entry)
                   :import-batch-id batch})
          done (assoc entry :status :imported :imported-at (now-ms)
                            :import-batch-id batch
                            :import-result (select-keys result [:results :active-save-id]))]
      (swap! (:requests catalog) assoc request-id done)
      {:request-id request-id
       :status :imported
       :import-batch-id batch
       :active-save-id (:active-save-id result)
       :results (:results result)})))

(defn request-status
  [catalog chat-id player-id rid]
  (let [entry (find-open-request catalog chat-id player-id rid)]
    (when entry
      (select-keys entry [:request-id :status :choice :selected
                          :created-at :player-confirmed-at :imported-at]))))
