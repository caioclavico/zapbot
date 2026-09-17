(ns zapbot.armazenamento
  "Persistência no Cassandra particionada por módulo e registro.

  A tabela antiga `estado` é mantida como cópia de segurança. Na primeira
  inicialização desta versão, cada JSON antigo é dividido automaticamente em
  linhas na tabela `estado_particionado`. Um marcador só é gravado depois que
  todas as linhas do módulo forem copiadas, permitindo repetir com segurança
  uma migração interrompida."
  (:require [promesa.core :as p]
            ["cassandra-driver" :as cassandra]
            [zapbot.config :as config]))

(def ^:private Client (.-Client cassandra))
(def ^:private tabela-antiga (str config/cassandra-keyspace ".estado"))
(def ^:private tabela (str config/cassandra-keyspace ".estado_particionado"))
(def ^:private marcador-migracao "__migrado__")
(def ^:private particao-valor "__valor__")

(defonce ^:private client (atom nil))
(defonce ^:private cache (atom {}))
(defonce ^:private confirmados (atom {}))
(defonce ^:private registros (atom {}))
;; Serializa gravações do mesmo módulo para uma atualização antiga nunca
;; terminar depois de uma nova e restaurar dados removidos.
(defonce ^:private filas-gravacao (atom {}))

(defn- esperar [ms]
  (p/create (fn [resolve _] (js/setTimeout resolve ms))))

(def ^:private tentativas-gravacao 3)
(def ^:private espera-base-gravacao-ms 150)

(defn- tentar-operacao!
  "Repete operações idempotentes do Cassandra com espera crescente."
  [acao restantes tentativa]
  (-> (acao)
      (p/catch
       (fn [erro]
         (if (pos? restantes)
           (p/then (esperar (* espera-base-gravacao-ms tentativa))
                   (fn [_] (tentar-operacao! acao (dec restantes) (inc tentativa))))
           (p/rejected erro))))))

(defn registrar!
  ([chave atom-chamador] (registrar! chave atom-chamador identity))
  ([chave atom-chamador transformar]
   (swap! registros assoc chave [atom-chamador transformar])
   (when (contains? @cache chave)
     (reset! atom-chamador (transformar (get @cache chave))))))

(defn obter [chave] (get @cache chave))

(defn- json [valor] (js/JSON.stringify (clj->js valor)))
(defn- ler-json [valor] (js->clj (js/JSON.parse valor)))

(defn- partes
  "Divide mapas pelo primeiro nível. Valores não associativos continuam
  suportados numa partição reservada."
  [valor]
  (if (map? valor) valor {particao-valor valor}))

(defn- reconstruir [partes-modulo]
  (if (contains? partes-modulo particao-valor)
    (get partes-modulo particao-valor)
    partes-modulo))

