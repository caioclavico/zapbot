(ns pokemon.recovery
  "Descoberta e autorização de recuperação histórica.

  Este namespace trabalha apenas com dumps offline já autorizados. Cassandra é
  fonte somente leitura: não há conexão, escrita ou alteração de schema aqui."
  (:require [clojure.string :as str]
            [pokemon.domain.reward :as reward]
            [pokemon.migration.import :as migration-import]
            [pokemon.migration.transform :as transform]))

(def supported-versions #{"v1" "v2"})

(defn- version-of
  [snapshot]
  (some-> (or (:legacy-version snapshot)
              (:version snapshot)
              (get-in snapshot [:source :legacy-version])
              (get-in snapshot [:source :legacyVersion])
              (get-in snapshot [:source :sourceVersion]))
          str
          str/lower-case
          (#(if (re-matches #"v?[12]" %) (str "v" (str/replace % #"^v" "")) %))))

(defn- preview
  [snapshot version]
  (let [save (transform/snapshot->save-ir snapshot {:slot-id (str "recovery-" version)})]
    {:snapshot-id (:snapshot-id snapshot)
     :version version
     :chat-id (:chat-id snapshot)
     :player-id (:player-id snapshot)
     :pokemon-count (:pokemon-count save)
     :pokemon-ids (mapv :id-pokemon (:pokemon save))
     :trainer-level (or (:trainer-level snapshot) (:nivel snapshot))
     :coins (:coins save)
     :created-at (:created-at snapshot)
     :content-hash (:content-hash save)}))

(defn discover
  "Descobre snapshots pertencentes exatamente a chat/player.
   Snapshots sem ID, identidade ou versão explícita ficam indisponíveis."
  [export chat-id player-id]
  (let [snapshots (or (:historical_snapshots export) [])]
    (->> snapshots
         (keep (fn [snapshot]
                 (let [version (version-of snapshot)]
                   (when (and (= chat-id (:chat-id snapshot))
                              (= player-id (:player-id snapshot))
                              (not (str/blank? (:snapshot-id snapshot)))
                              (contains? supported-versions version))
                     (preview snapshot version)))))
         (sort-by (juxt :version :snapshot-id))
         vec)))

(defn select
  "Seleciona :v1, :v2 ou :both sem criar snapshots inexistentes."
  [available choice]
  (let [choice (keyword (str/lower-case (name choice)))
        wanted (case choice
                 :v1 #{"v1"}
                 :v2 #{"v2"}
                 :both #{"v1" "v2"}
                 (throw (ex-info "recovery_selection_invalid" {:choice choice})))
        selected (filterv #(contains? wanted (:version %)) available)]
    (when (empty? selected)
      (throw (ex-info "recovery_save_not_found" {:choice choice
                                                   :available (mapv :version available)})))
    selected))

(defn confirmation-token
  "Token determinístico da prévia; a confirmação deve repetir identidade e hashes."
  [chat-id player-id selected]
  (reward/payload-hash {:kind :historical-recovery
                        :chat-id chat-id
                        :player-id player-id
                        :sources (mapv #(select-keys % [:snapshot-id :version :content-hash]) selected)}))

(defn confirm!
  "Executa importação somente após token da prévia coincidir.
   O caller deve transformar os previews em snapshots originais e fornecer o
   import-batch-id estável; esta função não ativa slots recuperados."
  [store snapshots {:keys [chat-id player-id selected token import-batch-id]}]
  (let [actual (mapv (fn [snapshot]
                       (let [version (version-of snapshot)]
                         (when-not (contains? supported-versions version)
                           (throw (ex-info "recovery_version_unsupported" {:version version})))
                         (preview snapshot version)))
                     snapshots)]
    (when-not (= (mapv #(select-keys % [:snapshot-id :version :content-hash]) selected)
                 (mapv #(select-keys % [:snapshot-id :version :content-hash]) actual))
      (throw (ex-info "recovery_selection_payload_mismatch" {}))))
  (when-not (= token (confirmation-token chat-id player-id selected))
    (throw (ex-info "recovery_confirmation_mismatch" {})))
  (migration-import/import-selected-saves!
   store snapshots {:chat-id chat-id :player-id player-id :import-batch-id import-batch-id}))
