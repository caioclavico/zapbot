(ns pokemon.migration.import
  "Importa IR → Datomic Local descartável.
  Não chama ensure-trainer! — cria player/saves só no caminho de importação.

  Joy (M5.5): pokemon com location=enfermaria viram :enfermaria-bed/*
  (fonte operacional). Não ficam na coleção ativa; pronto-em/UUID preservados.
  Reimport do mesmo batch é idempotente; leito conflitante aborta."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.migration.gates :as gates]
            [pokemon.migration.transform :as transform]))

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

(defn- location-str
  [poke]
  (let [loc (or (:location poke) :equipe)]
    (name (if (keyword? loc) loc (keyword (str loc))))))

(defn- enfermaria?
  [poke]
  (= "enfermaria" (location-str poke)))

(defn- import-bed-id
  "Bed-id determinístico = UUID do Pokémon (sem agora; reimport estável)."
  [id-pokemon]
  (str id-pokemon))

(defn- bed-registro
  "Payload enxuto de :enfermaria-bed/pokemon-edn (limite Datomic Local ~4096).
   origin/legado vão em attrs dedicados do leito."
  [poke]
  (cond-> {:id-pokemon (:id-pokemon poke)
           :species (or (:species poke) (:nome poke))
           :nome (or (:nome poke) (:species poke))
           :shiny (boolean (:shiny poke))
           :nivel (long (or (:nivel poke) 1))
           :xp-desde-nivel (long (or (:xp-desde-nivel poke) 0))
           :hp (long (:hp poke 0))
           :hp-atual (long (:hp-atual poke 0))
           :amizade (long (:amizade poke 70))
           :ataque (long (:ataque poke 0))
           :defesa (long (:defesa poke 0))
           :atq-esp (long (:atq-esp poke 0))
           :def-esp (long (:def-esp poke 0))
           :veloc (long (:veloc poke 0))
           :raridade (or (:raridade poke) "comum")
           :golpes (vec (or (:golpes poke) []))
           :tipos (vec (or (:tipos poke) []))
           :golpes-removidos (vec (or (:golpes-removidos poke) []))
           :collection-index (long (or (:collection-index poke) -1))}
    (:status poke) (assoc :status (:status poke))
    (:habilidade poke) (assoc :habilidade (:habilidade poke))
    (:imagem poke) (assoc :imagem (:imagem poke))
    (:imagem-shiny poke) (assoc :imagem-shiny (:imagem-shiny poke))
    (:item poke) (assoc :item (:item poke))
    (number? (:altura poke)) (assoc :altura (:altura poke))
    (number? (:peso poke)) (assoc :peso (:peso poke))
    (number? (:taxa-captura poke)) (assoc :taxa-captura (:taxa-captura poke))
    (seq (:historico-golpes poke)) (assoc :historico-golpes (:historico-golpes poke))
    (or (seq (:golpes-oferecidos poke)) (seq (:ofertas-golpes poke)))
    (assoc :golpes-oferecidos (or (:golpes-oferecidos poke) (:ofertas-golpes poke)))
    (:ivs poke) (assoc :ivs (:ivs poke))))

(defn- assert-joy-bed-compatible!
  "Se o leito já existe, exige mesmo id-pokemon e pronto-em; senão :import_joy_bed_conflict."
  [db sk poke]
  (let [id-poke (:id-pokemon poke)
        bed-id (import-bed-id id-poke)
        k (id/enfermaria-bed-key sk bed-id)
        e (d/pull db '[*] [:enfermaria-bed/key k])]
    (when (:db/id e)
      (let [stored-pronto (long (:enfermaria-bed/pronto-em e 0))
            stored-poke (try
                          (edn/read-string {:readers *data-readers*}
                                           (:enfermaria-bed/pokemon-edn e))
                          (catch Exception _ {}))
            stored-id (:id-pokemon stored-poke)
            want-pronto (long (:pronto-em poke))]
        (when (or (not= stored-pronto want-pronto)
                  (not= (str stored-id) (str id-poke)))
          (throw (err/error :import_joy_bed_conflict
                            {:save-key sk
                             :bed-id bed-id
                             :expected {:id-pokemon id-poke :pronto-em want-pronto}
                             :actual {:id-pokemon stored-id :pronto-em stored-pronto}})))))))

(defn- enfermaria-bed-entity
  "Materializa leito Joy a partir do IR. Não regenera UUID nem recalcula pronto-em.
   origin/legado em attrs separados (limite de string do Datomic Local)."
  [sk poke]
  (let [id-poke (:id-pokemon poke)
        idx (:collection-index poke)
        _ (when (str/blank? (str id-poke))
            (throw (err/error :import_missing_id_pokemon
                              {:collection-index idx :species (:species poke)})))
        _ (when-not (some? (:pronto-em poke))
            (throw (err/error :import_joy_missing_pronto_em
                              {:id-pokemon id-poke :species (:species poke)})))
        bed-id (import-bed-id id-poke)
        pronto (long (:pronto-em poke))
        origin (or (:origin-edn poke)
                   (when (:origin poke) (pr-str (:origin poke))))
        legado (when (seq (:legado poke)) (pr-str (:legado poke)))
        edn-str (pr-str (bed-registro poke))
        _ (when (> (count edn-str) 4000)
            (throw (err/error :import_joy_bed_payload_too_large
                              {:id-pokemon id-poke :bytes (count edn-str)})))]
    (cond-> {:enfermaria-bed/key (id/enfermaria-bed-key sk bed-id)
             :enfermaria-bed/save-key sk
             :enfermaria-bed/bed-id bed-id
             :enfermaria-bed/pokemon-edn edn-str
             :enfermaria-bed/pronto-em pronto}
      origin (assoc :enfermaria-bed/origin-edn origin)
      legado (assoc :enfermaria-bed/legado-edn legado))))

(defn- pokemon->entity
  "Persiste attrs suportados + location/legado/origin (equipe/pc).
   Enfermaria não usa este caminho — ver enfermaria-bed-entity.
   Não inventa valores ausentes; não regenera UUID; não limita equipe/coleção."
  [sk poke]
  (let [idx (long (:collection-index poke))
        id-poke (:id-pokemon poke)
        _ (when (str/blank? (str id-poke))
            (throw (err/error :import_missing_id_pokemon
                              {:collection-index idx :species (:species poke)})))
        loc (location-str poke)
        _ (when (= "enfermaria" loc)
            (throw (err/error :import_enfermaria_must_be_bed
                              {:id-pokemon id-poke
                               :hint "Use enfermaria-bed-entity no import."})))
        in-party? (= "equipe" loc)
        poke-key id-poke]
    (cond-> {:pokemon/key poke-key
             :pokemon/id-pokemon id-poke
             :pokemon/save [:save/key sk]
             :pokemon/species (:species poke)
             :pokemon/nickname (or (:nome poke) (:species poke))
             :pokemon/shiny (boolean (:shiny poke))
             :pokemon/xp (long (:xp-desde-nivel poke 0))
             :pokemon/level (long (:nivel poke 1))
             :pokemon/in-party in-party?
             :pokemon/location loc
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
      (contains? poke :status) (assoc :pokemon/status (:status poke))
      (:habilidade poke) (assoc :pokemon/habilidade (:habilidade poke))
      (:imagem poke) (assoc :pokemon/imagem (:imagem poke))
      (:imagem-shiny poke) (assoc :pokemon/imagem-shiny (:imagem-shiny poke))
      (seq (:golpes-removidos poke))
      (assoc :pokemon/golpes-removidos-edn (pr-str (:golpes-removidos poke)))
      (seq (:historico-golpes poke))
      (assoc :pokemon/historico-golpes-edn (pr-str (:historico-golpes poke)))
      (or (seq (:golpes-oferecidos poke)) (seq (:ofertas-golpes poke)))
      (assoc :pokemon/ofertas-golpes-edn
             (pr-str (or (:golpes-oferecidos poke) (:ofertas-golpes poke))))
      (:ivs poke) (assoc :pokemon/ivs-edn (pr-str (:ivs poke)))
      (and (contains? poke :item) (some? (:item poke)))
      (assoc :pokemon/item (str (:item poke)))
      (number? (:altura poke)) (assoc :pokemon/altura (double (:altura poke)))
      (number? (:peso poke)) (assoc :pokemon/peso (double (:peso poke)))
      (number? (:taxa-captura poke)) (assoc :pokemon/taxa-captura (long (:taxa-captura poke)))
      (seq (:legado poke)) (assoc :pokemon/legado-edn (pr-str (:legado poke)))
      (:origin-edn poke) (assoc :pokemon/origin-edn (:origin-edn poke))
      (and (nil? (:origin-edn poke)) (:origin poke))
      (assoc :pokemon/origin-edn (pr-str (:origin poke))))))

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
            already-beds (d/q '[:find (count ?b)
                                :in $ ?sk
                                :where [?b :enfermaria-bed/save-key ?sk]]
                              db sk)
            poke-count (or (ffirst already-pokes) 0)
            bed-count (or (ffirst already-beds) 0)
            all-pokes (:pokemon save-ir)
            active-pokes (filterv (complement enfermaria?) all-pokes)
            joy-pokes (filterv enfermaria? all-pokes)]
        (when (or (pos? poke-count) (pos? bed-count))
          (throw (err/error :import_save_not_empty
                            {:save-key sk
                             :pokemon-count poke-count
                             :joy-bed-count bed-count
                             :hint "Reimporte só com mesmo import-batch-id ou base limpa."})))
        (doseq [jp joy-pokes]
          (assert-joy-bed-compatible! db sk jp))
        (when-not (ports/get-player store chat-id player-id)
          (ports/create-player! store chat-id player-id))
        (when-not (ports/get-save store chat-id player-id slot-id)
          (ports/create-save! store chat-id player-id slot-id))
        (let [poke-entities (mapv #(pokemon->entity sk %) active-pokes)
              bed-entities (mapv #(enfermaria-bed-entity sk %) joy-pokes)
              inv (inventory-tx sk (:inventory save-ir))
              dex (pokedex-tx sk all-pokes)
              provenance (:provenance save-ir)
              source-kind (let [v (or (:kind provenance) "offline-import")]
                            (if (keyword? v) (name v) (str v)))
              source-version (or (some-> (:sourceVersion provenance) str) "unknown")
              original-ids (pr-str (select-keys provenance [:snapshot-id :sourceHash :contentHash]))
              ;; Datomic Local: batches grandes ok; chunk se necessário
              chunks (partition-all 40 poke-entities)
              bed-chunks (partition-all 40 bed-entities)
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
                                 :joy-bed-count (count joy-pokes)
                                 :coins (:coins save-ir)})}
                       [:db/add [:save/key sk] :save/coins (long (:coins save-ir))]
                       [:db/add [:save/key sk] :save/origin source-kind]
                       [:db/add [:save/key sk] :save/origin-player-id (str player-id)]
                       [:db/add [:save/key sk] :save/legacy-version source-version]
                       [:db/add [:save/key sk] :save/importer-version "m6.2-ir-v1"]
                       [:db/add [:save/key sk] :save/import-id op-id]
                       [:db/add [:save/key sk] :save/import-status "imported"]
                       [:db/add [:save/key sk] :save/imported-at (java.util.Date.)]
                       [:db/add [:save/key sk] :save/original-record-ids original-ids]
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
          (doseq [chunk bed-chunks]
            (d/transact conn {:tx-data (vec chunk)}))
          {:status :imported
           :slot-id slot-id
           :operation-id op-id
           :content-hash phash
           :pokemon-count (:pokemon-count save-ir)
           :collection-count (count active-pokes)
           :joy-bed-count (count joy-pokes)})))))

(defn import-ir!
  "Importa IR completa. Recusa se houver quarentena bloqueante sem :allow-quarantine?
   Ativa o slot marcado como canonical-active ao final.
   Com `:require-match-resolved? true`, exige matching resolvido via gates."
  [store ir {:keys [import-batch-id allow-quarantine? match-report require-match-resolved?]
             :or {allow-quarantine? false
                  require-match-resolved? false}}]
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
    (when require-match-resolved?
      (gates/assert-importable! ir match-report))
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

(defn import-selected-saves!
  "Importa somente saves históricos previamente selecionados por um fluxo autorizado.
   O jogador pode já existir: o save atual nunca é sobrescrito e os slots recuperados
   não são ativados. A chamada deve receber snapshots com identidade já validada."
  [store snapshots {:keys [import-batch-id chat-id player-id]}]
  (when (str/blank? import-batch-id)
    (throw (err/error :missing_import_batch_id {})))
  (when-not (and chat-id player-id (seq snapshots))
    (throw (err/error :recovery_selection_invalid
                      {:chat-id chat-id :player-id player-id
                       :snapshot-count (count snapshots)})))
  (let [conn (:conn store)
        snapshots (mapv (fn [snapshot]
                          (let [version (or (:legacy-version snapshot)
                                            (:version snapshot)
                                            (get-in snapshot [:source :legacy-version])
                                            (get-in snapshot [:source :legacyVersion])
                                            (get-in snapshot [:source :sourceVersion]))
                                version (str/lower-case (str version))
                                version (if (str/starts-with? version "v") version (str "v" version))]
                            (assoc snapshot :target-slot (str "recovery-" version))))
                        snapshots)
        save-irs (mapv #(transform/snapshot->save-ir % {}) snapshots)
        ;; Preflight completo antes de qualquer escrita evita criar o primeiro slot
        ;; quando o segundo snapshot é inválido ou colide.
        _ (doseq [save-ir save-irs]
            (when-not (= chat-id (:chat-id save-ir))
              (throw (err/error :recovery_owner_mismatch {:expected chat-id :actual (:chat-id save-ir)})))
            (when-not (= player-id (:player-id save-ir))
              (throw (err/error :recovery_owner_mismatch {:expected player-id :actual (:player-id save-ir)}))))
        _ (doseq [save-ir save-irs]
            (when-let [existing (ports/get-save store chat-id player-id (:slot-id save-ir))]
              (let [op-id (receipt-id import-batch-id (:save-key save-ir))]
                (when-not (ports/get-receipt store op-id)
                  (throw (err/error :recovery_slot_already_exists
                                    {:slot-id (:slot-id save-ir)
                                     :existing (select-keys existing [:origin :legacy-version :import-id])}))))))
        _ (when-not (player-exists? store chat-id player-id)
            (ports/create-player! store chat-id player-id))
        results (mapv #(import-one-save! store conn import-batch-id %) save-irs)]
    {:import-batch-id import-batch-id
     :chat-id chat-id
     :player-id player-id
     :results results
     :active-save-id (ports/active-save-id store chat-id player-id)}))
