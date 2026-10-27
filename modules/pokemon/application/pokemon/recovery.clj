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

(defn public-preview
  "Resumo seguro para WhatsApp: sem IDs de Pokémon, hashes ou identidades."
  [preview]
  (-> preview
      (select-keys [:version :pokemon-count :trainer-level :coins :created-at])
      (assoc :slot-id (str "recovery-" (:version preview)))))

(defn- alias-entries
  [export]
  (let [raw (or (get-in export [:meta :identity-aliases])
                (get-in export [:meta "identity-aliases"])
                [])]
    (cond
      (map? raw) (mapv (fn [[k v]]
                         (let [parts (str/split (str k) #"\|" 2)]
                           {:current-chat-id (nth parts 0 nil)
                            :current-player-id (nth parts 1 nil)
                            :historical-chat-id (or (:chat-id v) (get v "chat-id") (get v :historical-chat-id))
                            :historical-player-id (or (:player-id v) (get v "player-id")
                                                     (get v :historical-player-id))}))
                       raw)
      (sequential? raw) (mapv (fn [e]
                                {:current-chat-id (or (:current-chat-id e) (get e "current-chat-id"))
                                 :current-player-id (or (:current-player-id e) (get e "current-player-id"))
                                 :historical-chat-id (or (:historical-chat-id e) (get e "historical-chat-id")
                                                         (:chat-id e) (get e "chat-id"))
                                 :historical-player-id (or (:historical-player-id e) (get e "historical-player-id")
                                                           (:player-id e) (get e "player-id"))})
                              raw)
      :else [])))

(defn resolve-identity
  "Resolve identidade histórica a partir do chat/player atuais.

  Igualdade exata primeiro. Alias só se declarado no dump autorizado
  (:meta :identity-aliases). Argumentos de comando nunca entram aqui."
  [export chat-id player-id]
  (let [exact {:chat-id chat-id :player-id player-id :alias-applied? false}
        snapshots (or (:historical_snapshots export) [])
        exact-hit? (some #(and (= chat-id (:chat-id %)) (= player-id (:player-id %))) snapshots)]
    (if exact-hit?
      exact
      (if-let [alias (->> (alias-entries export)
                          (filter #(and (= chat-id (:current-chat-id %))
                                        (= player-id (:current-player-id %))
                                        (string? (:historical-chat-id %))
                                        (string? (:historical-player-id %))
                                        (not (str/blank? (:historical-chat-id %)))
                                        (not (str/blank? (:historical-player-id %)))))
                          first)]
        {:chat-id (:historical-chat-id alias)
         :player-id (:historical-player-id alias)
         :alias-applied? true
         :current-chat-id chat-id
         :current-player-id player-id}
        exact))))

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
                 (:both :ambos) #{"v1" "v2"}
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
   import-batch-id estável; esta função não ativa slots recuperados.

   chat-id/player-id = identidade histórica do snapshot (token/prévia).
   target-chat-id/target-player-id = destino Datomic (jogador atual no WhatsApp).
   Quando o destino difere, o snapshot é remapeado e origin-player-id preserva o histórico."
  [store snapshots {:keys [chat-id player-id selected token import-batch-id
                           target-chat-id target-player-id]}]
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
  (let [dest-chat (or target-chat-id chat-id)
        dest-player (or target-player-id player-id)
        remapped? (or (not= dest-chat chat-id) (not= dest-player player-id))
        snapshots* (if-not remapped?
                     snapshots
                     (mapv #(assoc %
                                   :chat-id dest-chat
                                   :player-id dest-player
                                   :origin-chat-id chat-id
                                   :origin-player-id player-id)
                           snapshots))]
    (migration-import/import-selected-saves!
     store snapshots* {:chat-id dest-chat
                       :player-id dest-player
                       :import-batch-id import-batch-id
                       :origin-player-id (when remapped? player-id)})))
