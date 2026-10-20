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

(defn evaluate-m84-gates
  "Gates M8.4 — endurecimento do protocolo. NÃO promove rollback-recon só com
   proof sintético. cutover-ready permanece false."
  [{:keys [protocol-crash-proven?
           stores-wired?
           production-journal-deployed?
           independent-failure-domain?
           whatsapp-staging-validated?]}]
  (let [recon-ok? (boolean (and protocol-crash-proven?
                                stores-wired?
                                production-journal-deployed?
                                independent-failure-domain?))]
    {:protocol-crash-proven? (boolean protocol-crash-proven?)
     :stores-wired? (boolean stores-wired?)
     :production-journal-deployed? (boolean production-journal-deployed?)
     :independent-failure-domain? (boolean independent-failure-domain?)
     :whatsapp-staging-validated? (boolean whatsapp-staging-validated?)
     :rollback-recon-available? recon-ok?
     :cutover-ready? false
     :exactly-once-claimed? false
     :rpo-zero-claimed? false
     :nota
     (str "Protocolo sintético ≠ integração nos stores. "
          "rollback-recon exige journal operacional em domínio de falha distinto "
          "+ stores wired. cutover-ready exige também WhatsApp staging.")}))

(defn evaluate-m85-gates
  "Gates M8.5 — coins-delta real. Proteção global NÃO concluída.
   rollback-recon / cutover permanecem false."
  [{:keys [synthetic-protocol-ok?
           coins-delta-store-ok?
           coins-delta-recovery-ok?
           bridge-http-ok?
           other-mutations-wired?
           independent-failure-domain?
           production-journal-deployed?
           whatsapp-staging-validated?]}]
  {:synthetic-protocol-ok? (boolean synthetic-protocol-ok?)
   :coins-delta-store-ok? (boolean coins-delta-store-ok?)
   :coins-delta-recovery-ok? (boolean coins-delta-recovery-ok?)
   :bridge-http-ok? (boolean bridge-http-ok?)
   :other-mutations-wired? (boolean other-mutations-wired?)
   :independent-failure-domain? (boolean independent-failure-domain?)
   :production-journal-deployed? (boolean production-journal-deployed?)
   :whatsapp-staging-validated? (boolean whatsapp-staging-validated?)
   :global-protection-complete? false
   :rollback-recon-available? false
   :cutover-ready? false
   :exactly-once-claimed? false
   :rpo-zero-claimed? false
   :nota
   (str "Coins-delta wired ≠ proteção global. "
        "rollback-recon exige domínio de falha independente + journal operacional "
        "+ mais mutações. cutover-ready exige WhatsApp staging.")})

(defn evaluate-m86-gates
  "Gates M8.6 — coins + rank score. Proteção global ainda incompleta."
  [{:keys [coins-delta-ok?
           rank-score-ok?
           rank-recovery-ok?
           bridge-rank-ok?
           required-strict-policy-ok?
           other-mutations-wired?
           independent-failure-domain?
           production-journal-deployed?
           whatsapp-staging-validated?]}]
  {:coins-delta-ok? (boolean coins-delta-ok?)
   :rank-score-ok? (boolean rank-score-ok?)
   :rank-recovery-ok? (boolean rank-recovery-ok?)
   :bridge-rank-ok? (boolean bridge-rank-ok?)
   :required-strict-policy-ok? (boolean required-strict-policy-ok?)
   :other-mutations-wired? (boolean other-mutations-wired?)
   :independent-failure-domain? (boolean independent-failure-domain?)
   :production-journal-deployed? (boolean production-journal-deployed?)
   :whatsapp-staging-validated? (boolean whatsapp-staging-validated?)
   :global-protection-complete? false
   :rollback-recon-available? false
   :cutover-ready? false
   :exactly-once-claimed? false
   :rpo-zero-claimed? false
   :nota
   (str "Coins + rank score wired ≠ proteção global. "
        "bloqueio/admins/enquetes/quiz/lembretes/bugs/participantes e demais "
        "Pokémon ainda descobertos. Domínio de falha independente pendente.")})

(defn evaluate-m87-gates
  "Gates M8.7 — coins + rank + blocks/admins. Proteção global incompleta."
  [{:keys [coins-delta-ok?
           rank-score-ok?
           blocks-admins-ok?
           blocks-admins-recovery-ok?
           opposing-ops-order-ok?
           bridge-blocks-ok?
           other-mutations-wired?
           independent-failure-domain?
           production-journal-deployed?
           whatsapp-staging-validated?]}]
  {:coins-delta-ok? (boolean coins-delta-ok?)
   :rank-score-ok? (boolean rank-score-ok?)
   :blocks-admins-ok? (boolean blocks-admins-ok?)
   :blocks-admins-recovery-ok? (boolean blocks-admins-recovery-ok?)
   :opposing-ops-order-ok? (boolean opposing-ops-order-ok?)
   :bridge-blocks-ok? (boolean bridge-blocks-ok?)
   :other-mutations-wired? (boolean other-mutations-wired?)
   :independent-failure-domain? (boolean independent-failure-domain?)
   :production-journal-deployed? (boolean production-journal-deployed?)
   :whatsapp-staging-validated? (boolean whatsapp-staging-validated?)
   :global-protection-complete? false
   :rollback-recon-available? false
   :cutover-ready? false
   :exactly-once-claimed? false
   :rpo-zero-claimed? false
   :nota
   (str "Cobertura parcial (coins/rank/blocks/admins). "
        "Enquetes/quiz/lembretes/bugs/participantes e resto Pokémon descobertos.")})

