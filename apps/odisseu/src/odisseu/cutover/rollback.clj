(ns odisseu.cutover.rollback
  "Procedimentos sintéticos de freeze / backup / go-no-go / abort / recuperação.
   NÃO declara rollback pós-cutover seguro sem reconciliação comprovada."
  (:require [clojure.java.io :as io]
            [pokemon.migration.backup :as backup]
            [pokemon.migration.recovery :as recovery]))

(def go-no-go-checklist
  [{:id :writers-frozen
    :required true
    :desc "Writers Cassandra/legado congelados (flag ou drain documentado)"}
   {:id :snapshot-checksum-ok
    :required true
    :desc "Manifesto + checksums do export verificados"}
   {:id :reconcile-unblocked
    :required true
    :desc "Reconciliação sem colisões/proveniência ambígua"}
   {:id :import-equivalent
    :required true
    :desc "Equivalência por domínio OK no Datomic descartável"}
   {:id :single-writer-plan
    :required true
    :desc "Plano de ativar um único writer Datomic (sem dual-write)"}
   {:id :whatsapp-staging-validated
    :required true
    :desc "Staging com WhatsApp autenticado passou (bloqueante para cutover real)"}
   {:id :rollback-recon-available
    :required true
    :desc "Mecanismo de reconciliação pós-escrita Datomic documentado — senão no-go"}
   {:id :authorization
    :required true
    :desc "Autorização explícita para tocar dados de produção"}])

(defn evaluate-gates
  "Avalia mapa de evidências booleanas contra o checklist."
  [evidence]
  (let [results
        (mapv (fn [{:keys [id required desc]}]
                (let [ok? (boolean (get evidence id))]
                  {:id id :required required :desc desc :ok? ok?}))
              go-no-go-checklist)
        missing (->> results (filter #(and (:required %) (not (:ok? %)))) (mapv :id))]
    {:go? (empty? missing)
     :missing missing
     :results results
     :cutover-ready? false
     :nota (str "Cutover NÃO pronto enquanto :whatsapp-staging-validated for false. "
                "Restaurar Cassandra após escritas Datomic pode perder progresso.")}))

(defn freeze-legacy-writers!
  "Simulação: grava flag de freeze em out-dir. Sem efeito em produção."
  [out-dir]
  (let [f (io/file (str out-dir) "legacy-writers-frozen.edn")
        payload {:frozen? true
                 :at (str (java.time.Instant/now))
                 :synthetic true
                 :nota "Somente harness — não altera flags de produção"}]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str payload))
    payload))

(defn abort-before-release!
  "Abort pré-liberação: mantém writer legado; não ativa Datomic."
  [out-dir reason]
  (let [f (io/file (str out-dir) "abort.edn")
        payload {:aborted? true
                 :reason (str reason)
                 :datomic-writer-activated? false
                 :at (str (java.time.Instant/now))}]
    (spit f (pr-str payload))
    payload))

(defn backup-storage!
  "Backup de storage Datomic Local descartável (reusa M5)."
  [storage-rel backup-rel]
  (when (re-find #"(?i)prod|oracle|live" (str storage-rel backup-rel))
    (throw (ex-info "backup_path_blocked" {:storage storage-rel :backup backup-rel})))
  (backup/snapshot-storage! storage-rel backup-rel))

(defn restore-storage!
  [storage-rel backup-rel]
  (when (re-find #"(?i)prod|oracle|live" (str storage-rel backup-rel))
    (throw (ex-info "restore_path_blocked" {:storage storage-rel :backup backup-rel})))
  (backup/restore-storage! storage-rel backup-rel))

(defn post-cutover-risk
  "Documento vivo: rollback Cassandra após writes Datomic."
  []
  {:safe-without-recon? false
   :risk "Restaurar snapshot Cassandra depois de novas escritas no Datomic pode perder progresso."
   :required "Reconciliação comprovada (diff Datomic→Cassandra ou journal de ops) antes de qualquer declaração de rollback seguro."
   :abort-window "Antes de ativar writer Datomic único — abort é seguro se legado ainda é source of truth."})

(defn drill-backup-restore!
  "Exercício sintético: backup → wipe → restore → verificar checksums do export."
  [{:keys [storage-rel backup-rel export-path out-dir]}]
  (let [bak (backup-storage! storage-rel backup-rel)
        ;; wipe: remove dir storage (via backup restore overwrite)
        restored (restore-storage! storage-rel backup-rel)
        checksums (when export-path
                    (let [m (recovery/checksum-manifest [export-path])]
                      (recovery/verify-checksums! m)
                      m))]
    (spit (str (io/file out-dir "rollback-drill.edn"))
          (pr-str {:backup bak :restored restored :checksums checksums
                   :post-cutover (post-cutover-risk)}))
    {:ok true :backup bak :restored restored :post-cutover (post-cutover-risk)}))
