(ns zapbot.bugs
  "Relatórios de bugs enviados pelos jogadores e persistidos no Cassandra."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.bloqueio :as bloqueio]
            [zapbot.historico :as historico]))

(def ^:private versao-app
  (try
    (.-version (js/require "../package.json"))
    (catch :default _ "desconhecida")))

(defonce ^:private relatorios (atom (or (armazenamento/obter "bugs") {})))
(armazenamento/registrar! "bugs" relatorios)

(defn- jogador-id [message]
  (or (.-author message) (.-from message)))

(defn- proximo-id []
  (inc (reduce max 0 (keep #(let [n (js/parseInt % 10)]
                              (when-not (js/isNaN n) n))
                           (keys @relatorios)))))

(defn- mensagem-alvo
  "Prefere a mensagem citada/respondida; sem citação, usa a anterior do chat."
  [message]
  (let [anterior #(some-> (historico/mensagem-anterior message)
                           (assoc :origem "anterior"))]
    (if (and (.-hasQuotedMsg message) (fn? (.-getQuotedMessage message)))
      (-> (.getQuotedMessage message)
          (p/then (fn [citada]
                    {:autor (or (.-author citada) (.-from citada) "desconhecido")
                     :corpo (or (.-body citada) (.-caption citada) "")
                     :em (when (number? (.-timestamp citada)) (* 1000 (.-timestamp citada)))
                     :origem "citada"}))
          (p/catch (fn [erro]
                     (js/console.warn "Não consegui ler a mensagem citada; usando a anterior:" erro)
                     (anterior))))
      (p/resolved (anterior)))))

(defn registrar!
  "Persiste a mensagem citada ou, sem citação, a imediatamente anterior."
  [message]
  (p/let [alvo (mensagem-alvo message)]
    (if-let [{:keys [autor corpo em origem]} alvo]
      (let [id (str (proximo-id))
            item {"id" id
                  "versao" versao-app
                  "chat" (bloqueio/chat-id message)
                  "reportado-por" (jogador-id message)
                  "autor-mensagem" autor
                  "mensagem" corpo
                  "mensagem-em" em
                  "origem" origem
                  "reportado-em" (.now js/Date)
                  "status" "aberto"}]
        (swap! relatorios assoc id item)
        (-> (armazenamento/salvar! "bugs" @relatorios)
            (p/then (fn [_]
                      (str "🐛 Bug registrado com o número *#" id "* na versão *"
                           versao-app "*.\nMensagem " (if (= origem "citada") "citada" "anterior")
                           " salva: “" corpo "”")))
            (p/catch (fn [erro]
                       (swap! relatorios dissoc id)
                       (js/console.error "Erro ao persistir relatório de bug:" erro)
                       "❌ Não consegui salvar o bug no Cassandra. Tente novamente."))))
      "❓ Não encontrei uma mensagem anterior ou citada para registrar como bug.")))

(defn- formatar-data [ms]
  (if (number? ms) (.toLocaleString (js/Date. ms) "pt-BR") "data desconhecida"))

(defn- resumo [item]
  (str "🐛 *#" (get item "id") "* · v" (get item "versao")
       " · " (if (= "resolvido" (get item "status")) "✅ resolvido" "🔴 aberto")
       "\n" (subs (get item "mensagem" "") 0 (min 180 (count (get item "mensagem" ""))))))

(defn- listar [versao]
  (let [itens (->> (vals @relatorios)
                   (filter #(or (str/blank? versao) (= versao (get % "versao"))))
                   (sort-by #(js/parseInt (get % "id") 10) >)
                   (take 20))]
    (if (seq itens)
      (str "📋 *Bugs" (when-not (str/blank? versao) (str " da versão " versao)) "*\n\n"
           (str/join "\n\n" (map resumo itens))
           "\n\nUse !pk bug ver <número> para ver os detalhes.")
      (str "✅ Nenhum bug encontrado" (when-not (str/blank? versao) (str " na versão " versao)) "."))))

(defn- detalhar [id]
  (if-let [item (get @relatorios id)]
    (str "🐛 *Bug #" id "*\n"
         "Versão: " (get item "versao") "\n"
         "Status: " (get item "status") "\n"
         "Registrado: " (formatar-data (get item "reportado-em")) "\n"
         "Chat: " (get item "chat") "\n"
         "Reportado por: " (get item "reportado-por") "\n"
         "Autor da mensagem: " (get item "autor-mensagem") "\n\n"
         "Origem: mensagem " (get item "origem" "anterior") "\n\n"
         "*Mensagem registrada:*\n" (get item "mensagem"))
    (str "❓ Bug #" id " não encontrado.")))

(defn- resolver! [id]
  (if-let [item (get @relatorios id)]
    (let [anterior @relatorios]
      (swap! relatorios assoc id (assoc item "status" "resolvido" "resolvido-em" (.now js/Date)))
      (-> (armazenamento/salvar! "bugs" @relatorios)
          (p/then (fn [_] (str "✅ Bug #" id " marcado como resolvido.")))
          (p/catch (fn [erro]
                     (reset! relatorios anterior)
                     (js/console.error "Erro ao resolver relatório de bug:" erro)
                     "❌ Não consegui atualizar o bug no Cassandra."))))
    (p/resolved (str "❓ Bug #" id " não encontrado."))))

(defn- somente-admin [message acao]
  (p/let [autorizado? (bloqueio/autorizado? message)]
    (if autorizado?
      (acao)
      "🚫 Apenas administradores podem consultar ou resolver os relatórios de bugs.")))

(defn comando!
  "Executa !pk bug, !pk bugs [versão], !pk bug ver <id> e resolver <id>."
  [message cmd args]
  (let [[acao-original valor] args
        acao (get {"res" "resolver"} acao-original acao-original)]
    (cond
      (= cmd "bugs") (somente-admin message #(p/resolved (listar (or acao ""))))
      (= acao "ver") (somente-admin message #(p/resolved (detalhar valor)))
      (= acao "resolver") (somente-admin message #(resolver! valor))
      (seq args) (p/resolved "❓ Use !pk bug, !pk bugs [versão], !pk bug ver <número> ou !pk bug resolver <número>.")
      :else (registrar! message))))
