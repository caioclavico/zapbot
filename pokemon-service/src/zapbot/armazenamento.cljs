(ns zapbot.armazenamento
  "Persistência exclusiva do serviço Pokémon sobre o schema existente.
  Não executa DDL nem migra dados. Cada módulo tem um único writer ativo."
  (:require [promesa.core :as p]
            [clojure.string :as str]
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
      (verificar-modulo! chave)
      (swap! cache assoc chave valor)
      (let [anterior (get @filas-gravacao chave (p/resolved nil))
            gravacao (-> anterior
                         (p/then
                          (fn [_]
                            (p/let [_ (tentar-operacao!
                                       #(operacoes-alteradas! @client chave
                                                             (get @confirmados chave {}) valor) 2)]
                              (swap! confirmados assoc chave valor)
                              (swap! falhas-gravacao dissoc chave)
                              nil))))
            segura (p/catch
                    gravacao
                    (fn [erro]
                      (swap! falhas-gravacao assoc chave erro)
                      (when (identical? valor (get @cache chave))
                        (swap! cache assoc chave (get @confirmados chave {})))
                      (js/console.error "[PokemonCassandra] gravação não confirmada:" chave (.-message erro))
                      nil))]
        (swap! filas-gravacao assoc chave segura)
        gravacao))))

(defn aguardar-todas!
  "Falhas de durabilidade chegam ao handler; nunca significam sucesso HTTP."
  []
  (p/let [_ (p/all (vals @filas-gravacao))]
    (when-let [erro (first (vals @falhas-gravacao))]
      (throw erro))))

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

(defn encerrar! []
  (reset! pronto false)
  (let [^js c @client]
    (-> (aguardar-todas!)
        (p/finally (fn []
                     (reset! client nil)
                     (when c (.shutdown c)))))))
