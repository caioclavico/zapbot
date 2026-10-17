(ns zapbot.armazenamento
  "Persistência exclusiva do serviço Pokémon sobre o schema existente.
  Não executa DDL nem migra dados. Cada módulo tem um único writer ativo."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [pokemon-service.shutdown :as shutdown-log]
            ["cassandra-driver" :as cassandra]
            [zapbot.config :as config]))

(def ^:private Client (.-Client cassandra))
(def ^:private tabela-antiga (str config/cassandra-keyspace ".estado"))
(def ^:private tabela (str config/cassandra-keyspace ".estado_particionado"))
(def ^:private marcador-migracao "__migrado__")
(def ^:private particao-valor "__valor__")
(defonce ^:private client (atom nil))
(defonce ^:private canonical-client (atom nil))
(defonce ^:private canonical-trainer-store (atom nil))
(defonce ^:private adventure-hatch-resume (atom nil))
(defonce ^:private canonical-gym-client (atom nil))
(defonce ^:private canonical-gym-store (atom nil))
(defonce ^:private pronto (atom false))
(defonce ^:private hidratando? (atom false))
(defonce ^:private cache (atom {}))
(defonce ^:private confirmados (atom {}))
(defonce ^:private registros (atom {}))
(defonce ^:private modulos (atom #{}))
(defonce ^:private filas-gravacao (atom {}))
(defonce ^:private falhas-gravacao (atom {}))
(defonce ^:private gravacoes-pendentes (atom 0))
(defonce ^:private partes-bloqueadas (atom {}))
(defonce ^:private geracoes-partes (atom {}))
;; Jogadores cuja ativação durável confirmou um save novo, mas a hidratação
;; dos atoms falhou ou ficou ambígua. Peers do mesmo grupo seguem operáveis.
(defonce ^:private contas-bloqueadas-hidratacao (atom {}))
(defonce restauracao-bloqueios (atom {}))
;; Costura de teste: injeta um PlayableSaveActivation falso sem Cassandra.
(defonce ^:private playable-activation-test (atom nil))
;; Leitor opcional de ovos/entregas Adventure para o gate de elegibilidade.
(defonce ^:private save-activity-adventure-reader (atom nil))
(defonce ^:private save-activity-eligibility (atom nil))
;; Callback (cid pid save-id fence-gen) para vincular economia da loja ao save.
(defonce ^:private binder-loja-save (atom nil))
(defonce ^:private estatisticas
  (atom {:readQueries 0 :writeQueries 0 :blockedWrites 0
         :hydratedModules 0 :hydratedPartitions 0 :hydrated false}))

(defn diagnostico []
  (assoc @estatisticas :readOnly config/read-only?))

(defn exigir-escrita! []
  (when config/read-only?
    (swap! estatisticas update :blockedWrites inc)
    (throw (js/Error. "POKEMON_READ_ONLY: escrita/processamento bloqueado."))))

(defn pronto? []
  (let [^js c @client
        ^js estado (when c (.getState c))]
    (and @pronto c (pos? (count (array-seq (.getConnectedHosts estado)))))))

(defn registrar-modulo! [chave]
  (when-not (and (string? chave) (re-matches #"[a-z0-9-]+" chave))
    (throw (js/Error. "Nome de módulo de persistência inválido.")))
  (when (and @pronto (not (contains? @modulos chave)))
    (throw (js/Error. "Registre os módulos antes de iniciar a persistência.")))
  (swap! modulos conj chave)
  nil)

(defn registrar!
  ([chave destino] (registrar! chave destino identity))
  ([chave destino transformar]
   (registrar-modulo! chave)
   (swap! registros assoc chave [destino transformar])
   (when (contains? @cache chave)
     (reset! destino (transformar (get @cache chave))))))

(defn obter [chave] (get @cache chave))

(defn estado-confirmado
  "Snapshot cuja gravação já foi confirmada (LWT/aplicação). Não é o cache
  otimista: fila vazia ou salvar! no-op não atualizam este mapa."
  [chave]
  (get @confirmados chave))

(declare exigir-restauracao-livre! instalar-parte-confirmada!)

(defn exigir-parte-disponivel! [modulo particao]
  (when (= modulo "treinador") (exigir-restauracao-livre! particao))
  (when (get @partes-bloqueadas [modulo particao])
    (throw (ex-info "Persistência incerta; recupere a operação antes de prosseguir."
                    {:code :result-unknown}))))
(defn- json [valor] (js/JSON.stringify (clj->js valor)))
(defn- ler-json [valor] (js->clj (js/JSON.parse valor)))
(def ^:private quorum (.. cassandra -types -consistencies -localQuorum))
(def ^:private serial (.. cassandra -types -consistencies -localSerial))
(defn- partes [valor] (if (map? valor) valor {particao-valor valor}))
(defn- reconstruir [partes-modulo]
  (if (contains? partes-modulo particao-valor)
    (get partes-modulo particao-valor)
    partes-modulo))

(defn- verificar-modulo! [chave]
  (when-not (contains? @modulos chave)
    (throw (js/Error. (str "Persistência não autorizada para módulo: " chave))))
  (when-not @client
    (throw (js/Error. "Cassandra indisponível; comando não foi confirmado."))))

(defn- executar! [^js c consulta parametros & [opcoes]]
  ;; Todos os comandos CQL da aplicação atravessam esta barreira.
  ;; Em validação só aceitamos uma consulta SELECT; DDL e batches falham antes
  ;; de chamar o driver, inclusive quando alguém contorna salvar!/reservar!.
  (let [leitura? (and (re-find #"(?i)^\s*SELECT\s" consulta)
                      (not (str/includes? (str/replace (str/trim consulta) #";$" "") ";")))]
    (when-not leitura? (exigir-escrita!))
    (swap! estatisticas update (if leitura? :readQueries :writeQueries) inc))
  (let [metricas (js/require "../runtime/metrics.cjs")]
    ((.-measure metricas) "cassandra_ms"
     #(.execute c consulta (clj->js parametros)
                (clj->js (merge {:prepare true} opcoes))))))

(defn consultar-treinador-legado! [cid]
  (let [c @client]
  ;; Two keyed SELECTs only. Never register legacy tables with current writers.
  (if-not c
    (p/rejected (js/Error. "Consulta de recuperação indisponível."))
    (p/let [particionado (executar! c
                         (str "SELECT valor FROM " config/cassandra-keyspace
                              ".legacy_estado_particionado WHERE modulo = ? AND particao = ?")
                         ["treinador" cid] {:readTimeout 5000})
            antigo (executar! c
                    (str "SELECT valor FROM " config/cassandra-keyspace
                         ".legacy_estado WHERE chave = ?")
                    ["treinador"] {:readTimeout 5000})]
      {:grupo cid :particionado (mapv #(.-valor %) (array-seq (.-rows particionado)))
       :antigo (mapv #(.-valor %) (array-seq (.-rows antigo)))}))))

(defn- consultar-paginas!
  ([c consulta parametros] (consultar-paginas! c consulta parametros nil []))
  ([c consulta parametros pagina linhas]
   (p/let [^js resultado (executar! c consulta parametros
                               (cond-> {:fetchSize 1000}
                                 pagina (assoc :pageState pagina)))]
     (let [acumuladas (into linhas (array-seq (.-rows resultado)))]
       (if-let [proxima (.-pageState resultado)]
         (consultar-paginas! c consulta parametros proxima acumuladas)
         acumuladas)))))

(defn- gravar-parte! [c modulo particao valor]
  (executar! c (str "INSERT INTO " tabela " (modulo, particao, valor) VALUES (?, ?, ?)")
             [modulo (str particao) (json valor)]))

(defn- apagar-parte! [c modulo particao]
  (executar! c (str "DELETE FROM " tabela " WHERE modulo = ? AND particao = ?")
             [modulo (str particao)]))

(def ^:private ausente ::ausente)
(def ^:private transition-locks "pokemon-save-transition-locks")
(defn- lock-partition [cid pid]
  (.hash (js/require "../runtime/restore-protocol.cjs")
         (clj->js [cid "account" pid])))
(defn- lock-resource-hash [pid]
  (.hash (js/require "../runtime/restore-protocol.cjs")
         (clj->js {"type" "account" "id" pid})))

(defn- exigir-locks-duraveis-livres! [c cid pids]
  (p/all
   (for [pid pids]
     (p/let [^js resultado (executar! c
                             (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                             [transition-locks (lock-partition cid pid)]
                             {:consistency serial})
             ^js row (.first resultado)
             lock (when (and row (.-valor row)) (ler-json (.-valor row)))
             owner (get lock "owner")]
       (when (and row
                  (not (and (map? lock)
                            (= 1 (get lock "schemaVersion"))
                            (integer? (get lock "generation"))
                            (not (neg? (get lock "generation")))
                            (= (lock-resource-hash pid) (get lock "resourceHash"))
                            (or (nil? owner) (string? owner))))
         (throw (ex-info "Registro de bloqueio durável inválido; gravação recusada."
                         {:code :save-transition-pending})))
       (when (and row owner)
         (throw (ex-info "Conta protegida por uma operação durável de saves."
                         {:code :save-transition-pending}))))))))

(defn- deltas-contas [antes depois]
  (let [ids (into #{} (concat (keys antes) (keys depois)))]
    (into {}
          (keep (fn [pid]
                  (let [a (get antes pid ausente) d (get depois pid ausente)]
                    (when (not= a d) [pid [a d]])))
                ids))))

(defn- gravar-treinador-grupo! [c cid antes depois]
  (if config/canonical-trainer-enabled?
    (let [store @canonical-trainer-store]
      (when-not store (throw (ex-info "Armazenamento canônico indisponível." {:code :canonical-trainer-not-ready})))
      ;; This is a read-side admission check, not the fence itself: the
      ;; canonical per-player CAS below remains the write guard.  It prevents
      ;; ordinary runtime writers from starting once a durable transition has
      ;; reserved the account.  Certification stays false until the fence is
      ;; represented atomically in every final domain write.
      (-> (p/let [_ (exigir-locks-duraveis-livres! c cid (keys (deltas-contas antes depois)))
                  confirmado (.saveGroup store cid (clj->js antes) (clj->js depois))]
            (js->clj confirmado))
          (p/catch (fn [erro]
                     ;; Preserve underscore codes from the JS store so callers
                     ;; like fence-entrega-adiavel? keep matching.
                     (let [bruto (or (some-> erro .-code)
                                     (:code (ex-data erro))
                                     (some-> erro .-cause .-code)
                                     (:code (ex-data (.-cause erro)))
                                     "canonical_trainer_result_unknown")
                           texto (-> (if (keyword? bruto) (name bruto) (str bruto))
                                     (str/replace "-" "_"))
                           codigo (keyword texto)]
                       (throw (ex-info "Gravação canônica recusada; necessário reconciliar."
                                       {:code codigo}
                                       erro)))))))
    (let [adapter (js/require "../runtime/trainer-state-store.cjs")
          executor (fn [sql parametros opcoes]
                     (let [op (js->clj opcoes :keywordize-keys true)
                           consistencia (case (:consistency op)
                                          "SERIAL" serial
                                          "QUORUM" quorum
                                          quorum)]
                       (executar! c sql (js->clj parametros)
                                  {:consistency consistencia :serialConsistency serial})))
          operacao (.saveGroup adapter executor tabela cid (clj->js antes) (clj->js depois))]
      (-> (p/then operacao js->clj)
          (p/catch (fn [erro]
                     (p/rejected
                      (ex-info "Gravação CAS do treinador recusada ou incerta."
                               {:code (keyword (or (.-code erro) "result-unknown"))} erro))))))))

(defn- ler-grupo-treinador! [c cid]
  (if config/canonical-trainer-enabled?
    (let [store @canonical-trainer-store]
      (when-not store (throw (ex-info "Armazenamento canônico indisponível." {:code :canonical-trainer-not-ready})))
      (p/then (.readGroup store cid) js->clj))
    (p/let [^js resultado (executar! c
                             (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                             ["treinador" cid] {:consistency serial})
            ^js row (.first resultado)]
      (if (and row (.-valor row)) (ler-json (.-valor row)) {}))))

(defn- exigir-jogador-hidratacao-livre! [cid pid]
  (when-let [erro (get @contas-bloqueadas-hidratacao [cid pid])]
    (throw (ex-info "Hidratação de save pendente; jogador bloqueado até reconciliação."
                    {:code :playable-save-hydration-blocked :chat cid :player pid}
                    erro))))

(defn configurar-binder-loja-save!
  "Registra o vínculo loja↔save sem importar zapbot.pokemon.loja neste ns."
  [binder!]
  (reset! binder-loja-save binder!))

(defn configurar-leitor-aventura-elegibilidade!
  "Registra leitor de ovos/entregas Adventure para o gate de ativação."
  [leitura!]
  (reset! save-activity-adventure-reader leitura!))

(defn- gate-atividade-comando
  "Durante uma reserva de transição, recusa nova atividade do jogador. Não é
  fencing atômico por si: só consulta lock/transitionOwner duráveis."
  [cid pid]
  (when (and config/save-transitions-enabled?
             (string? pid) (not (str/blank? pid)))
    (when-let [^js gate @save-activity-eligibility]
      (when-let [^js coordinator @save-transition-coordinator]
        (.assertActivityMayStart gate #js {:chat cid :player pid
                                           :coordinator coordinator})))))

(defn com-contexto-writer-treinador!
  "Captura o fence antes do trabalho de domínio. Promises e timers herdam o
  contexto original; reentradas de callbacks não obtêm um fence novo. Writers
  fora deste contexto são recusados pelo armazenamento canônico. Um comando
  novo do jogador reservado é recusado; os demais jogadores do grupo seguem."
  ([cid acao] (com-contexto-writer-treinador! cid acao false nil))
  ([cid acao novo-comando?] (com-contexto-writer-treinador! cid acao novo-comando? nil))
  ([cid acao novo-comando? pid]
   (when (and (string? pid) (not (str/blank? pid)))
     (exigir-jogador-hidratacao-livre! cid pid))
   (let [rodar (fn []
                 (if-not config/canonical-trainer-enabled?
                   (acao)
                   (let [^js store @canonical-trainer-store]
                     (when-not store
                       (throw (ex-info "Writer canônico indisponível."
                                       {:code :canonical-trainer-not-ready})))
                     (.withWriterContext store cid acao
                                         (clj->js (get-in @confirmados ["treinador" cid] {}))
                                         (true? novo-comando?)
                                         pid))))
         gate (when (true? novo-comando?) (gate-atividade-comando cid pid))]
     (if gate (p/then gate (fn [_] (rodar))) (rodar)))))

(defn contexto-writer-jogador
  "Save/fence/reserva do writer atual para o pid, ou nil fora de contexto."
  [pid]
  (when-let [^js store @canonical-trainer-store]
    (when-let [^js scope (.currentWriterContext store)]
      (when-let [^js ctx (aget (.-contexts scope) pid)]
        {:save-id (.-activeSaveId ctx)
         :fence-generation (.-fenceGeneration ctx)
         :transition-owner (or (.-transitionOwner ctx) "")
         :identity-hash (.-identityHash ctx)}))))

(defn loja-lateral-disponivel?
  "True só com modo canônico e store pronto — unit tests de envelope sem store
  seguem o caminho save-bound sem LateralIntent."
  []
  (boolean (and config/canonical-trainer-enabled? @canonical-trainer-store)))

(defn- writer-context->js [ctx]
  #js {:identityHash (:identity-hash ctx)
       :fenceGeneration (:fence-generation ctx)
       :activeSaveId (:save-id ctx)
       :transitionOwner (or (:transition-owner ctx) "")})

(defn- resolver-contexto-loja!
  "Prefere o fence herdado do ALS; hidratação/binder leem a linha canônica."
  [cid pid]
  (if-let [ctx (contexto-writer-jogador pid)]
    (p/resolved ctx)
    (let [^js store @canonical-trainer-store]
      (p/then (.readAccount store cid pid)
              (fn [^js acc]
                (when-not acc
                  (throw (ex-info "Conta canônica ausente para escrita de loja."
                                  {:code :canonical-trainer-missing})))
                (when (and (.-transitionOwner acc) (not= (.-transitionOwner acc) ""))
                  (throw (ex-info "Loja bloqueada durante reserva de transição."
                                  {:code :canonical-trainer-transition-reserved})))
                {:save-id (.-activeSaveId acc)
                 :fence-generation (.-fenceGeneration acc)
                 :transition-owner ""
                 :identity-hash (.-identityHash acc)})))))

(defn gravar-loja-com-intent!
  "Begin → revalidar intent ativo → persistir loja → confirm/reconcile com
  evidência do snapshot confirmado. Sem store canônico, só `escrever!`.
  `escrever!` deve retornar promise e atualizar estado-confirmado."
  [cid pid tipo payload escrever!]
  (if-not (loja-lateral-disponivel?)
    (escrever!)
    (let [^js store @canonical-trainer-store
          helper (js/require "../runtime/loja-lateral-write.cjs")]
      (p/let [ctx (resolver-contexto-loja! cid pid)]
        (.withLojaLateralIntent helper store
                                #js {:chat cid
                                     :player pid
                                     :type tipo
                                     :payload (clj->js payload)
                                     :writerContext (writer-context->js ctx)
                                     :write (fn [_]
                                              (p/then (escrever!)
                                                      (fn [fingerprint]
                                                        (let [fp (if (string? fingerprint)
                                                                   fingerprint
                                                                   (or (:confirmed-fingerprint fingerprint)
                                                                       (:confirmedFingerprint fingerprint)))]
                                                          (when-not (string? fp)
                                                            (throw (ex-info "Evidência durável da loja ausente."
                                                                            {:code :loja-lateral-evidence-missing})))
                                                          #js {:confirmedFingerprint fp}))))})))))

(defn selo-save-atual
  "Congela o fence já capturado. Não lê a conta canônica vigente."
  [pid]
  (when config/canonical-trainer-enabled?
    (let [^js store @canonical-trainer-store]
      (when-not store
        (throw (ex-info "Writer canônico indisponível." {:code :canonical-trainer-not-ready})))
      (js->clj (.freezeSaveBoundTask store pid)))))

(defn executar-com-selo-save!
  "Reinstala um fence persistido. Sem selo no modo canônico o efeito é
  recusado em vez de recapturar a geração do save ativo."
  [cid pid selo acao]
  (if-not config/canonical-trainer-enabled?
    (acao)
    (let [^js store @canonical-trainer-store
          dados (clj->js (or selo {}))]
      (when-not store
        (throw (ex-info "Writer canônico indisponível." {:code :canonical-trainer-not-ready})))
      (when-not selo
        (throw (ex-info "Tarefa de save sem fence persistido; efeito bloqueado."
                        {:code :save-bound-fence-missing})))
      (.runPersistedContext store cid pid dados acao))))

(defn- selo->js [selo]
  (when selo (clj->js (walk/stringify-keys selo))))

(defn adventure-hatch-resume-disponivel? []
  (boolean (and config/canonical-trainer-enabled? @adventure-hatch-resume)))

(defn registrar-nascimento-pendente!
  "Grava o claim imutável de origem antes de uma entrega que possa precisar
  de retomada. Sem selo/canônico é no-op. Não reescreve fence de incubação."
  [cid pid egg-id hatch-id pokemon-id selo]
  (if-not (adventure-hatch-resume-disponivel?)
    (p/resolved nil)
    (let [^js resume @adventure-hatch-resume]
      (when-not selo
        (throw (ex-info "Nascimento canônico sem fence de origem; retomada bloqueada."
                        {:code :adventure-hatch-origin-unproven})))
      (.registerPending resume #js {:chat cid :player pid :eggId egg-id
                                    :hatchId hatch-id :pokemonId pokemon-id
                                    :incubateSeal (selo->js selo)}))))

(defn origem-save-ativa?
  "True somente quando a conta canônica vigente ainda é o save de origem do selo."
  [cid pid selo]
  (if-not (and config/canonical-trainer-enabled? @canonical-trainer-store selo)
    false
    (let [^js store @canonical-trainer-store
          origem (str (or (get selo "activeSaveId") (get selo :activeSaveId) ""))]
      (p/then (.readAccount store cid pid)
              (fn [^js conta]
                (boolean (and conta (= (.-activeSaveId conta) origem)
                              (or (nil? (.-transitionOwner conta))
                                  (= (.-transitionOwner conta) "")))))))))

(defn retomar-nascimento!
  "Retomada auditável após A→B→A. Captura a geração atual só depois de provar
  a origem. `entregar` roda no contexto novo e deve gravar adventure-entregas."
  [cid pid egg-id hatch-id entregar]
  (when-not (adventure-hatch-resume-disponivel?)
    (throw (ex-info "Retomada de nascimento indisponível." {:code :adventure-hatch-resume-unavailable})))
  (let [^js resume @adventure-hatch-resume
        resume-mod (js/require "../runtime/adventure-hatch-resume.cjs")
        op-id (.resumeOperationId resume-mod hatch-id)]
    (.resume resume cid pid egg-id op-id
             #js {:deliver (fn [ctx]
                             (p/then (entregar (js->clj ctx :keywordize-keys true))
                                     clj->js))})))

(defn recuperar-nascimento!
  "Reconcilição após LWT perdido/reinício; não reentrega se o recibo existir."
  [cid pid egg-id hatch-id entregar]
  (when-not (adventure-hatch-resume-disponivel?)
    (throw (ex-info "Retomada de nascimento indisponível." {:code :adventure-hatch-resume-unavailable})))
  (let [^js resume @adventure-hatch-resume
        resume-mod (js/require "../runtime/adventure-hatch-resume.cjs")
        op-id (.resumeOperationId resume-mod hatch-id)
        opts (when entregar
               #js {:deliver (fn [ctx]
                               (p/then (entregar (js->clj ctx :keywordize-keys true))
                                       clj->js))})]
    (.recover resume cid pid egg-id op-id (or opts #js {}))))

(defn marcar-nascimento-entregue!
  "Fecha o claim pendente após entrega confirmada pelo fence de incubação."
  [cid pid egg-id]
  (if-not (adventure-hatch-resume-disponivel?)
    (p/resolved nil)
    (.markHatched ^js @adventure-hatch-resume cid pid egg-id)))

(defn- persistir-treinador-snapshot! [c anterior valor]
  ;; Mantém o schema legado (uma partição JSON por grupo), mas aplica somente
  ;; as contas modificadas sobre a versão SERIAL mais recente da partição.
  ;; Assim, snapshots de outro jogador não removem alterações alheias.
  (let [grupos (into #{} (concat (keys anterior) (keys valor)))]
    (p/then
     (p/all
      (for [cid grupos
            :let [antes (get anterior cid {})
                  depois (get valor cid {})
                  deltas (deltas-contas antes depois)]
            :when (seq deltas)]
        (p/then (gravar-treinador-grupo! c cid antes depois)
                (fn [confirmado] [cid confirmado]))))
     (fn [confirmados-grupos]
       (reduce (fn [resultado [cid grupo]] (assoc resultado cid grupo))
               (reduce (fn [resultado cid]
                         (if (contains? valor cid) resultado (assoc resultado cid {})))
                       valor grupos)
               confirmados-grupos)))))

(defn- operacoes-alteradas! [c chave anterior valor]
  (if (= chave "treinador")
    (persistir-treinador-snapshot! c (or anterior {}) (or valor {}))
    (if (and config/canonical-gyms-enabled?
             (contains? #{"ginasios" "ginasios-estatisticas"} chave))
      (let [store @canonical-gym-store]
        (when-not store (throw (ex-info "Armazenamento canônico de ginásios indisponível."
                                        {:code :canonical-gym-not-ready})))
        (-> (.saveModule store chave (clj->js (or anterior {})) (clj->js (or valor {})))
            (p/then js->clj)))
    (let [antes (partes anterior) depois (partes valor)]
      (p/all
       (concat
        (for [[particao dado] depois :when (not= dado (get antes particao ::ausente))]
          (gravar-parte! c chave particao dado))
        (for [particao (keys antes) :when (not (contains? depois particao))]
          (apagar-parte! c chave particao))))))))

(defn- tentar-operacao! [acao restantes]
  (-> (acao)
      (p/catch
       (fn [erro]
         (if (pos? restantes)
           (p/then (p/create (fn [resolve _] (js/setTimeout resolve 150)))
                   #(tentar-operacao! acao (dec restantes)))
           (p/rejected erro))))))

(defn- snapshot-atual [modulo valor geracoes]
  ;; Uma entrega confirmada pode ocorrer depois da captura de um snapshot de
  ;; outro chat. Esse snapshot não pode sobrescrever a partição recém-confirmada.
  (reduce (fn [dados [[m particao] geracao]]
            (if (and (= m modulo)
                     (or (not= geracao (get geracoes [m particao] 0))
                         (get @partes-bloqueadas [m particao])
                         (get @restauracao-bloqueios particao)))
              (if-let [atual (get-in @confirmados [modulo particao])]
                (assoc dados particao atual)
                (dissoc dados particao))
              dados))
          valor @geracoes-partes))

(defn snapshot-canonico-vigente?
  "Evita que um snapshot capturado por callback seja promovido a uma escrita
  canônica após o atom ter avançado. O CAS protege processos distintos; esta
  comparação protege callbacks atrasados deste processo."
  [treinador-habilitado? ginasio-habilitado? modulo atual snapshot]
  (if (or (and treinador-habilitado? (= "treinador" modulo))
          (and ginasio-habilitado?
               (contains? #{"ginasios" "ginasios-estatisticas"} modulo)))
    (= atual snapshot)
    true))

(defn snapshot-canonical-ginasio-vigente? [habilitado? modulo atual snapshot]
  ;; Compatibilidade para os testes e chamadores de ginásio existentes.
  (snapshot-canonico-vigente? false habilitado? modulo atual snapshot))

(def ^:private modulos-laterais-nao-certificados
  #{"loja" "ginasios" "ginasios-estatisticas" "raids"
    "pokemon-batalhas-ativas" "pokemon-cacadas-ativas"
    "pokemon-propostas-troca" "pokemon-remocoes-pendentes"
    "pokemon-descobertas"})

(defn- admitir-escrita-lateral!
  "Laterais não participam do LWT da conta. Com o modo canônico ativo, a
  reserva do jogador da operação recusa a gravação; sem contexto a escrita
  também falha fechada em vez de cair no snapshot legado."
  [modulo]
  (if (and config/canonical-trainer-enabled? @canonical-trainer-store
           (contains? modulos-laterais-nao-certificados modulo))
    (.assertLateralAdmission @canonical-trainer-store modulo)
    (p/resolved nil)))

(def ^:private recusas-laterais-definitivas
  #{"canonical_trainer_transition_reserved"
    "canonical_trainer_context_required"})

(defn- codigo-falha-gravacao [erro]
  (some-> (or (:code (ex-data erro))
              (some-> erro .-code)
              (:code (ex-data (.-cause erro)))
              (some-> erro .-cause .-code))
          str
          (str/replace "-" "_")))

(defn- reverter-atom-lateral-recusado!
  "swap!/add-watch já mutou o atom antes de salvar! recusar. Sem reverter, a
  memória diverge do Cassandra e a elegibilidade (que lê o durável) pode
  aprovar uma troca com batalha/loja ainda viva no processo."
  [chave]
  (when-let [[destino transformar] (get @registros chave)]
    (let [confirmado (get @confirmados chave {})]
      (reset! destino (transformar confirmado))
      (swap! cache assoc chave confirmado))))

(defn- snapshot-canonico-atual? [modulo valor]
  ;; Domain modules mutate atoms synchronously and may call salvar! from a
  ;; delayed callback. A callback that captured an older whole-module map must
  ;; not become a newer canonical write merely because it reached this queue
  ;; later. Compare against the registered live atom both before enqueue and
  ;; immediately before persistence. The row CAS remains the cross-process
  ;; guard; this check prevents stale in-process callbacks from being promoted.
  (if-let [[destino _] (get @registros modulo)]
    (snapshot-canonico-vigente? config/canonical-trainer-enabled?
                                 config/canonical-gyms-enabled? modulo @destino valor)
    (not (or config/canonical-trainer-enabled? config/canonical-gyms-enabled?))))

(defn salvar!
  "Retorna confirmação real. O cache otimista preserva o serializador de
  combates; falha restaura o último snapshot confirmado se nada mais o alterou."
  [chave valor]
  (if @hidratando?
    ;; Restaurar atoms dispara watches. Hidratação nunca escreve nem migra
    ;; dados; a próxima ação real salvará seu snapshot contra o confirmado.
    (p/resolved nil)
    (if-not (snapshot-canonico-atual? chave valor)
      (do
        (swap! estatisticas update :blockedWrites inc)
        (when-let [[destino _] (get @registros chave)]
          (swap! cache assoc chave @destino))
        (js/console.warn "[PokemonCassandra] callback antigo de ginásio descartado; estado atual preservado.")
        (p/resolved nil))
    (do
      (exigir-escrita!)
      (when (= chave "treinador")
        (doseq [[cid _] @restauracao-bloqueios
                :let [bruto (get-in @confirmados ["treinador" cid] {})
                      transformar (second (get @registros "treinador"))
                      esperado (if transformar (get (transformar {cid bruto}) cid) bruto)]
                :when (not= (get valor cid) esperado)]
          (instalar-parte-confirmada! "treinador" cid (get-in @confirmados ["treinador" cid] {}))
          (throw (ex-info "Snapshot conflitante com restauração; gravação recusada." {:code :restore-pending}))))
      (verificar-modulo! chave)
      (swap! cache assoc chave valor)
      (let [geracoes @geracoes-partes
            anterior (get @filas-gravacao chave (p/resolved nil))
            gravacao (-> anterior
                         (p/then
                          (fn [_]
                            (if-not (snapshot-canonico-atual? chave valor)
                              (do
                                (when-let [[destino _] (get @registros chave)]
                                  (swap! cache assoc chave @destino))
                                nil)
                              (let [efetivo (snapshot-atual chave valor geracoes)
                                    canonical-gym? (and config/canonical-gyms-enabled?
                                                        (contains? #{"ginasios" "ginasios-estatisticas"} chave))
                                    persistir (fn []
                                                (p/then
                                                 (admitir-escrita-lateral! chave)
                                                 (fn [_]
                                                   (cond
                                                     (= chave "treinador")
                                                     (operacoes-alteradas! @client chave
                                                                           (get @confirmados chave {}) efetivo)
                                                     canonical-gym?
                                                     ;; CAS conflicts are not retryable: blindly retrying a stale
                                                     ;; module snapshot could overwrite another runtime's newer row.
                                                     (operacoes-alteradas! @client chave
                                                                           (get @confirmados chave {}) efetivo)
                                                     :else
                                                     (tentar-operacao!
                                                      #(operacoes-alteradas! @client chave
                                                                            (get @confirmados chave {}) efetivo) 2)))))]
                                (p/let [confirmado (persistir)]
                                  (swap! confirmados assoc chave
                                         (if (or (= chave "treinador") canonical-gym?)
                                           confirmado efetivo))
                                  (swap! falhas-gravacao dissoc chave)
                                  nil))))))
            _ (swap! gravacoes-pendentes inc)
            segura (p/finally
                    (p/catch
                     gravacao
                     (fn [erro]
                       (let [codigo (codigo-falha-gravacao erro)
                             recusa-reserva? (and (contains? modulos-laterais-nao-certificados chave)
                                                  (contains? recusas-laterais-definitivas codigo))]
                         (if recusa-reserva?
                           (do (reverter-atom-lateral-recusado! chave)
                               (js/console.warn "[PokemonCassandra] escrita lateral recusada durante reserva:"
                                                chave codigo)
                               nil)
                           (do
                             (swap! falhas-gravacao assoc chave erro)
                             (when (and (= chave "treinador")
                                        (or (contains? #{:result-unknown :trainer-cas-conflict}
                                                       (:code (ex-data erro)))
                                            (and config/canonical-trainer-enabled?
                                                 (some? (re-find #"^canonical-trainer-"
                                                                 (or (some-> (:code (ex-data erro)) name) ""))))))
                               (doseq [cid (into #{} (concat (keys (get @confirmados chave {}))
                                                            (keys valor)))]
                                 (swap! partes-bloqueadas assoc ["treinador" cid] erro)))
                             (when (and config/canonical-gyms-enabled?
                                        (contains? #{"ginasios" "ginasios-estatisticas"} chave))
                               (let [confirmado (get @confirmados chave {})
                                     grupos (into #{} (concat (keys confirmado) (keys valor)))]
                                 (doseq [cid grupos
                                         :when (not= (get confirmado cid) (get valor cid))]
                                   ;; A CAS conflict or unknown result quarantines this
                                   ;; group's whole snapshot until startup rehydrates
                                   ;; the canonical rows. Never retry a stale atom image.
                                   (swap! partes-bloqueadas assoc [chave cid] erro))))
                             (when (identical? valor (get @cache chave))
                               (swap! cache assoc chave (get @confirmados chave {})))
                             (when-not (= :canonical-gym-stale-snapshot (:code (ex-data erro)))
                               (js/console.error "[PokemonCassandra] gravação não confirmada:" chave (.-message erro)))
                             nil)))))
                    #(swap! gravacoes-pendentes dec))]
        (swap! filas-gravacao assoc chave segura)
        gravacao)))))

(defn alterar-ginasio-confirmado!
  "Aplica uma transformação determinística a um registro canônico de ginásio.
  O recibo do evento e o novo estado são confirmados pelo mesmo LWT; repetição
  do mesmo ID retorna o estado já aplicado sem executar a transformação outra vez."
  [modulo cid id operacao-id transformar]
  (when-not (and config/canonical-gyms-enabled?
                 (contains? #{"ginasios" "ginasios-estatisticas"} modulo)
                 (fn? transformar))
    (throw (ex-info "Operação idempotente canônica de ginásio indisponível."
                    {:code :canonical-gym-operation-disabled})))
  (let [store @canonical-gym-store]
    (when-not store
      (throw (ex-info "Armazenamento canônico de ginásios indisponível."
                      {:code :canonical-gym-not-ready})))
    (p/then
     (.updateRecordOnce store cid modulo id operacao-id
                        (fn [estado]
                          (clj->js (transformar (when estado (js->clj estado)))))
                        )
     (fn [registro]
       (let [estado (when (.-state registro) (js->clj (.-state registro)))
             valor (if estado estado {})]
         (swap! confirmados assoc-in [modulo cid id] valor)
         (when-let [[destino _] (get @registros modulo)]
           (swap! destino assoc-in [cid id] valor))
         valor)))))

(defn aplicar-efeitos-batalha-ginasio-canonicos!
  "Executa somente o recorte recuperável de uma batalha de ginásio: HP/status
  do desafiante e desgaste da ocupação. O chamador fornece um plano já
  congelado do resultado da rodada; XP, moedas, itens, estatísticas e troca de
  ocupação não passam por aqui e continuam impedindo a certificação pública."
  [cid pid batalha-id hp-payload desgaste-payload]
  (when-not (and config/canonical-trainer-enabled? config/canonical-gyms-enabled?
                 @canonical-trainer-store @canonical-gym-store)
    (throw (ex-info "Journal canônico de batalha indisponível."
                    {:code :canonical-battle-journal-unavailable})))
  (let [journal-module (js/require "../runtime/gym-battle-journal.cjs")
        effects-module (js/require "../runtime/gym-battle-real-effects.cjs")
        journal (js/Reflect.construct
                 (.-GymBattleJournal journal-module)
                 #js [(.-execute @canonical-trainer-store) config/cassandra-keyspace
                      #js {:writerId "pokemon-service-gym-battle"}])]
    (.finalizeRealGymRound effects-module
                           #js {:journal journal :trainerStore @canonical-trainer-store
                                :gymStore @canonical-gym-store :chat cid :player pid
                                :battleId batalha-id
                                :activeIndex (.-activeIndex hp-payload)
                                :hp (.-hp hp-payload) :status (.-status hp-payload)
                                :gymId (when desgaste-payload (.-targetId desgaste-payload))
                                :wornOccupancy (when desgaste-payload (.-nextState desgaste-payload))})))

(defn finalizar-hp-e-desgaste-ginasio-canonicos!
  "Monta o plano a partir das versões canônicas que serão usadas pelo LWT.
  Não lê atoms como prova de durabilidade e não inclui recompensas ainda não
  coordenadas."
  [cid pid batalha-id indice hp status ginasio-id ocupacao-apos-desgaste]
  (when-not (and @canonical-trainer-store @canonical-gym-store)
    (throw (ex-info "Armazenamento canônico indisponível."
                    {:code :canonical-battle-journal-unavailable})))
  (let [effects-module (js/require "../runtime/gym-battle-real-effects.cjs")]
    (p/let [conta (.readAccount @canonical-trainer-store cid pid)
            ocupacao (when ocupacao-apos-desgaste
                        (.readRecord @canonical-gym-store cid "ginasios" ginasio-id))]
      (when-not conta
        (throw (ex-info "Treinador canônico ausente." {:code :canonical-battle-trainer-missing})))
      (let [hp-payload (.trainerPayload effects-module
                                        #js {:record conta :activeIndex indice :hp hp :status status})
            desgaste-payload (when ocupacao-apos-desgaste
                               (.gymWearPayload effects-module
                                                #js {:record ocupacao :targetId ginasio-id
                                                     :nextState (clj->js ocupacao-apos-desgaste)}))]
        (aplicar-efeitos-batalha-ginasio-canonicos! cid pid batalha-id hp-payload desgaste-payload)))))

(defn aguardar-todas!
  "Espera filas em voo e propaga falhas, conflitos CAS e respostas incertas.
  Fila vazia NÃO prova que uma gravação específica foi confirmada — só que
  nada pendente registrou falha. Use estado-confirmado para verificação."
  []
  (p/let [_ (p/all (vals @filas-gravacao))]
    (when-let [erro (first (concat (vals @falhas-gravacao) (vals @partes-bloqueadas)))]
      (throw erro))))

(def ^:private adventure-schema
  {"adventure_account_binding" {"chat_id" ["text" "partition_key" 0] "player_id" ["text" "clustering" 0]
                                "account_id" ["uuid" "regular" -1] "created_at" ["timestamp" "regular" -1]}
   "adventure_account_state" {"account_id" ["uuid" "partition_key" 0] "version" ["bigint" "regular" -1]
                              "schema_version" ["int" "regular" -1] "updated_at" ["timestamp" "regular" -1]
                              "payload" ["text" "regular" -1]}
   "adventure_sync_receipts" {"account_id" ["uuid" "partition_key" 0] "day_utc" ["date" "partition_key" 1]
                              "batch_id" ["uuid" "clustering" 0] "fingerprint" ["text" "regular" -1]
                              "state_version" ["bigint" "regular" -1] "payload" ["text" "regular" -1]}
   "adventure_hatches_by_month" {"account_id" ["uuid" "partition_key" 0] "month_utc" ["text" "partition_key" 1]
                                 "seq" ["bigint" "clustering" 0] "hatch_id" ["uuid" "regular" -1]
                                 "egg_id" ["uuid" "regular" -1] "pokemon_id" ["uuid" "regular" -1]
                                 "payload" ["text" "regular" -1]}})
(defonce ^:private adventure-schema-client (atom nil))

(defn validar-adventure-schema! []
  (when-not config/adventure-enabled? (throw (ex-info "Adventure Sync desabilitado." {:code :disabled})))
  (if (identical? @adventure-schema-client @client) (p/resolved nil)
    (p/let [^js resultado (executar! @client
                            (str "SELECT table_name, column_name, type, kind, position FROM system_schema.columns "
                                 "WHERE keyspace_name = ? AND table_name IN (?, ?, ?, ?)")
                            (into [config/cassandra-keyspace] (keys adventure-schema)) {:consistency quorum})]
      (let [actual (reduce (fn [m ^js row] (assoc-in m [(.-table_name row) (.-column_name row)]
                                              [(.-type row) (.-kind row) (.-position row)]))
                           {} (array-seq (.-rows resultado)))]
        (doseq [[t colunas] adventure-schema]
          (when-not (and (every? (fn [[coluna tipo]] (= tipo (get-in actual [t coluna]))) colunas)
                         (= (set (for [[c [_ kind _]] colunas :when (not= "regular" kind)] c))
                            (set (for [[c [_ kind _]] (get actual t) :when (not= "regular" kind)] c))))
            (throw (ex-info "Schema Adventure ausente ou incompatível; nenhuma migração automática."
                            {:code :schema-incompatible}))))
        (reset! adventure-schema-client @client)))))

(defn executar-adventure!
  "Somente statements preparados e tabelas aditivas desta fase. Sem DDL."
  [operacao parametros & [pagina]]
  (when-not config/adventure-enabled? (throw (ex-info "Adventure Sync desabilitado." {:code :disabled})))
  (when-not (pronto?) (throw (ex-info "Persistência indisponível." {:code :not-ready})))
  (let [[t sql] (case operacao
                 :binding-read ["account_binding" "SELECT account_id FROM %s WHERE chat_id = ? AND player_id = ?"]
                 :binding-insert ["account_binding" "INSERT INTO %s (chat_id, player_id, account_id, created_at) VALUES (?, ?, ?, ?) IF NOT EXISTS"]
                 :state-read ["account_state" "SELECT version, schema_version, payload FROM %s WHERE account_id = ?"]
                 :state-insert ["account_state" "INSERT INTO %s (account_id, version, schema_version, updated_at, payload) VALUES (?, ?, ?, ?, ?) IF NOT EXISTS"]
                 :state-cas ["account_state" "UPDATE %s SET version = ?, payload = ?, updated_at = ? WHERE account_id = ? IF version = ?"]
                 :state-scan ["account_state" "SELECT version, schema_version, payload FROM %s"]
                 :receipt-read ["sync_receipts" "SELECT fingerprint, payload FROM %s WHERE account_id = ? AND day_utc = ? AND batch_id = ?"]
                 :receipt-write ["sync_receipts" "INSERT INTO %s (account_id, day_utc, batch_id, fingerprint, state_version, payload) VALUES (?, ?, ?, ?, ?, ?) USING TTL 1209600"]
                 :history-write ["hatches_by_month" "INSERT INTO %s (account_id, month_utc, seq, hatch_id, egg_id, pokemon_id, payload) VALUES (?, ?, ?, ?, ?, ?, ?)"]
                 :history-read ["hatches_by_month" "SELECT seq, payload FROM %s WHERE account_id = ? AND month_utc = ? AND seq < ? ORDER BY seq DESC LIMIT 20"]
                 (throw (js/Error. "Consulta Adventure não permitida.")))
        consulta (str/replace sql "%s" (str config/cassandra-keyspace ".adventure_" t))]
    (p/let [_ (validar-adventure-schema!)]
      (executar! @client consulta parametros
                 (cond-> {:consistency (if (contains? #{:state-read :binding-read} operacao) serial quorum)
                          :serialConsistency serial :fetchSize 32}
                   pagina (assoc :pageState pagina))))))

(defn- preservar-contas-bloqueadas-hidratacao
  "Enquanto a hidratação de um jogador falhou, instalações de grupo (ex.: peer
  writing) não podem publicar a conta canônica dele sem validar ponteiro/hash."
  [cid grupo]
  (reduce (fn [acc [[c p] _]]
            (if (not= c cid)
              acc
              (if-let [antiga (get-in @confirmados ["treinador" cid p])]
                (assoc acc p antiga)
                (dissoc acc p))))
          (or grupo {})
          @contas-bloqueadas-hidratacao))

(defn- instalar-parte-confirmada! [modulo particao valor]
  (let [valor (if (= modulo "treinador")
                (preservar-contas-bloqueadas-hidratacao particao valor)
                valor)]
    (swap! cache assoc-in [modulo particao] valor)
    (swap! confirmados assoc-in [modulo particao] valor)
    (when-let [[destino transformar] (get @registros modulo)]
      (swap! destino assoc particao (get (transformar {particao valor}) particao)))
    (swap! geracoes-partes update [modulo particao] (fnil inc 0))
    (swap! partes-bloqueadas dissoc [modulo particao])))

(defn- instalar-conta-treinador-hidratada! [cid pid conta]
  (swap! contas-bloqueadas-hidratacao dissoc [cid pid])
  (let [grupo (assoc (or (get-in @confirmados ["treinador" cid]) {}) pid conta)]
    (reset! hidratando? true)
    (try
      (instalar-parte-confirmada! "treinador" cid grupo)
      (finally (reset! hidratando? false)))))

(defn recuperar-treinador! [cid]
  "Leitura SERIAL resolve Paxos pendente antes de reabrir o chat incerto."
  (p/let [_ (get @filas-gravacao "treinador" (p/resolved nil))
          valor (ler-grupo-treinador! @client cid)]
    (instalar-parte-confirmada! "treinador" cid valor)
    (when-not (some (fn [[[modulo _] _]] (= modulo "treinador")) @partes-bloqueadas)
      (swap! falhas-gravacao dissoc "treinador"))
    valor))

(defn alterar-treinador-confirmado!
  "Entrega+recibo na mesma linha, CAS sem retry cego. Um writer do jogo."
  [cid mudar]
  (exigir-escrita!)
  (exigir-parte-disponivel! "treinador" cid)
  (let [anterior (get @filas-gravacao "treinador" (p/resolved nil))
        trabalho (p/then anterior
                   (fn [_]
                     (when-let [erro (get @falhas-gravacao "treinador")] (throw erro))
                     (swap! geracoes-partes update ["treinador" cid] (fnil inc 0))
                     (swap! partes-bloqueadas assoc ["treinador" cid]
                            (ex-info "Entrega ainda não confirmada." {:code :result-unknown}))
                     (p/let [base (ler-grupo-treinador! @client cid)
                             {:keys [valor resultado]} (mudar base)
                             confirmado (gravar-treinador-grupo! @client cid base valor)]
                       (instalar-parte-confirmada! "treinador" cid confirmado)
                       resultado)))
        _ (swap! gravacoes-pendentes inc)
        segura (p/finally (p/catch trabalho
                           (fn [erro]
                             (let [bruto (or (some-> erro .-code)
                                             (:code (ex-data erro))
                                             (some-> erro .-cause .-code)
                                             (:code (ex-data (.-cause erro))))
                                   codigo (when bruto
                                            (-> (if (keyword? bruto) (name bruto) (str bruto))
                                                (str/replace "-" "_")))
                                   ;; Fence/admission failures are definitive: the LWT did not
                                   ;; apply. Quarantine only uncertain/lost-response outcomes.
                                   definitivo? (contains? #{"canonical_trainer_fence_conflict"
                                                            "canonical_trainer_cas_conflict"
                                                            "canonical_trainer_context_required"
                                                            "canonical_trainer_transition_reserved"
                                                            "canonical_trainer_snapshot_stale"
                                                            "save_transition_pending"
                                                            "save-transition-pending"
                                                            ;; Pre-flight / rejected-before-LWT interrupts
                                                            ;; never applied the write.
                                                            "synthetic_lost_after_claim"}
                                                          codigo)]
                               (if definitivo?
                                 (swap! partes-bloqueadas dissoc ["treinador" cid])
                                 (swap! partes-bloqueadas assoc ["treinador" cid] erro))
                               (js/console.error (js/JSON.stringify
                                                  #js {:event "adventure_delivery_unknown"
                                                       :stage "trainer_cas"
                                                       :code codigo
                                                       :definitive (boolean definitivo?)
                                                       :message (some-> erro .-message str)}))
                               nil)))
                         #(swap! gravacoes-pendentes dec))]
    (swap! filas-gravacao assoc "treinador" segura)
    trabalho))

(defn reservar!
  "Reserva uma partição com LWT para requestId. Não repete LWT com resultado
  incerto. Retorna boolean; o cache recebe o valor confirmado ou já existente."
  [modulo particao valor]
  (exigir-escrita!)
  (when (and config/canonical-gyms-enabled?
             (contains? #{"ginasios" "ginasios-estatisticas"} modulo))
    (throw (ex-info "Reserva legada de ginásio bloqueada; use o writer canônico versionado."
                    {:code :canonical-gym-legacy-write-blocked})))
  (verificar-modulo! modulo)
  (when-not (and (string? particao) (not (contains? #{marcador-migracao particao-valor} particao)))
    (throw (js/Error. "Partição reservada inválida.")))
  (p/let [_ (get @filas-gravacao modulo (p/resolved nil))
          ^js resultado (executar! @client
                               (str "INSERT INTO " tabela
                                    " (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS")
                               [modulo particao (json valor)])]
    (let [aplicado? (.wasApplied resultado)
          ^js row (.first resultado)
          atual (if aplicado? valor (when (.-valor row) (ler-json (.-valor row))))]
      ;; Só atualizar snapshots após resultado confirmado pelo Cassandra.
      (when (some? atual)
        (swap! cache assoc-in [modulo particao] atual)
        (swap! confirmados assoc-in [modulo particao] atual))
      aplicado?)))

(defonce ^:private restauracao-writer (atom #{}))
(defn bloquear-restauracao! [cid operacao]
  (swap! restauracao-bloqueios assoc cid operacao)
  (swap! geracoes-partes update ["treinador" cid] (fnil inc 0)))
(defn liberar-restauracao! [cid operacao]
  (when (= operacao (get @restauracao-bloqueios cid))
    (swap! restauracao-bloqueios dissoc cid)))
(defn exigir-restauracao-livre! [cid]
  (when (get @restauracao-bloqueios cid)
    (throw (ex-info "Restauração pendente; grupo protegido até reconciliação." {:code :restore-pending}))))
(defn restauracao-ler-grupo! [cid]
  (when config/canonical-trainer-enabled?
    (throw (ex-info "Restauração ainda não suporta o armazenamento canônico; operação bloqueada."
                    {:code :canonical-restore-unsupported})))
  (p/then (ler-grupo-treinador! @client cid) clj->js))
(defn restauracao-instalar! [cid valor]
  (instalar-parte-confirmada! "treinador" cid (js->clj valor)))
(defn restauracao-cas-grupo! [cid antes depois]
  (when config/canonical-trainer-enabled?
    (throw (ex-info "Restauração ainda não suporta o armazenamento canônico; operação bloqueada."
                    {:code :canonical-restore-unsupported})))
  (exigir-escrita!)
  (when-not (contains? @restauracao-writer cid)
    (throw (js/Error. "Writer exclusivo de restauração obrigatório.")))
  (let [deltas (deltas-contas (js->clj antes) (js->clj depois))]
   (p/let [_ (exigir-locks-duraveis-livres! @client cid (keys deltas))
           ^js leitura (executar! @client
                         (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                         ["treinador" cid] {:consistency serial})]
    (let [row (.first leitura) bruto (when row (.-valor row))]
      (if (not= (if bruto (ler-json bruto) {}) (js->clj antes)) false
          (p/let [^js gravacao (executar! @client
                                (if bruto
                                  (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?")
                                  (str "INSERT INTO " tabela " (valor, modulo, particao) VALUES (?, ?, ?) IF NOT EXISTS"))
                                (if bruto [(json (js->clj depois)) "treinador" cid bruto]
                                    [(json (js->clj depois)) "treinador" cid])
                                {:consistency quorum :serialConsistency serial})]
            (.wasApplied gravacao)))))))
(defn com-writer-restauracao! [cid acao]
  ;; One queue shared with salvar!/normal trainer LWT, never a second writer.
  (let [anterior (get @filas-gravacao "treinador" (p/resolved nil))
        trabalho (p/then anterior
                   (fn [_]
                     (when-let [erro (get @falhas-gravacao "treinador")] (throw erro))
                     (swap! restauracao-writer conj cid)
                     (when-not (get @restauracao-bloqueios cid)
                       (swap! restauracao-bloqueios assoc cid "__writer__"))
                     (swap! geracoes-partes update ["treinador" cid] (fnil inc 0))
                     (-> (p/then (p/resolved nil) (fn [_] (acao)))
                         (p/finally (fn []
                                      (swap! restauracao-writer disj cid)
                                      (when (= "__writer__" (get @restauracao-bloqueios cid))
                                        (swap! restauracao-bloqueios dissoc cid)))))))
        _ (swap! gravacoes-pendentes inc)
        segura (p/finally (p/catch trabalho (fn [_] nil)) #(swap! gravacoes-pendentes dec))]
    (swap! filas-gravacao assoc "treinador" segura)
    trabalho))
(defn restauracao-executar! [sql parametros opcoes]
  (let [params (js->clj parametros)
        modulo (first (filter #(and (string? %) (str/starts-with? % "pokemon-restauracao-")) params))]
    (when-not (and (contains? #{"pokemon-restauracao-backups" "pokemon-restauracao-operacoes"
                               "pokemon-restauracao-bloqueios" "pokemon-restauracao-controle"} modulo)
                   (str/includes? sql tabela) (not (str/includes? sql ";"))
                   (not (str/includes? sql "legacy_"))
                   (re-find #"^(SELECT|INSERT|UPDATE) " sql))
      (throw (js/Error. "Consulta de restauração não permitida.")))
    (when-not (str/starts-with? sql "SELECT ")
      (when (empty? @restauracao-writer)
        (throw (js/Error. "Gravação de restauração exige writer exclusivo.")))
      (when (and (= modulo "pokemon-restauracao-backups")
                 (not (and (str/starts-with? sql "INSERT ") (str/includes? sql "IF NOT EXISTS"))))
        (throw (js/Error. "Backup imutável não pode ser alterado."))))
    (executar! @client sql params
               (merge {:consistency quorum :serialConsistency serial}
                      (when (= "SERIAL" (aget opcoes "consistency")) {:consistency serial})))))

(def ^:private modulos-slots
  #{"pokemon-save-slots" "pokemon-save-slot-index" "pokemon-save-global-receipts"})
(defn save-slot-executar!
  "Executor mínimo para slots inativos. Não aceita DDL, DELETE, batches ou
  tabelas diferentes do estado particionado existente."
  [sql parametros opcoes]
  (let [params (js->clj parametros)
        modulo (cond
                 (str/starts-with? sql "SELECT ") (first params)
                 (str/starts-with? sql "INSERT ") (first params)
                 (str/starts-with? sql "UPDATE ") (second params)
                 :else nil)
        consulta-ok? (or
                      (= sql (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?"))
                      (= sql (str "INSERT INTO " tabela " (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS"))
                      (= sql (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?")))]
    (when-not (and (contains? modulos-slots modulo) consulta-ok?
                   (not (str/includes? sql ";")) @pronto @client)
      (throw (js/Error. "Operação de slot não permitida.")))
    (executar! @client sql params
               (merge {:consistency quorum :serialConsistency serial}
                      (when (= "SERIAL" (aget opcoes "consistency")) {:consistency serial})))))

(def ^:private modulos-coordenacao
  #{"pokemon-save-transition-locks" "pokemon-save-transition-journal"
    "pokemon-save-transition-index"})
(def ^:private modulos-hidratacao-saves
  #{"pokemon-playable-save-state" "pokemon-playable-save-index"
    "pokemon-active-save-pointer" "pokemon-playable-save-operations"
    "pokemon-playable-save-operation-index"})
(def ^:private modulos-alvos-coordenacao
  #{"treinador" "ginasios" "ginasios-estatisticas" "loja" "raids"
    "pokemon-batalhas-ativas" "pokemon-cacadas-ativas"
    "pokemon-propostas-troca" "pokemon-remocoes-pendentes"})
(defonce ^:private save-transition-coordinator (atom nil))

(defn save-transition-executar!
  "Adaptador CQL interno e restrito para o coordenador de transição. Aceita
  somente SELECT por chave e INSERT/UPDATE com LWT no estado particionado; não
  executa DDL, DELETE, batches ou leitura global. Não torna os writers normais
  participantes do fence por si só."
  [sql parametros opcoes]
  (let [params (js->clj parametros)
        comando (first (str/split (str/trim sql) #"\s+"))
        modulo (case comando
                 "SELECT" (first params)
                 "INSERT" (first params)
                 "UPDATE" (second params)
                 nil)
        select? (= sql (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?"))
        insert? (= sql (str "INSERT INTO " tabela " (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS"))
        update? (= sql (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?"))
        interno? (contains? modulos-coordenacao modulo)
        hidratacao? (contains? modulos-hidratacao-saves modulo)
        alvo? (contains? modulos-alvos-coordenacao modulo)]
    (when-not (and @pronto @client
                   (not (str/includes? sql ";"))
                   (or (and select? (or interno? hidratacao? alvo?))
                       ;; LWTs de ponteiro/slot/operação de ativação jogável.
                       (and (or insert? update?) (or interno? hidratacao?))))
      (throw (ex-info "Operação de coordenação de saves não permitida." {:code :invalid-save-transition-query})))
    (executar! @client sql params
               (merge {:consistency quorum :serialConsistency serial}
                      (when (= "SERIAL" (aget opcoes "consistency")) {:consistency serial})))))

(defn reconciliar-save-transitions!
  "Reconcilia o índice do coordenador antes do readiness quando explicitamente
  habilitado. O padrão é desligado; o primeiro bootstrap exige cobertura
  legada certificada e falha fechado se ela estiver ausente."
  []
  (if-not config/save-transitions-enabled?
    (p/resolved {:ready false :disabled true})
    (let [modulo (js/require "../runtime/save-transition-coordinator.cjs")
          executar (fn [sql parametros opcoes]
                     (save-transition-executar! sql parametros opcoes))
          coordinator (js/Reflect.construct
                       (.-SaveTransitionCoordinator modulo)
                       #js [executar config/cassandra-keyspace
                            #js {:enabled true
                                 :legacyJournalCoverageVerified
                                 config/save-transition-legacy-coverage-verified?
                                 :writerProtocolCoverageVerified
                                 config/save-transition-writer-coverage-verified?}])]
      (reset! save-transition-coordinator coordinator)
      (.reconcileStartup coordinator))))

(defn- criar-elegibilidade-ativacao! []
  (let [eligibility-module (js/require "../runtime/save-activity-eligibility.cjs")
        ^js trainer @canonical-trainer-store
        account-reader (fn [cid pid]
                         (.readAccount trainer cid pid))
        adventure-reader (fn [cid pid]
                           (if-let [leitura @save-activity-adventure-reader]
                             (leitura cid pid)
                             (p/resolved nil)))
        gate (js/Reflect.construct
              (.-SaveActivityEligibility eligibility-module)
              #js [save-transition-executar! config/cassandra-keyspace
                   #js {:writerCoverageVerified
                        config/save-transition-writer-coverage-verified?
                        :accountReader account-reader
                        :adventureReader adventure-reader}])]
    (reset! save-activity-eligibility gate)
    gate))

(defn- ativacao-saves-jogaveis! []
  (when-not (and @canonical-trainer-store @save-transition-coordinator)
    (throw (ex-info "Hidratação de saves jogáveis sem coordenador certificado."
                    {:code :playable-save-hydration-unavailable})))
  (let [slot-module (js/require "../runtime/save-slot-store.cjs")
        activation-module (js/require "../runtime/playable-save-activation.cjs")
        slot-store (js/Reflect.construct (.-SaveSlotStore slot-module)
                                         #js [save-slot-executar! config/cassandra-keyspace])
        eligibility (criar-elegibilidade-ativacao!)]
    (js/Reflect.construct
     (.-PlayableSaveActivation activation-module)
     #js [save-transition-executar! config/cassandra-keyspace
          #js {:slotStore slot-store :trainerStore @canonical-trainer-store
               :coordinator @save-transition-coordinator
               :eligibility eligibility
               :enabled true}])))

(defn- obter-ativacao-saves! []
  (or @playable-activation-test (ativacao-saves-jogaveis!)))

(defn- bloquear-hidratacao-jogador! [cid pid erro]
  (let [codigo (or (:code (ex-data erro))
                   (some-> erro .-code keyword)
                   (:code (ex-data (.-cause erro)))
                   (some-> erro .-cause .-code keyword))]
    (if (= codigo :playable-save-hydration-blocked)
      (throw erro)
      (do (swap! contas-bloqueadas-hidratacao assoc [cid pid] erro)
          (throw (ex-info "Hidratação de save recusada; jogador bloqueado até reconciliação."
                          {:code :playable-save-hydration-blocked
                           :chat cid :player pid}
                          erro))))))

(defn hidratar-jogador-ativo!
  "Após ativação durável confirmada, valida ponteiro/geração/identidade/hash e
  instala somente a conta desse jogador nos atoms. Não publica enquanto a
  transição estiver pendente (hydrateActive falha fechado). Falha bloqueia o
  jogador até reconciliação; peers do grupo são preservados."
  [cid pid]
  (if-not config/save-transitions-enabled?
    (p/resolved {:ready false :disabled true})
    (p/catch
     (p/let [^js ativacao (obter-ativacao-saves!)
             ^js hidratado (.hydrateActive ativacao cid pid)
             conta (js->clj (.-account hidratado))
             slot (.-activeSlotId hidratado)]
       (when-not (and (map? conta) (string? slot) (not (str/blank? slot)))
         (throw (ex-info "Hidratação incompleta; estado em memória não publicado."
                         {:code :playable-save-hydration-incomplete})))
       (instalar-conta-treinador-hidratada! cid pid conta)
       ;; Economia da loja: migração legado→envelope confirmada antes de liberar.
       _ (if-let [binder @binder-loja-save]
           (binder cid pid slot (.-fenceGeneration hidratado))
           (p/resolved nil))
       {:ready true
        :activeSlotId slot
        :generation (.-generation hidratado)
        :stateHash (.-stateHash hidratado)})
     (fn [erro] (bloquear-hidratacao-jogador! cid pid erro)))))

(defn- garantir-loja-antes-de-deixar-save!
  "Bloqueia A→B enquanto o envelope do save de origem não estiver durável."
  [cid pid]
  (if-not (and config/save-transitions-enabled? @binder-loja-save @canonical-trainer-store)
    (p/resolved nil)
    (p/let [^js conta (.readAccount @canonical-trainer-store cid pid)]
      (when-not conta
        (throw (ex-info "Conta canônica ausente antes da transição de save."
                        {:code :loja-save-binding-required})))
      (let [from (.-activeSaveId conta)
            fence (.-fenceGeneration conta)]
        (when (and (string? from) (not (str/blank? from)))
          (@binder-loja-save cid pid from fence))))))

(defn ativar-save-jogavel!
  "Ativa o slot alvo com fence durável e hidrata automaticamente só o jogador
  que alternou. Callbacks do save anterior ficam com fence/geração obsoletos
  após a instalação. Com flags desligadas não altera atoms."
  [cid pid slot-id operation-id]
  (if-not config/save-transitions-enabled?
    (p/resolved {:ready false :disabled true})
    (p/let [;; Vínculo durável do save atual antes de qualquer reserva/activate.
            _ (garantir-loja-antes-de-deixar-save! cid pid)
            ^js ativacao (obter-ativacao-saves!)
            ;; Hidratação só depois do activate retornar: ponteiro e fence
            ;; já estão confirmados (ou no-op idempotente).
            ^js ativado (.activate ativacao cid pid slot-id operation-id)
            hidratacao (hidratar-jogador-ativo! cid pid)]
      (merge {:activated true
              :activeSlotId (.-activeSlotId ativado)
              :idempotent (boolean (.-idempotent ativado))}
             hidratacao))))

(defn hidratar-saves-ativos!
  "Antes de liberar o serviço no modo de transições, instala nos atoms somente
  a conta canônica cujo ponteiro, slot e recibos globais foram confirmados.
  O slot não sobrescreve o estado canônico atual: ele é a cópia de trabalho
  que será atualizada ao sair daquele save numa transição futura."
  []
  (if-not config/save-transitions-enabled?
    (p/resolved {:ready false :disabled true})
    (let [^js ativacao (obter-ativacao-saves!)
          grupos (get @confirmados "treinador" {})]
      (-> (p/then
           (p/all
            (for [[cid jogadores] grupos
                  pid (keys jogadores)]
              (p/then (.hydrateActive ativacao cid pid)
                      (fn [^js hidratado] [cid pid (js->clj (.-account hidratado))]))))
           (fn [contas-hidratadas]
             (when-not (= (count contas-hidratadas)
                          (reduce + 0 (map (comp count val) grupos)))
               (throw (ex-info "Hidratação parcial recusada; ponteiro de save não instalado."
                               {:code :playable-save-hydration-incomplete})))
             (let [dados (reduce (fn [resultado [cid pid conta]]
                                   (assoc-in resultado [cid pid] conta)) {} contas-hidratadas)]
               ;; Nenhuma escrita ocorre aqui. Só instala depois que todos os
               ;; jogadores confirmaram o ponteiro; uma falha não mistura saves.
               (doseq [[cid pid _] contas-hidratadas]
                 (swap! contas-bloqueadas-hidratacao dissoc [cid pid]))
               (reset! hidratando? true)
               (try
                 (doseq [[cid grupo] dados]
                   (instalar-parte-confirmada! "treinador" cid grupo))
                 {:ready true :accounts (count contas-hidratadas)}
                 (finally (reset! hidratando? false))))))
          (p/catch
           (fn [erro]
             (reset! pronto false)
             (throw erro)))))))

(defn- canonical-executar! [^js c consulta parametros opcoes]
  (let [op (js->clj opcoes :keywordize-keys true)
        consistencia (case (:consistency op)
                       "SERIAL" serial "QUORUM" quorum quorum)]
    (when-not (re-find #"(?i)^\s*SELECT\s" consulta) (exigir-escrita!))
    (.execute c consulta (clj->js parametros)
              (clj->js {:prepare true :consistency consistencia :serialConsistency serial}))))

(defn- iniciar-canonical! []
  (if-not config/canonical-trainer-enabled?
    (p/resolved nil)
    (let [provider (js/Reflect.construct
                    (.-PlainTextAuthProvider (.-auth cassandra))
                    #js [config/canonical-trainer-username config/canonical-trainer-password])
          c (Client. #js {:contactPoints (clj->js config/cassandra-contact-points)
                          :localDataCenter config/cassandra-datacenter
                          :authProvider provider
                          :socketOptions #js {:connectTimeout 5000 :readTimeout 15000}})
          store-module (js/require "../runtime/canonical-trainer-store.cjs")]
      (reset! canonical-client c)
      (-> (p/then (.connect c)
                  (fn [_]
                    (let [store (js/Reflect.construct
                                 (.-CanonicalTrainerStore store-module)
                                 #js [(fn [sql parametros opcoes]
                                        (canonical-executar! c sql parametros opcoes))
                                      config/cassandra-keyspace
                                      #js {:writerId "pokemon-service"}])]
                      (reset! canonical-trainer-store store)
                      (let [resume-mod (js/require "../runtime/adventure-hatch-resume.cjs")
                            resume (js/Reflect.construct
                                    (.-AdventureHatchResume resume-mod)
                                    #js [(fn [sql parametros opcoes]
                                           (canonical-executar! c sql parametros opcoes))
                                         config/cassandra-keyspace
                                         #js {:trainerStore store}])]
                        (reset! adventure-hatch-resume resume))
                      (.assertActivated store))))
          (p/catch (fn [erro]
                     (reset! canonical-client nil)
                     (reset! canonical-trainer-store nil)
                     (reset! adventure-hatch-resume nil)
                     (-> (.shutdown c) (p/catch (fn [_] nil))
                         (p/then (fn [_]
                                   (throw (ex-info "Armazenamento canônico não certificado; startup bloqueado."
                                                   {:code :canonical-trainer-not-ready} erro)))))))))))

(defn- iniciar-canonical-ginasios! []
  (if-not config/canonical-gyms-enabled?
    (p/resolved nil)
    (let [provider (js/Reflect.construct
                    (.-PlainTextAuthProvider (.-auth cassandra))
                    #js [config/canonical-gyms-username config/canonical-gyms-password])
          c (Client. #js {:contactPoints (clj->js config/cassandra-contact-points)
                          :localDataCenter config/cassandra-datacenter
                          :authProvider provider
                          :socketOptions #js {:connectTimeout 5000 :readTimeout 15000}})
          module (js/require "../runtime/canonical-gym-store.cjs")]
      (reset! canonical-gym-client c)
      (-> (p/then (.connect c)
                  (fn [_]
                    (let [store (js/Reflect.construct
                                 (.-CanonicalGymStore module)
                                 #js [(fn [sql parametros opcoes]
                                        (canonical-executar! c sql parametros opcoes))
                                      config/cassandra-keyspace
                                      #js {:writerId "pokemon-service-gym"}])]
                      (reset! canonical-gym-store store)
                      (.capability store "active"))))
          (p/catch (fn [erro]
                     (reset! canonical-gym-client nil)
                     (reset! canonical-gym-store nil)
                     (-> (.shutdown c) (p/catch (fn [_] nil))
                         (p/then (fn [_]
                                   (throw (ex-info "Armazenamento canônico dos ginásios não certificado; startup bloqueado."
                                                   {:code :canonical-gym-not-ready} erro)))))))))))

(defn- carregar-modulo! [c modulo]
  ;; O schema existente usa PK ((modulo,particao)): requer filtro no startup.
  ;; Não fazemos SELECT geral seguido de descarte em memória.
  (cond
    (and (= modulo "treinador") config/canonical-trainer-enabled?)
    (p/then (.readAllGroups @canonical-trainer-store) (fn [grupos] [modulo (js->clj grupos)]))

    (and config/canonical-gyms-enabled?
         (contains? #{"ginasios" "ginasios-estatisticas"} modulo))
    (p/then (.readAll @canonical-gym-store modulo) (fn [grupos] [modulo (js->clj grupos)]))

    :else
  (p/let [linhas (consultar-paginas!
                  c (str "SELECT modulo, particao, valor FROM " tabela
                         " WHERE modulo = ? ALLOW FILTERING") [modulo])
          antigo (consultar-paginas!
                  c (str "SELECT chave, valor FROM " tabela-antiga " WHERE chave = ?") [modulo])]
    (let [migrado? (some (fn [^js row] (= marcador-migracao (.-particao row))) linhas)
          legado (when-let [^js row (first antigo)] (ler-json (.-valor row)))]
      (when (and (seq legado) (not migrado?))
        (throw (js/Error. (str "Módulo " modulo " ainda está no formato legado sem marcador; "
                              "serviço não fará migração automática. Prepare os dados antes do corte."))))
      [modulo (reconstruir
               (into {} (for [^js row linhas :when (not= marcador-migracao (.-particao row))]
                          [(.-particao row) (ler-json (.-valor row))])))]))))

(defn iniciar!
  "Hidrata somente os módulos registrados e falha se Cassandra não estiver
  pronto. Nenhum CREATE/ALTER/DROP ou migração é executado."
  []
  (when-not (re-matches #"[A-Za-z][A-Za-z0-9_]*" config/cassandra-keyspace)
    (throw (js/Error. "CASSANDRA_KEYSPACE inválido.")))
  (reset! pronto false)
  (reset! estatisticas {:readQueries 0 :writeQueries 0 :blockedWrites 0
                       :hydratedModules 0 :hydratedPartitions 0 :hydrated false})
  (let [opts #js {:contactPoints (clj->js config/cassandra-contact-points)
                  :localDataCenter config/cassandra-datacenter
                  :socketOptions #js {:connectTimeout 5000 :readTimeout 15000}}
        _ (when (and (not (str/blank? config/cassandra-username))
                     (not (str/blank? config/cassandra-password)))
            (aset opts "authProvider"
                  (js/Reflect.construct
                   (.-PlainTextAuthProvider (.-auth cassandra))
                   #js [config/cassandra-username config/cassandra-password])))
        c (Client. opts)]
    (reset! client c)
    (-> (p/let [_ (.connect c)
                _ (iniciar-canonical!)
              _ (iniciar-canonical-ginasios!)
                carregados (p/all (map #(carregar-modulo! c %) @modulos))]
          (let [dados (into {} carregados)]
            (reset! cache dados)
            (reset! confirmados dados)
            (reset! filas-gravacao {})
            (reset! falhas-gravacao {})
            (reset! hidratando? true)
            (try
              (doseq [[chave [destino transformar]] @registros]
                (reset! destino (transformar (get dados chave))))
              (finally (reset! hidratando? false)))
            (swap! estatisticas assoc :hydrated true
                   :hydratedModules (count dados)
                   :hydratedPartitions (reduce + 0 (map #(count (partes %)) (vals dados))))
            (reset! pronto true)
            (js/console.log "[PokemonCassandra] módulos do serviço carregados.")))
        (p/catch
         (fn [erro]
           (reset! pronto false)
           (reset! client nil)
           (let [^js canonical @canonical-client]
             (reset! canonical-client nil)
             (reset! canonical-trainer-store nil)
             (reset! adventure-hatch-resume nil)
             (let [^js canonical-gym @canonical-gym-client]
               (reset! canonical-gym-client nil)
               (reset! canonical-gym-store nil)
               (-> (if canonical-gym (.shutdown canonical-gym) (js/Promise.resolve nil))
                   (.finally (fn [] (when canonical (.shutdown canonical))))
                   (.finally (fn [] (.shutdown c)))
                   (p/catch (fn [_] nil))
                   (p/then (fn [_] (p/rejected erro)))))))))))

(defn pendentes []
  {:persistence_pending @gravacoes-pendentes
   :persistence_failed_modules (count @falhas-gravacao)
   :adventure_blocked_chats (count @partes-bloqueadas)})

(defn encerrar! []
  (reset! pronto false)
  (let [^js c @client ^js canonical @canonical-client ^js canonical-gym @canonical-gym-client]
    ;; Promesa 12's CLJS finally is a notification callback: it returns the
    ;; original promise without waiting for the callback's asynchronous work.
    ;; Native finally waits for the driver and preserves a drain rejection.
    (.finally (js/Promise.resolve
               (shutdown-log/etapa "aguardar_todas_BANG_" aguardar-todas! pendentes))
              (fn []
                (reset! client nil)
                (reset! canonical-client nil)
                (reset! canonical-trainer-store nil)
                (reset! adventure-hatch-resume nil)
                (reset! canonical-gym-client nil)
                (reset! canonical-gym-store nil)
                (reset! save-transition-coordinator nil)
                (reset! save-activity-eligibility nil)
                (reset! contas-bloqueadas-hidratacao {})
                (reset! playable-activation-test nil)
                (when c
                  (p/then (shutdown-log/etapa "client.shutdown" #(.shutdown c) pendentes)
                          (fn [_]
                            (p/then (when canonical
                                      (shutdown-log/etapa "canonical.client.shutdown" #(.shutdown canonical) pendentes))
                                    (fn [_]
                                      (when canonical-gym
                                        (shutdown-log/etapa "canonical.gym.client.shutdown" #(.shutdown canonical-gym) pendentes)))))))))))
