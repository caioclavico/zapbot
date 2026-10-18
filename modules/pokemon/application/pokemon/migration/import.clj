(ns pokemon.migration.import
  "Importa IR → Datomic Local descartável.
  Não chama ensure-trainer! — cria player/saves só no caminho de importação."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]))

(defn- receipt-id
  [import-batch-id save-key*]
  (str "m50-import|" import-batch-id "|" save-key*))

(defn- player-exists?
  [store chat-id player-id]
  (some? (ports/get-player store chat-id player-id)))

(defn- assert-no-ensure-trainer-precondition!
  "Falha se o jogador já existir antes do import (ex.: ensure-trainer prévio)."
  [store chat-id player-id]
  (when (player-exists? store chat-id player-id)
    (throw (err/error :import_player_already_exists
                      {:chat-id chat-id
                       :player-id player-id
                       :hint "Não chame ensure-trainer! antes do import; use só o harness."}))))

(defn- pokemon->entity
  [sk poke]
  (let [idx (long (:collection-index poke))
        id-poke (:id-pokemon poke)
        poke-key id-poke]
    (cond-> {:pokemon/key poke-key
             :pokemon/id-pokemon id-poke
             :pokemon/save [:save/key sk]
             :pokemon/species (:species poke)
             :pokemon/nickname (:nome poke)
             :pokemon/shiny (boolean (:shiny poke))
             :pokemon/xp (long (:xp-desde-nivel poke 0))
             :pokemon/level (long (:nivel poke 1))
             :pokemon/in-party true
             :pokemon/collection-index idx
             :pokemon/hp (long (:hp poke 0))
             :pokemon/hp-atual (long (:hp-atual poke 0))
             :pokemon/xp-desde-nivel (long (:xp-desde-nivel poke 0))
             :pokemon/amizade (long (:amizade poke 70))
             :pokemon/ataque (long (:ataque poke 0))
             :pokemon/defesa (long (:defesa poke 0))
             :pokemon/atq-esp (long (:atq-esp poke 0))
             :pokemon/def-esp (long (:def-esp poke 0))
             :pokemon/veloc (long (:veloc poke 0))
             :pokemon/raridade (or (:raridade poke) "comum")
             :pokemon/golpes-edn (pr-str (or (:golpes poke) []))}
      (seq (:tipos poke)) (assoc :pokemon/tipos (vec (:tipos poke)))
      (:status poke) (assoc :pokemon/status (:status poke)))))

(defn- inventory-tx
  [sk items]
  (mapv (fn [{:keys [item-id qty]}]
          {:inventory-item/key (id/inventory-item-key sk item-id)
           :inventory-item/save [:save/key sk]
           :inventory-item/item-id item-id
           :inventory-item/qty (long qty)})
        items))

(defn- pokedex-tx
  [sk pokemon]
  (->> pokemon
       (map :species)
       distinct
       (mapv (fn [sp]
               {:pokedex-entry/key (id/pokedex-entry-key sk sp)
                :pokedex-entry/save [:save/key sk]
                :pokedex-entry/species sp
                :pokedex-entry/seen true
                :pokedex-entry/caught true}))))

(defn- save-payload-hash
  [save-ir]
  (:content-hash save-ir))

(defn- existing-receipt
  [conn op-id]
  (let [e (d/pull (d/db conn) '[*] [:operation-receipt/id op-id])]
    (when (:db/id e) e)))

(defn- import-one-save!
  "Importa um save. Idempotente: mesmo batch+hash → duplicate; hash diferente → erro."
  [store conn import-batch-id save-ir]
  (let [{:keys [chat-id player-id slot-id]} save-ir
        sk (:save-key save-ir)
        op-id (receipt-id import-batch-id sk)
        phash (save-payload-hash save-ir)
        existing (existing-receipt conn op-id)]
    (cond
      (and existing (= phash (:operation-receipt/payload-hash existing)))
      {:status :duplicate
       :slot-id slot-id
       :operation-id op-id
       :content-hash phash}

      (and existing (not= phash (:operation-receipt/payload-hash existing)))
      (throw (err/error :import_receipt_payload_mismatch
                        {:operation-id op-id
                         :stored (:operation-receipt/payload-hash existing)
                         :expected phash}))

      :else
      (let [db (d/db conn)
            save-e (d/pull db '[:save/key] [:save/key sk])
            already-pokes (when (:db/id save-e)
                            (d/q '[:find (count ?p)
                                   :in $ ?sk
                                   :where
                                   [?s :save/key ?sk]
                                   [?p :pokemon/save ?s]]
                                 db sk))
            poke-count (or (ffirst already-pokes) 0)]
        (when (pos? poke-count)
          (throw (err/error :import_save_not_empty
                            {:save-key sk
                             :pokemon-count poke-count
                             :hint "Reimporte só com mesmo import-batch-id ou base limpa."})))
        (when-not (ports/get-player store chat-id player-id)
          (ports/create-player! store chat-id player-id))
        (when-not (ports/get-save store chat-id player-id slot-id)
          (ports/create-save! store chat-id player-id slot-id))
        (let [poke-entities (mapv #(pokemon->entity sk %) (:pokemon save-ir))
              inv (inventory-tx sk (:inventory save-ir))
              dex (pokedex-tx sk (:pokemon save-ir))
              ;; Datomic Local: batches grandes ok; chunk se necessário
              chunks (partition-all 40 poke-entities)
              base-tx [{:operation-receipt/id op-id
                        :operation-receipt/kind "migration-import"
                        :operation-receipt/channel "m5.0"
                        :operation-receipt/payload-hash phash
                        :operation-receipt/player-key (:player-key save-ir)
                        :operation-receipt/save-id slot-id
                        :operation-receipt/summary
                        (pr-str {:snapshot-id (get-in save-ir [:provenance :snapshot-id])
                                 :kind (get-in save-ir [:provenance :kind])
                                 :pokemon-count (:pokemon-count save-ir)
                                 :coins (:coins save-ir)})}
                       [:db/add [:save/key sk] :save/coins (long (:coins save-ir))]
                       [:db/add [:save/key sk] :save/ativo-index (long (:ativo-index save-ir))]
                       [:db/add [:save/key sk] :save/vitorias-treinador (long (:vitorias-treinador save-ir))]
                       [:db/add [:save/key sk] :save/pe-ginasios (long (:pe-ginasios save-ir))]
                       [:db/add [:save/key sk] :save/pe-raids (long (:pe-raids save-ir))]
                       [:db/add [:save/key sk] :save/xp-insignias (long (:xp-insignias save-ir))]
                       [:db/add [:save/key sk] :save/xp-missoes (long (:xp-missoes save-ir))]
                       [:db/add [:save/key sk] :save/ginasios-edn (pr-str (:ginasios save-ir))]
                       [:db/add [:save/key sk] :save/sequencia-capturas (long (:sequencia-capturas save-ir))]
                       [:db/add [:save/key sk] :save/maior-sequencia-capturas
                        (long (:maior-sequencia-capturas save-ir))]
                       [:db/add [:save/key sk] :save/expansoes-pokemon-50
                        (long (:expansoes-pokemon-50 save-ir))]
                       [:db/add [:save/key sk] :save/expansoes-mochila-50
                        (long (:expansoes-mochila-50 save-ir))]
                       [:db/add [:save/key sk] :save/capacidade-mochila-legada
                        (long (:capacidade-mochila-legada save-ir))]]]
          (d/transact conn {:tx-data (into base-tx (concat inv dex))})
          (doseq [chunk chunks]
            (d/transact conn {:tx-data (vec chunk)}))
          {:status :imported
           :slot-id slot-id
           :operation-id op-id
           :content-hash phash
           :pokemon-count (:pokemon-count save-ir)})))))

(defn import-ir!
  "Importa IR completa. Recusa se houver quarentena bloqueante sem :allow-quarantine?
   Ativa o slot marcado como canonical-active ao final."
  [store ir {:keys [import-batch-id allow-quarantine?]
             :or {allow-quarantine? false}}]
  (when (str/blank? import-batch-id)
    (throw (err/error :missing_import_batch_id {})))
  (let [;; Avisos (loja órfã, sourceVersion) vão ao relatório; só bloqueios duros abortam
        hard-block (filter #(contains? #{:treinador-ativo-ausente
                                         :slot-conflito-ativo-historico
                                         :save-duplicado-mesmo-slot}
                                       (:reason %))
                           (:quarantine ir))]
    (when (and (seq hard-block) (not allow-quarantine?))
      (throw (err/error :import_blocked_by_quarantine
                        {:quarantine hard-block})))
    (let [chat (:chat-id ir)
          player (:player-id ir)
          conn (:conn store)
          _ (assert-no-ensure-trainer-precondition! store chat player)
          results (mapv #(import-one-save! store conn import-batch-id %)
                        (:saves ir))
          active-slot (->> (:saves ir)
                           (filter #(= :canonical-active
                                       (get-in % [:provenance :kind])))
                           first
                           :slot-id
                           (#(or % "a")))]
      (when-not (ports/active-save-id store chat player)
        (ports/activate-save! store chat player active-slot))
      (when-let [label (get-in ir [:group :label])]
        (d/transact conn {:tx-data [{:group/chat-id chat
                                     :group/label label}]}))
      {:import-batch-id import-batch-id
       :player-key (:player-key ir)
       :results results
       :active-save-id (ports/active-save-id store chat player)
       :quarantine (:quarantine ir)
       :ir-hash (:ir-hash ir)})))

(defn reimport-same-batch!
  "Segunda passagem: deve retornar duplicate em todos os saves."
  [store ir import-batch-id]
  (let [conn (:conn store)]
    (mapv #(import-one-save! store conn import-batch-id %) (:saves ir))))