(defn- gravar-parte! [c modulo particao valor]
  (.execute c (str "INSERT INTO " tabela " (modulo, particao, valor) VALUES (?, ?, ?)")
            #js [modulo (str particao) (json valor)] #js {:prepare true}))

(defn- apagar-parte! [c modulo particao]
  (.execute c (str "DELETE FROM " tabela " WHERE modulo = ? AND particao = ?")
            #js [modulo (str particao)] #js {:prepare true}))

(defn- operacoes-alteradas! [c chave anterior valor]
  (let [antes (partes anterior)
        depois (partes valor)
        alteradas (for [[particao dado] depois :when (not= dado (get antes particao ::ausente))]
                    (gravar-parte! c chave particao dado))
        removidas (for [particao (keys antes) :when (not (contains? depois particao))]
                    (apagar-parte! c chave particao))]
    (p/all (concat alteradas removidas))))

(defn salvar!
  "Atualiza o cache imediatamente e persiste apenas as partições alteradas.
  Retorna uma promise que termina quando esta gravação entra em ordem e conclui.
  O diff usa somente o último snapshot confirmado: uma falha parcial será
  repetida de forma idempotente pela próxima gravação, em vez de desaparecer
  atrás do cache otimista."
  [chave valor]
  (do
    (swap! cache assoc chave valor)
    (if-let [c @client]
      (let [anterior-fila (get @filas-gravacao chave (p/resolved nil))
            gravacao (-> anterior-fila
                         (p/catch (fn [_] nil))
                         (p/then
                          (fn [_]
                            (let [anterior (get @confirmados chave {})]
                              (-> (tentar-operacao!
                                   #(operacoes-alteradas! c chave anterior valor)
                                   (dec tentativas-gravacao) 1)
                                  (p/then (fn [_]
                                            (swap! confirmados assoc chave valor))))))))
            ;; A fila precisa continuar mesmo após uma falha, mas o chamador
            ;; recebe `gravacao` e pode decidir não confirmar a própria ação.
            fila-segura (p/catch gravacao
                                 (fn [err]
                                   (js/console.error
                                    (str "Erro ao salvar módulo \"" chave "\" no Cassandra após "
                                         tentativas-gravacao " tentativas:") err)
                                   nil))]
        (swap! filas-gravacao assoc chave fila-segura)
        gravacao)
      (p/resolved nil))))

(defn aguardar-todas!
  "Espera as filas de persistência conhecidas terminarem (com sucesso ou após
  esgotarem as tentativas). Útil antes de responder ações que alteram vários
  módulos, como recompensas de uma batalha."
  []
  (p/all (vals @filas-gravacao)))

(def ^:private tentativas-conexao 5)
(def ^:private espera-entre-tentativas-ms 3000)

(defn- conectar-com-retry [c tentativas-restantes]
  (-> (.connect c)
      (p/catch (fn [err]
                 (if (pos? tentativas-restantes)
                   (do (js/console.warn (str "⏳ Cassandra ainda não respondeu, tentando de novo em "
                                              (/ espera-entre-tentativas-ms 1000) "s... (" err ")"))
                       (p/then (esperar espera-entre-tentativas-ms)
                               (fn [_] (conectar-com-retry c (dec tentativas-restantes)))))
                   (p/rejected err))))))

(defn- linhas->modulos [rows]
  (reduce (fn [acc row]
            (let [modulo (.-modulo row)
                  particao (.-particao row)]
              (if (= marcador-migracao particao)
                (update acc :migrados conj modulo)
                (assoc-in acc [:dados modulo particao] (ler-json (.-valor row))))))
          {:dados {} :migrados #{}} rows))

(defn- migrar-modulo! [c modulo valor]
  (p/let [_ (p/all (for [[particao dado] (partes valor)]
                     (gravar-parte! c modulo particao dado)))
          _ (gravar-parte! c modulo marcador-migracao true)]
    (js/console.log (str "✅ Cassandra: módulo " modulo " migrado para linhas particionadas."))))

(defn iniciar!
  "Cria a estrutura nova, migra dados legados de modo retomável e hidrata os
  módulos registrados. Se o Cassandra falhar, o bot continua sem persistência."
  []
  (let [c (Client. #js {:contactPoints (clj->js config/cassandra-contact-points)
                        :localDataCenter config/cassandra-datacenter})]
    (reset! client c)
    (-> (p/let [_ (conectar-com-retry c tentativas-conexao)
                _ (.execute c (str "CREATE KEYSPACE IF NOT EXISTS " config/cassandra-keyspace
                                   " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"))
                _ (.execute c (str "CREATE TABLE IF NOT EXISTS " tabela-antiga
                                   " (chave text PRIMARY KEY, valor text)"))
                _ (.execute c (str "CREATE TABLE IF NOT EXISTS " tabela
                                   " (modulo text, particao text, valor text, PRIMARY KEY ((modulo, particao)))"))
                novas (.execute c (str "SELECT modulo, particao, valor FROM " tabela))
                antigas (.execute c (str "SELECT chave, valor FROM " tabela-antiga))]
          (let [{:keys [dados migrados]} (linhas->modulos (.-rows novas))
                legados (into {} (map (fn [row] [(.-chave row) (ler-json (.-valor row))])
                                      (.-rows antigas)))
                pendentes (remove (fn [[modulo _]] (contains? migrados modulo)) legados)]
            ;; Durante esta inicialização, módulos ainda não marcados usam o
            ;; JSON legado completo. A próxima inicialização já lerá as partes.
            (let [carregados
                  (merge (into {} (map (fn [[modulo ps]] [modulo (reconstruir ps)]) dados))
                         (into {} pendentes))]
              (reset! cache carregados)
              (reset! confirmados carregados))
            (doseq [[chave [atom-chamador transformar]] @registros]
              (when (contains? @cache chave)
                (reset! atom-chamador (transformar (get @cache chave)))))
            (p/let [_ (p/all (map (fn [[modulo valor]] (migrar-modulo! c modulo valor)) pendentes))]
              (js/console.log "✅ Conectado ao Cassandra; estado particionado carregado."))))
        (p/catch (fn [err]
                   (js/console.error "❌ Não consegui conectar/preparar o Cassandra - seguindo sem persistência nessa execução:" err)
                   (reset! client nil))))))
