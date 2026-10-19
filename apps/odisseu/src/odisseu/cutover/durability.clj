(ns odisseu.cutover.durability
  "M8.3 — auditoria de durabilidade, comparação A/B/C e política.
   Protótipo: estratégia C leve (backup + journal independente em arquivos).
   Sem exactly-once entre dois storages."
  (:require [odisseu.cutover.recovery :as recovery]))

(def datomic-local-audit
  {:product "Datomic Local (Client API embutida)"
   :persistence "Arquivos sob storage-dir (resolve-storage-dir)"
   :process-crash "Volume intacto → reopen recupera SoT + receipts"
   :container-crash-volume-ok "Idem reopen"
   :volume-loss "Sem backup/journal externo → perda permanente do estado"
   :active-backup-limits
   "Cópia de diretório com writers ativos NÃO é snapshot atômico; exige quiesce."
   :confirmation-point
   "HTTP/Node só deve confirmar após commit Datomic (receipt gravado)."
   :receipts "operation-receipt (Pokémon) / platform.op (Platform) — idempotência no MESMO volume"
   :partial-loss
   "Perda só de Pokémon ou só Platform → manutenção acoplada; sem dual-write."
   :exactly-once-across-stores? false})

(def strategy-comparison
  [{:id :A-backup-only
    :name "Backup consistente + retenção + restore testado"
    :rpo "Até o intervalo desde o último backup quiescido"
    :rto "Tempo de restore + validação"
    :complexity :low
    :cost :low
    :covers-post-backup-txs? false
    :falhas "TXs confirmadas após backup irrecuperáveis (M8.2)"
    :kafka-needed? false}
   {:id :B-journal-only
    :name "Journal durável independente + replay"
    :rpo "Ops que falharam no append pós-Datomic; ou journal+volume perdidos juntos"
    :rto "Replay + validação"
    :complexity :medium
    :cost :low-medium
    :covers-post-backup-txs? true
    :falhas "Sem baseline (backup), replay desde o zero é pesado; journal corrupto bloqueia"
    :kafka-needed? false
    :nota "Arquivo append-only local basta para protótipo; Kafka não demonstrado necessário."}
   {:id :C-backup-plus-journal
    :name "Backup periódico + journal recuperável (escolhida)"
    :rpo "Ops confirmadas no Datomic cujo append no journal falhou; ou perda simultânea dos dois"
    :rto "Restore backup + replay journal após watermark"
    :complexity :medium
    :cost :low-medium
    :covers-post-backup-txs? true
    :falhas "Dual-write parcial (Datomic OK, journal NOK); corrupção journal; perda conjunta"
    :kafka-needed? false}])

(def chosen-strategy
  {:id :C-backup-plus-journal
   :justification
   (str "Fecha o buraco M8.2 (TXs pós-backup) com journal em path independente, "
        "sem introduzir Kafka/Cassandra. Backup continua obrigatório como baseline. "
        "Não promete RPO zero: append pós-commit pode falhar; perda conjunta ainda é fatal.")
   :protocol
   {:confirm-to-client-after :datomic-commit
    :journal-after :datomic-commit
    :on-journal-append-failure :mark-at-risk-or-maintenance
    :replay :idempotent-by-operation-id
    :exactly-once-claimed? false
    :http-lost :retry-same-op-id-duplicate}})

(def journal-atomicity
  [{:case :datomic-ok-journal-fail
    :client-confirm? true
    :recoverable-after-volume-loss? false
    :action "Marcar at-risk; idealmente manutenção se política estrita"}
   {:case :journal-intent-datomic-fail
    :client-confirm? false
    :recoverable? "Replay da intenção com mesmo op-id (idempotente se parcial)"}
   {:case :both-ok-http-lost
    :client-confirm? "cliente não viu"
    :recoverable? "Retry → receipt duplicate; journal já tem entry"}
   {:case :concurrent-ops
    :ordering "seq monotônico do journal; replay em ordem de seq"
    :isolation "scope chat/player/save no entry; reject cross-scope"}
   {:case :nondeterministic
    :rule "Journal deve guardar resultado materializado; senão :replayable? false"}
   {:case :external-messages
    :rule "Não reenviar WhatsApp no replay automático; side-effect manual"}
   {:case :datomic-and-journal-lost
    :recoverable? false
    :rpo "desde último backup que ainda exista fora do volume"}])

(def operational-policy
  {:backup
   {:frequency "Periódico com quiesce (ex.: diário + pré-manutenção); NÃO usar lab-ms como SLA"
    :destination "Storage independente do volume live (outro disco/objeto)"
    :retention "N versões + checksums"
    :integrity "verify após cada backup e restore de ensaio"}
   :journal
   {:path "Fora do storage-dir Datomic"
    :fsync "Append via write+atomic move; produção deve fsync explícito"
    :retention "≥ retenção de backup + margem"
    :corruption "Manutenção + intervenção humana"}
   :rpo-rto
   {:lab-values-are-not-sla true
    :production-rpo "Definir com stakeholders após política de backup/journal"
    :production-rto "Restore + replay ensaiado periodicamente"}
   :maintenance
   ["Volume Datomic indisponível"
    "Journal append falhando repetidamente"
    "Divergência pós-restore não resolvida"
    "Corrupção de journal"]
   :human-required
   ["Ambos Datomic+journal perdidos"
    "Ops :replayable? false"
    "Side-effects externos (WhatsApp)"
    "Ambiguidade de ordenação/corrupção"]
   :if-rpo-zero-not-viable
   "Aceitar RPO=intervalo de risco (journal lag / backup) e comunicar; não marcar zero-loss."})

(defn evaluate-m83-gates
  "Gates M8.3. cutover-ready permanece false.
   journal-recon-proven? = evidência laboratorial de reconstrução com journal vivo.
   rollback-recon-available? só true se evidência + política operacional aceita
   (não promover só para greenwash)."
  [{:keys [journal-recon-proven?
           dual-loss-still-fatal?
           journal-lag-honest?
           whatsapp-staging-validated?
           production-journal-deployed?]}]
  (let [recon-ok? (boolean (and journal-recon-proven?
                                dual-loss-still-fatal? ; consciência do limite
                                journal-lag-honest?
                                production-journal-deployed?))
        base (recovery/evaluate-recovery-gates
              {:backup-consistent-validated true
               :restore-proven true
               :single-writer true
               :safe-reprocess true
               :storage-failure-handled true
               :no-silent-divergence true
               :rpo-rto-measured true
               :maintenance-procedure true
               :lost-confirmed-ops (if journal-recon-proven? 0 3)
               :external-durable-log? (boolean journal-recon-proven?)
               :rollback-recon-available recon-ok?})]
    (assoc base
           :journal-recon-proven? (boolean journal-recon-proven?)
           :rollback-recon-available? recon-ok?
           :cutover-ready? false
           :whatsapp-staging-validated? (boolean whatsapp-staging-validated?)
           :nota
           (str "Lab pode provar journal-recon. "
                "rollback-recon-available exige journal em produção + limites aceitos. "
                "cutover-ready exige também WhatsApp staging."))))
