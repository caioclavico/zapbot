(ns odisseu.cutover.recovery
  "M8.2 — modelo de falhas, manutenção e gates de recuperação pós-cutover.
   Somente sintético. Datomic = SoT após liberação; sem rollback automático p/ Cassandra."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [odisseu.cutover.rollback :as rollback]))

(def failure-catalog
  "Cenários distintos e o que receipts/journals/backups cobrem."
  [{:id :jvm-before-commit
    :desc "Falha JVM antes do commit Datomic"
    :durable-change? false
    :recover-via #{:retry-same-op-id}
    :data-loss-risk :none
    :nota "Retry com mesmo operation-id é seguro (ainda não houve TX)."}
   {:id :commit-http-lost
    :desc "Commit OK, resposta HTTP perdida"
    :durable-change? true
    :recover-via #{:receipt-duplicate-on-retry}
    :data-loss-risk :none
    :nota "Recibo no Datomic; retry → :duplicate sem reaplicar efeito."}
   {:id :node-after-confirm
    :desc "Falha Node após confirmação Datomic"
    :durable-change? true
    :recover-via #{:receipt-duplicate-on-retry :reopen-jvm}
    :data-loss-risk :none
    :nota "SoT intacto; Node reinicia e consulta receipts."}
   {:id :jvm-destroyed-volume-ok
    :desc "Container JVM destruído, volume preservado"
    :durable-change? true
    :recover-via #{:reopen-jvm :single-writer}
    :data-loss-risk :none}
   {:id :volume-unavailable
    :desc "Volume Datomic indisponível"
    :durable-change? :unknown
    :recover-via #{:wait-volume :restore-to-new :maintenance}
    :data-loss-risk :service-outage
    :nota "Escrita bloqueada até volume ou restore."}
   {:id :backup-before-latest-tx
    :desc "Backup anterior às últimas transações confirmadas"
    :durable-change? true
    :recover-via #{:restore-backup :accept-rpo :maintenance}
    :data-loss-risk :confirmed-txs-after-backup
    :reconstructible-without-external-log? false
    :nota "Sem journal externo independente, TXs pós-backup são irrecuperáveis."}
   {:id :volume-corruption
    :desc "Corrupção ou perda do volume"
    :durable-change? true
    :recover-via #{:quarantine-volume :restore-to-new}
    :data-loss-risk :confirmed-txs-after-backup
    :reconstructible-without-external-log? false}
   {:id :restore-failure
    :desc "Falha durante restauração"
    :durable-change? :partial
    :recover-via #{:keep-quarantine :retry-restore-new-dest}
    :data-loss-risk :operational
    :nota "Nunca sobrescrever único remanescente; restaurar em destino novo."}
   {:id :partial-store-failure
    :desc "Falha de apenas Pokémon ou Platform"
    :durable-change? :partial
    :recover-via #{:isolate-store :maintenance-coupled :single-writer-per-storage}
    :data-loss-risk :domain-scoped
    :nota "Não dual-write; não misturar writers; gameplay em manutenção se divergência."}])

(def recovery-strategy
  {:source-of-truth-after-cutover :datomic
   :cassandra-auto-rollback? false
   :priorities
   ["Recuperar Datomic como SoT"
    "Preservar último estado confirmado no volume/backup"
    "Reiniciar Node/JVM sem ops duplicadas (receipts)"
    "Reconciliar receipts vs filas pendentes Node"
    "Um writer por storage"
    "Não reativar pollers antes de validar lembretes"
    "Manutenção de gameplay se divergência não resolvida"]
   :zero-loss-claimed? false
   :nota (str "Não alegar perda zero: TXs confirmadas após o último backup "
              "válido sem registro durável independente não são reconstruíveis.")})

(defn maintenance-path [out-dir]
  (str (io/file out-dir "maintenance.edn")))

(defn enter-maintenance!
  "Coloca gameplay/escrita em manutenção (artefato sintético local)."
  [out-dir reason]
  (.mkdirs (io/file (str out-dir)))
  (let [payload {:maintenance? true
                 :write-enabled? false
                 :pollers-enabled? false
                 :reason (str reason)
                 :at (str (java.time.Instant/now))
                 :synthetic true}]
    (spit (maintenance-path out-dir) (pr-str payload))
    payload))

(defn exit-maintenance!
  "Só libera escrita se divergência resolvida explicitamente."
  [out-dir {:keys [divergence-resolved? operator]}]
  (when-not divergence-resolved?
    (throw (ex-info "maintenance_exit_blocked"
                    {:hint "Exija divergência resolvida antes de liberar escrita."})))
  (let [payload {:maintenance? false
                 :write-enabled? true
                 :pollers-enabled? false ;; pollers só após validação separada
                 :divergence-resolved? true
                 :operator (str operator)
                 :at (str (java.time.Instant/now))}]
    (spit (maintenance-path out-dir) (pr-str payload))
    payload))

(defn maintenance-status
  [out-dir]
  (let [f (io/file (maintenance-path out-dir))]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn compute-rpo
  "RPO observado = idade do backup relativamente à última TX confirmada conhecida.
   Sem inventar perda zero."
  [{:keys [backup-at-ms last-confirmed-tx-at-ms lost-op-ids]}]
  (let [gap (when (and backup-at-ms last-confirmed-tx-at-ms)
              (max 0 (- (long last-confirmed-tx-at-ms) (long backup-at-ms))))]
    {:rpo-ms gap
     :rpo-seconds (when gap (/ gap 1000.0))
     :lost-confirmed-ops (count (or lost-op-ids []))
     :zero-loss? (and (number? gap) (zero? gap) (empty? lost-op-ids))
     :reconstructible? false
     :nota (if (seq lost-op-ids)
             "TXs confirmadas pós-backup perdidas sem log externo — não reconstruíveis."
             "Sem TXs pós-backup conhecidas neste ensaio.")}))

(defn compute-rto
  "RTO observado do ensaio (tempo até restore+validação ou manutenção)."
  [{:keys [failure-at-ms recovered-at-ms]}]
  (let [ms (when (and failure-at-ms recovered-at-ms)
             (max 0 (- (long recovered-at-ms) (long failure-at-ms))))]
    {:rto-ms ms
     :rto-seconds (when ms (/ ms 1000.0))}))

(def recovery-gates-checklist
  [{:id :backup-consistent-validated
    :required true
    :desc "Backup feito com writers ociosos / procedimento consistente"}
   {:id :restore-proven
    :required true
    :desc "Restauração comprovada em destino descartável/novo"}
   {:id :single-writer
    :required true
    :desc "Um writer por storage após recuperação"}
   {:id :safe-reprocess
    :required true
    :desc "Reprocessamento só via receipts (duplicate), sem double-apply"}
   {:id :storage-failure-handled
    :required true
    :desc "Volume indisponível/corrupto → quarantine + restore novo"}
   {:id :no-silent-divergence
    :required true
    :desc "Divergência detectada força manutenção (sem escrita silenciosa)"}
   {:id :rpo-rto-measured
    :required true
    :desc "RPO/RTO medidos no ensaio (não inventados)"}
   {:id :maintenance-procedure
    :required true
    :desc "Manutenção funcional (bloquear cmds/pollers)"}
   {:id :rollback-recon-available
    :required true
    :desc "Reconciliação pós-escrita comprovada SEM perda irrecuperável — senão no-go"}])

(defn evaluate-recovery-gates
  "Avalia evidências. rollback-recon-available exige zero TXs confirmadas
   irrecuperáveis no cenário de restore — se o ensaio perdeu TXs, gate fica false."
  [evidence]
  (let [lost (long (or (:lost-confirmed-ops evidence) 0))
        ;; forçar: se houve perda confirmada sem reconstrução, não aprovar recon
        evidence' (cond-> evidence
                    (and (pos? lost) (not (:external-durable-log? evidence)))
                    (assoc :rollback-recon-available false))
        results
        (mapv (fn [{:keys [id required desc]}]
                {:id id :required required :desc desc
                 :ok? (boolean (get evidence' id))})
              recovery-gates-checklist)
        missing (->> results (filter #(and (:required %) (not (:ok? %)))) (mapv :id))]
    {:go? (empty? missing)
     :missing missing
     :results results
     :lost-confirmed-ops lost
     :zero-loss-proven? (and (zero? lost) (true? (:rollback-recon-available evidence')))
     :rollback-recon-available? (boolean (:rollback-recon-available evidence'))
     :cutover-recovery-ready? false
     :nota (str "Não marcar rollback-recon-available se TXs confirmadas foram perdidas "
                "sem reconstrução possível. Não declarar recuperação sem perda sem prova.")}))

(defn merge-cutover-gates
  "Combina gates M8.0 cutover com evidência M8.2 de recuperação."
  [cutover-evidence recovery-gate-result]
  (let [cut (rollback/evaluate-gates
             (assoc cutover-evidence
                    :rollback-recon-available
                    (boolean (:rollback-recon-available? recovery-gate-result))))]
    {:cutover cut
     :recovery recovery-gate-result
     :cutover-ready? false}))
