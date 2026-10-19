(ns odisseu.cutover.import
  "Importação M8.0 — batchId, recibos, retomada, sem poller/gameplay/WhatsApp.
   Não usa ensure-trainer para preencher buracos de import."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [odisseu.cutover.export :as export]
            [odisseu.platform.domain.lembretes :as lembretes]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [pokemon.migration.import :as poke-import]
            [pokemon.migration.transform :as poke-transform]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.application.trainer :as trainer]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn- sha256 [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str s) StandardCharsets/UTF_8))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn journal-path [out-dir]
  (str (io/file out-dir "import-journal.edn")))

(defn load-journal
  [out-dir]
  (let [f (io/file (journal-path out-dir))]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn save-journal!
  [out-dir journal]
  (.mkdirs (io/file (str out-dir)))
  (spit (journal-path out-dir) (pr-str journal))
  journal)

(defn- pending-lembretes-only
  "Cutover: só importa pending (cancelados/enviados = evidência, não reabrir)."
  [raw]
  (into {}
        (keep (fn [[id info]]
                (let [st (str (or (get info "status") (get info :status) "pending"))]
                  (when (= "pending" st)
                    [id (dissoc info "status" :status)]))))
        (or raw {})))

(defn platform-ir-for-import
  [dump]
  (let [mods (export/canonicalize-modules dump)
        filtered (assoc mods "lembretes"
                        (pending-lembretes-only (get mods "lembretes" {})))]
    (platform-pipe/export-modules {:meta (:meta dump) :modules filtered})))

(defn- stage-done? [journal stage]
  (contains? (set (:completed-stages journal)) stage))

(defn import-platform!
  "Importa platform com recibo de lote. Idempotente no mesmo batchId+hash.
   stages: [:platform] — permite resume se journal marcar etapa concluída."
  [platform-store dump {:keys [import-batch-id out-dir force?]
                        :or {force? false}}]
  (when (str/blank? import-batch-id)
    (throw (ex-info "missing_import_batch_id" {})))
  (let [ir (platform-ir-for-import dump)
        payload-hash (sha256 (pr-str (dissoc ir :meta)))
        receipt-id (str "m80-platform|" import-batch-id)
        journal (or (load-journal out-dir) {:import-batch-id import-batch-id
                                            :completed-stages []
                                            :receipts {}})
        existing (get-in journal [:receipts receipt-id])]
    (cond
      (and existing (= payload-hash (:payload-hash existing))
           (stage-done? journal :platform)
           (not force?))
      (let [snap (or (:imported-snapshot existing)
                     ;; reimport no-op: re-lê store via import-ir! seria mutável;
                     ;; usa snapshot do recibo
                     {})]
        {:status :duplicate
         :receipt-id receipt-id
         :payload-hash payload-hash
         :equivalence (when (seq snap)
                        (platform-pipe/equivalence-report ir snap))
         :journal journal})

      (and existing (not= payload-hash (:payload-hash existing)))
      (throw (ex-info "import_receipt_payload_mismatch"
                      {:receipt-id receipt-id
                       :stored (:payload-hash existing)
                       :actual payload-hash}))

      :else
      (let [imported (platform-pipe/import-ir! platform-store ir)
            eq (platform-pipe/equivalence-report ir imported)
            journal' (-> journal
                         (assoc :import-batch-id import-batch-id)
                         (assoc-in [:receipts receipt-id]
                                   {:payload-hash payload-hash
                                    :at (str (java.time.Instant/now))
                                    :kind "platform-cutover-import"
                                    :imported-snapshot imported})
                         (update :completed-stages
                                 (fn [s] (vec (distinct (conj (or s []) :platform))))))]
        (save-journal! out-dir journal')
        {:status :imported
         :receipt-id receipt-id
         :payload-hash payload-hash
         :ir ir
         :imported imported
         :equivalence eq
         :journal journal'
         :poller-started? false
         :whatsapp-touched? false}))))

(defn resume-platform!
  "Retoma se :platform não estiver no journal; caso contrário duplicate."
  [platform-store dump opts]
  (let [journal (load-journal (:out-dir opts))]
    (if (stage-done? journal :platform)
      (import-platform! platform-store dump opts)
      (import-platform! platform-store dump (assoc opts :force? false)))))

(defn- assert-disposable-pokemon-storage!
  [storage-rel]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str storage-rel))
    (throw (ex-info "forbidden_pokemon_storage" {:storage storage-rel}))))

(defn open-pokemon-store!
  "Abre store descartável (apaga db). Recusa m53/m55-real e produção."
  [storage-rel system db-name]
  (assert-disposable-pokemon-storage! storage-rel)
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        _ (persistence/delete-db! client db-name)
        conn (persistence/ensure-db! client db-name)
        s (poke-store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn reopen-pokemon-store!
  "Reabre JVM/store existente sem delete (simula restart)."
  [storage-rel system db-name]
  (assert-disposable-pokemon-storage! storage-rel)
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (poke-store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn import-pokemon!
  "Import Pokémon via M5 (recibos por save). Sem ensure-trainer de preenchimento."
  [poke-store dump {:keys [import-batch-id out-dir]}]
  (when (str/blank? import-batch-id)
    (throw (ex-info "missing_import_batch_id" {})))
  (let [export-data (export/pokemon-export-from-dump dump)
        ir (poke-transform/transform-export export-data)
        ;; Precondição M5: jogador não existe — bootstrap só cria schema/helpers
        result (poke-import/import-ir! poke-store ir {:import-batch-id import-batch-id})
        reimport (poke-import/reimport-same-batch! poke-store ir import-batch-id)
        journal (or (load-journal out-dir) {:import-batch-id import-batch-id
                                            :completed-stages []
                                            :receipts {}})
        journal' (-> journal
                     (update :completed-stages
                             (fn [s] (vec (distinct (conj (or s []) :pokemon)))))
                     (assoc-in [:receipts (str "m80-pokemon|" import-batch-id)]
                               {:at (str (java.time.Instant/now))
                                :ir-hash (:ir-hash ir)
                                :results (:results result)}))]
    (save-journal! out-dir journal')
    {:status :imported
     :import-batch-id import-batch-id
     :result result
     :reimport-statuses (mapv :status reimport)
     :ir ir
     :ensure-trainer-used-for-fill? false
     :journal journal'}))

(defn interrupt-after-platform!
  "Simula falha após platform: grava journal sem :pokemon."
  [platform-store dump opts]
  (let [r (import-platform! platform-store dump opts)]
    (assoc r :interrupted-before :pokemon)))