(defn evaluate-m90-gates
  "Gates M9.0 — preparação de implantação na VM odisseu-core.
   Não promove cutover; produção odisseu-zapbot permanece intocada."
  [{:keys [compose-ready?
           volumes-isolated?
           bridge-loopback-only?
           session-path-isolated?
           journal-volume-planned?
           backup-scripts-ready?
           whatsapp-test-isolation-documented?
           memory-budget-documented?
           remote-deploy-executed?
           production-vm-touched?]}]
  {:compose-ready? (boolean compose-ready?)
   :volumes-isolated? (boolean volumes-isolated?)
   :bridge-loopback-only? (boolean bridge-loopback-only?)
   :session-path-isolated? (boolean session-path-isolated?)
   :journal-volume-planned? (boolean journal-volume-planned?)
   :backup-scripts-ready? (boolean backup-scripts-ready?)
   :whatsapp-test-isolation-documented? (boolean whatsapp-test-isolation-documented?)
   :memory-budget-documented? (boolean memory-budget-documented?)
   :remote-deploy-executed? (boolean remote-deploy-executed?)
   :production-vm-touched? (boolean production-vm-touched?)
   :global-protection-complete? false
   :rollback-recon-available? false
   :cutover-ready? false
   :exactly-once-claimed? false
   :rpo-zero-claimed? false
   :nota
   (str "M9.0 prepara odisseu-core (Compose/volumes/backups/docs). "
        "Deploy remoto e WhatsApp READY na VM exigem autorização explícita. "
        "cutover-ready? permanece false.")})

(defn evaluate-m91-gates
  "Gates M9.1 — pré-deploy JVM/Datomic only na odisseu-core."
  [{:keys [health-unauthenticated-ok?
           curl-in-jvm-image?
           jvm-only-default?
           node-profile-gated?
           seed-reset-default-false?
           compose-config-ok?
           local-persistence-ok?
           journal-same-disk-honest?
           remote-jvm-deployed?
           whatsapp-started?
           production-vm-touched?]}]
  {:health-unauthenticated-ok? (boolean health-unauthenticated-ok?)
   :curl-in-jvm-image? (boolean curl-in-jvm-image?)
   :jvm-only-default? (boolean jvm-only-default?)
   :node-profile-gated? (boolean node-profile-gated?)
   :seed-reset-default-false? (boolean seed-reset-default-false?)
   :compose-config-ok? (boolean compose-config-ok?)
   :local-persistence-ok? (boolean local-persistence-ok?)
   :journal-same-disk-honest? (boolean journal-same-disk-honest?)
   :remote-jvm-deployed? (boolean remote-jvm-deployed?)
   :whatsapp-started? (boolean whatsapp-started?)
   :production-vm-touched? (boolean production-vm-touched?)
   :independent-failure-domain? false
   :global-protection-complete? false
   :rollback-recon-available? false
   :cutover-ready? false
   :exactly-once-claimed? false
   :rpo-zero-claimed? false
   :nota
   (str "M9.1: JVM/Datomic preparada; Node gated por profile. "
        "Journal/Datomic no mesmo disco ⇒ sem domínio de falha físico. "
        "cutover-ready?=false até autorização e evidências futuras.")})

(defn production-session-path?
  "Espelho JVM do gate CLJS: path de produção (.wwebjs_auth / /app/.wwebjs_auth).
   Sufixos _core/_staging são permitidos."
  [p]
  (let [s (clojure.string/trim (str (or p "")))]
    (or (= s ".wwebjs_auth")
        (= s "/app/.wwebjs_auth")
        (boolean (re-find #"(^|/)\.wwebjs_auth$" s)))))

(defn evaluate-m94-gates
  "Gates M9.4 — descoberta de grupos pós-READY + path sessão core."
  [{:keys [compose-session-path-ok?
           getchats-retry-implemented?
           store-fallback-implemented?
           phone-redacted?
           bootstrap-no-commands?
           session-preserved?
           remote-deploy-executed?
           production-vm-touched?]}]
  {:compose-session-path-ok? (boolean compose-session-path-ok?)
   :getchats-retry-implemented? (boolean getchats-retry-implemented?)
   :store-fallback-implemented? (boolean store-fallback-implemented?)
   :phone-redacted? (boolean phone-redacted?)
   :bootstrap-no-commands? (boolean bootstrap-no-commands?)
   :session-preserved? (boolean session-preserved?)
   :remote-deploy-executed? (boolean remote-deploy-executed?)
   :production-vm-touched? (boolean production-vm-touched?)
   :global-protection-complete? false
   :rollback-recon-available? false
   :cutover-ready? false
   :exactly-once-claimed? false
   :rpo-zero-claimed? false
   :nota
   (str "M9.4: retries/backoff getChats + fallback Store; Compose usa "
        "/app/.wwebjs_auth_core. cutover-ready?=false.")})
