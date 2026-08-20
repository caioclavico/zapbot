(ns zapbot.armazenamento
  "Persistência em Cassandra (tabela única chave/valor, ver iniciar!) pra
  estado que hoje vive só em memória (defonce atom) sobreviver a reinícios
  do bot/deploys.

  Como a conexão com o Cassandra é assíncrona mas os outros namespaces
  esperam ler/gravar de forma síncrona (ex.: `(defonce placares (atom (or
  (armazenamento/obter \"rank\") {})))` roda na hora que o namespace é
  carregado, bem antes de qualquer await terminar), o padrão é:
  1. iniciar! conecta, garante keyspace/tabela e carrega tudo pra um cache
     local (atom) - deve ser chamado (e esperado) uma vez, no início do
     zapbot.core/main, antes do bot conectar no WhatsApp.
  2. obter é sempre síncrono, lê só desse cache local (por isso pode
     retornar nil se chamado antes de iniciar! terminar - normal só na
     primeiríssima inicialização de cada namespace, ver registrar!).
  3. Cada namespace chama registrar! logo depois de criar seu atom, pra
     ganhar um `reset!` automático assim que iniciar! terminar de carregar
     (cobre o caso comum de o atom já ter sido criado, com valor padrão tipo
     {}, antes da conexão terminar).
  4. salvar! atualiza o cache local na hora (síncrono, comportamento igual
     ao de antes) e manda a gravação pro Cassandra em segundo plano.

  Convenção: os dados salvos usam só strings como chave (nunca keywords),
  tanto no nível de fora quanto dentro - assim clj->js/js->clj não precisam
  de nenhuma opção especial e o round-trip é sempre exato. Cada namespace
  que usa isso é responsável por converter de/para sua própria estrutura
  interna (ex.: sets viram vetor pra salvar, e voltam a ser set ao carregar)."
  (:require [promesa.core :as p]
            ["cassandra-driver" :as cassandra]
            [zapbot.config :as config]))

(def ^:private Client (.-Client cassandra))

(def ^:private tabela (str config/cassandra-keyspace ".estado"))

(defonce ^:private client (atom nil))
(defonce ^:private cache (atom {}))
;; chave -> atom do namespace chamador, pra hidratar quando iniciar! carregar
(defonce ^:private registros (atom {}))

(defn registrar!
  "Registra o atom do chamador pra ser sincronizado com o valor persistido
  sob `chave` assim que iniciar! terminar de carregar. Chame logo depois de
  criar o atom, com o mesmo padrão de defonce+registrar! usado em
  zapbot.rank. Se iniciar! já tiver carregado antes desse registro (registro
  tardio), hidrata na hora.

  `transformar` é opcional (padrão identity) - use quando a forma persistida
  (sempre string/vetor, ver convenção no topo do namespace) for diferente da
  forma usada em memória pelo chamador (ex.: zapbot.bloqueio/zapbot.admins
  convertem string->keyword e vetor->set)."
  ([chave atom-chamador] (registrar! chave atom-chamador identity))
  ([chave atom-chamador transformar]
   (swap! registros assoc chave [atom-chamador transformar])
   (when (contains? @cache chave)
     (reset! atom-chamador (transformar (get @cache chave))))))

(defn obter
  "Lê o valor salvo (do cache local, já carregado por iniciar!) para a
  chave, ou nil se não houver nada salvo ainda."
  [chave]
  (get @cache chave))

(defn salvar!
  "Atualiza o cache local na hora e manda a gravação pro Cassandra em
  segundo plano (não espera a escrita terminar - só loga se der erro)."
  [chave valor]
  (swap! cache assoc chave valor)
  (when-let [c @client]
    (-> (.execute c (str "INSERT INTO " tabela " (chave, valor) VALUES (?, ?)")
                  #js [chave (js/JSON.stringify (clj->js valor))]
                  #js {:prepare true})
        (p/catch (fn [err] (js/console.error (str "Erro ao salvar \"" chave "\" no Cassandra:") err))))))

(defn- esperar [ms]
  (p/create (fn [resolve _] (js/setTimeout resolve ms))))

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

(defn iniciar!
  "Conecta no Cassandra (com algumas tentativas, caso o container ainda
  esteja subindo), cria o keyspace/tabela se não existirem, carrega tudo
  que já estiver salvo pro cache local e hidrata os atoms já registrados
  (ver registrar!). Se não conseguir conectar de jeito nenhum, loga o erro
  e segue em frente mesmo assim (o bot funciona, só sem persistência nessa
  execução) - não trava a inicialização por causa disso."
  []
  (let [c (Client. #js {:contactPoints (clj->js config/cassandra-contact-points)
                        :localDataCenter config/cassandra-datacenter})]
    (reset! client c)
    (-> (p/let [_         (conectar-com-retry c tentativas-conexao)
                _         (.execute c (str "CREATE KEYSPACE IF NOT EXISTS " config/cassandra-keyspace
                                            " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"))
                _         (.execute c (str "CREATE TABLE IF NOT EXISTS " tabela
                                            " (chave text PRIMARY KEY, valor text)"))
                resultado (.execute c (str "SELECT chave, valor FROM " tabela))]
          (doseq [row (.-rows resultado)]
            (let [chave (.-chave row)
                  valor (js->clj (js/JSON.parse (.-valor row)))]
              (swap! cache assoc chave valor)
              (when-let [[atom-chamador transformar] (get @registros chave)]
                (reset! atom-chamador (transformar valor)))))
          (js/console.log "✅ Conectado ao Cassandra, estado carregado."))
        (p/catch (fn [err]
                   (js/console.error "❌ Não consegui conectar/preparar o Cassandra - seguindo sem persistência nessa execução:" err)
                   (reset! client nil))))))
