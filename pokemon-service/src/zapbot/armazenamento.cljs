(ns zapbot.armazenamento
  "Persistência exclusiva do serviço Pokémon sobre o schema existente.
  Não executa DDL nem migra dados. Cada módulo tem um único writer ativo."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [pokemon-service.shutdown :as shutdown-log]
            ["cassandra-driver" :as cassandra]
            [zapbot.config :as config]))

(def ^:private Client (.-Client cassandra))
(def ^:private tabela-antiga (str config/cassandra-keyspace ".estado"))
(def ^:private tabela (str config/cassandra-keyspace ".estado_particionado"))
(def ^:private marcador-migracao "__migrado__")
(def ^:private particao-valor "__valor__")
(defonce ^:private client (atom nil))
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
(defonce restauracao-bloqueios (atom {}))
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

(declare exigir-restauracao-livre! instalar-parte-confirmada!)

(defn exigir-parte-disponivel! [modulo particao]
  (when (= modulo "treinador") (exigir-restauracao-livre! particao))
  (when (get @partes-bloqueadas [modulo particao])
    (throw (ex-info "Persistência incerta; recupere a operação antes de prosseguir."
                    {:code :result-unknown}))))
(defn- json [valor] (js/JSON.stringify (clj->js valor)))
(defn- ler-json [valor] (js->clj (js/JSON.parse valor)))
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

(defn- operacoes-alteradas! [c chave anterior valor]
  (let [antes (partes anterior) depois (partes valor)]
    (p/all
     (concat
      (for [[particao dado] depois :when (not= dado (get antes particao ::ausente))]
        (gravar-parte! c chave particao dado))
      (for [particao (keys antes) :when (not (contains? depois particao))]
        (apagar-parte! c chave particao))))))

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

(defn salvar!
  "Retorna confirmação real. O cache otimista preserva o serializador de
  combates; falha restaura o último snapshot confirmado se nada mais o alterou."
  [chave valor]
  (if @hidratando?
    ;; Restaurar atoms dispara watches. Hidratação nunca escreve nem migra
    ;; dados; a próxima ação real salvará seu snapshot contra o confirmado.
    (p/resolved nil)
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
                            (let [efetivo (snapshot-atual chave valor geracoes)]
                              (p/let [_ (tentar-operacao!
                                         #(operacoes-alteradas! @client chave
                                                               (get @confirmados chave {}) efetivo) 2)]
                                (swap! confirmados assoc chave efetivo)
                                (swap! falhas-gravacao dissoc chave)
                                nil)))))
            _ (swap! gravacoes-pendentes inc)
            segura (p/finally
                    (p/catch
                     gravacao
                     (fn [erro]
                       (swap! falhas-gravacao assoc chave erro)
                       (when (identical? valor (get @cache chave))
                         (swap! cache assoc chave (get @confirmados chave {})))
                       (js/console.error "[PokemonCassandra] gravação não confirmada:" chave (.-message erro))
                       nil))
                    #(swap! gravacoes-pendentes dec))]
        (swap! filas-gravacao assoc chave segura)
        gravacao))))

(defn aguardar-todas!
  "Falhas de durabilidade chegam ao handler; nunca significam sucesso HTTP."
  []
  (p/let [_ (p/all (vals @filas-gravacao))]
    (when-let [erro (first (concat (vals @falhas-gravacao) (vals @partes-bloqueadas)))]
      (throw erro))))

(def ^:private quorum (.. cassandra -types -consistencies -localQuorum))
(def ^:private serial (.. cassandra -types -consistencies -localSerial))

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

(defn- instalar-parte-confirmada! [modulo particao valor]
  (swap! cache assoc-in [modulo particao] valor)
  (swap! confirmados assoc-in [modulo particao] valor)
  (when-let [[destino transformar] (get @registros modulo)]
    (swap! destino assoc particao (get (transformar {particao valor}) particao)))
  (swap! geracoes-partes update [modulo particao] (fnil inc 0))
  (swap! partes-bloqueadas dissoc [modulo particao]))

(defn recuperar-treinador! [cid]
  "Leitura SERIAL resolve Paxos pendente antes de reabrir o chat incerto."
  (p/let [_ (get @filas-gravacao "treinador" (p/resolved nil))
          ^js resultado (executar! @client
                           (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                           ["treinador" cid] {:consistency serial})]
    (let [row (.first resultado) valor (if (and row (.-valor row)) (ler-json (.-valor row)) {})]
      (instalar-parte-confirmada! "treinador" cid valor)
      valor)))

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
                     (p/let [^js leitura (executar! @client
                                           (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                                           ["treinador" cid] {:consistency serial})
                             ^js row (.first leitura)
                             bruto (when row (.-valor row))
                             {:keys [valor resultado]} (mudar (if bruto (ler-json bruto) {}))
                             novo (json valor)
                             ^js gravacao (when (not= bruto novo)
                                            (executar! @client
                                              (if bruto
                                                (str "UPDATE " tabela " SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?")
                                                (str "INSERT INTO " tabela " (valor, modulo, particao) VALUES (?, ?, ?) IF NOT EXISTS"))
                                              (if bruto [novo "treinador" cid bruto] [novo "treinador" cid])
                                              {:consistency quorum :serialConsistency serial}))]
                       (when (and gravacao (not (.wasApplied gravacao)))
                         (throw (ex-info "Snapshot do treinador mudou; recupere antes de entregar." {:code :result-unknown})))
                       (instalar-parte-confirmada! "treinador" cid valor)
                       resultado)))
        _ (swap! gravacoes-pendentes inc)
        segura (p/finally (p/catch trabalho
                           (fn [erro]
                             (swap! partes-bloqueadas assoc ["treinador" cid] erro)
                             (js/console.error (js/JSON.stringify #js {:event "adventure_delivery_unknown" :stage "trainer_cas"}))
                             nil))
                         #(swap! gravacoes-pendentes dec))]
    (swap! filas-gravacao assoc "treinador" segura)
    trabalho))

(defn reservar!
  "Reserva uma partição com LWT para requestId. Não repete LWT com resultado
  incerto. Retorna boolean; o cache recebe o valor confirmado ou já existente."
  [modulo particao valor]
  (exigir-escrita!)
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
  (p/let [^js leitura (executar! @client
                         (str "SELECT valor FROM " tabela " WHERE modulo = ? AND particao = ?")
                         ["treinador" cid] {:consistency serial})]
    (let [row (.first leitura)] (clj->js (if row (ler-json (.-valor row)) {})))))
(defn restauracao-instalar! [cid valor]
  (instalar-parte-confirmada! "treinador" cid (js->clj valor)))
(defn restauracao-cas-grupo! [cid antes depois]
  (exigir-escrita!)
  (when-not (contains? @restauracao-writer cid)
    (throw (js/Error. "Writer exclusivo de restauração obrigatório.")))
  (p/let [^js leitura (executar! @client
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
            (.wasApplied gravacao))))))
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

(defn- carregar-modulo! [c modulo]
  ;; O schema existente usa PK ((modulo,particao)): requer filtro no startup.
  ;; Não fazemos SELECT geral seguido de descarte em memória.
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
                          [(.-particao row) (ler-json (.-valor row))])))])))

(defn iniciar!
  "Hidrata somente os módulos registrados e falha se Cassandra não estiver
  pronto. Nenhum CREATE/ALTER/DROP ou migração é executado."
  []
  (when-not (re-matches #"[A-Za-z][A-Za-z0-9_]*" config/cassandra-keyspace)
    (throw (js/Error. "CASSANDRA_KEYSPACE inválido.")))
  (reset! pronto false)
  (reset! estatisticas {:readQueries 0 :writeQueries 0 :blockedWrites 0
                       :hydratedModules 0 :hydratedPartitions 0 :hydrated false})
  (let [c (Client. #js {:contactPoints (clj->js config/cassandra-contact-points)
                        :localDataCenter config/cassandra-datacenter
                        :socketOptions #js {:connectTimeout 5000 :readTimeout 15000}})]
    (reset! client c)
    (-> (p/let [_ (.connect c)
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
           (-> (.shutdown c)
               (p/catch (fn [_] nil))
               (p/then (fn [_] (p/rejected erro)))))))))

(defn pendentes []
  {:persistence_pending @gravacoes-pendentes
   :persistence_failed_modules (count @falhas-gravacao)
   :adventure_blocked_chats (count @partes-bloqueadas)})

(defn encerrar! []
  (reset! pronto false)
  (let [^js c @client]
    ;; Promesa 12's CLJS finally is a notification callback: it returns the
    ;; original promise without waiting for the callback's asynchronous work.
    ;; Native finally waits for the driver and preserves a drain rejection.
    (.finally (js/Promise.resolve
               (shutdown-log/etapa "aguardar_todas_BANG_" aguardar-todas! pendentes))
              (fn []
                (reset! client nil)
                (when c
                  (shutdown-log/etapa "client.shutdown" #(.shutdown c) pendentes))))))
