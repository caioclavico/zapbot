(ns zapbot.rank
  "Placar de pontos por chat, compartilhado entre os jogos (!velha, !naval,
  !pokemon, !quiz, !adedonha). Persistido via Cassandra (produção) ou Datomic
  (staging com PLATFORM_ADAPTER=datomic). Sem dual-write.

  Toda mutação exige operation-id estável (message id, rodada-id, effect id).
  Cache Node só atualiza após commit Datomic."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.platform-jvm :as platform]))

(defonce ^:private placares
  (atom (or (when-not (platform/enabled?)
              (armazenamento/obter "rank"))
            {})))
(when-not (platform/enabled?)
  (armazenamento/registrar! "rank" placares))
(defonce ^:private filas-efeitos (atom {}))
(defonce ^:private filas-chat (atom {}))

(defn- persistir-cassandra! []
  (armazenamento/salvar! "rank" @placares))

(defn operation-id-from-message
  "ID estável da mensagem WhatsApp (retries HTTP reutilizam o mesmo)."
  [message kind]
  (let [id (.-id message)
        sid (or (when id (.-_serialized id))
                (when id (aget id "$1")))]
    (when (str/blank? sid)
      (throw (js/Error. (str "Mensagem sem id estável para rank/" kind))))
    (str "rank:" kind ":" sid)))

(defn- aplicar-rank-wire!
  [chats]
  (when chats
    (let [m (js->clj chats :keywordize-keys false)]
      ;; wire: {chatId -> {playerId -> info}} ou lista {chatId, players[]}
      (if (sequential? m)
        (reset! placares
                (into {}
                      (map (fn [entry]
                             [(get entry "chatId")
                              (into {}
                                    (map (fn [p]
                                           [(get p "playerId")
                                            (dissoc p "playerId")])
                                         (get entry "players" [])))]))
                      m))
        (reset! placares m)))))

(defn- aplicar-chat-wire!
  [cid players]
  (when players
    (let [ps (js->clj players :keywordize-keys false)
          mapa (if (sequential? ps)
                 (into {}
                       (map (fn [p]
                              [(get p "playerId")
                               (dissoc p "playerId")])
                            ps))
                 ps)]
      (swap! placares assoc cid mapa))))

(defn hidratar-datomic!
  []
  (when (platform/enabled?)
    (platform/hydrate-rank! placares)))

(defn- enfileirar-chat! [cid acao]
  (let [anterior (get @filas-chat cid (p/resolved nil))
        atual (-> anterior
                  (p/catch (fn [_] nil))
                  (p/then (fn [_] (acao))))]
    (swap! filas-chat assoc cid atual)
    (p/finally atual
               (fn []
                 (when (identical? atual (get @filas-chat cid))
                   (swap! filas-chat dissoc cid))))))

(defn- pontuar-cassandra! [cid pid nome jogo]
  (swap! placares update-in [cid pid]
         (fn [info]
           (-> (or info {"nome" nome "pontos" 0 "jogos" {}})
               (assoc "nome" nome)
               (dissoc "somente-efeitos-http")
               (update "pontos" inc)
               (update-in ["jogos" jogo] (fnil inc 0)))))
  (persistir-cassandra!)
  (p/resolved true))

(defn- pontuar-datomic! [cid pid nome jogo operation-id]
  (when (str/blank? operation-id)
    (throw (js/Error. "rank/pontuar! exige operation-id estável")))
  (enfileirar-chat!
   cid
   (fn []
     (-> (platform/rank-score! cid pid nome jogo operation-id)
         (p/then (fn [data]
                   (p/then (platform/fetch-rank-chat cid)
                           (fn [players]
                             (aplicar-chat-wire! cid players)
                             true))))
         (p/catch (fn [err]
                    (js/console.error "rank(datomic): falha ao pontuar (fail-closed):" err)
                    (p/rejected err)))))))

(defn pontuar!
  "Registra 1 ponto. Em Datomic, `operation-id` é obrigatório e estável."
  [cid pid nome jogo operation-id]
  (if (platform/enabled?)
    (pontuar-datomic! cid pid nome jogo operation-id)
    (pontuar-cassandra! cid pid nome jogo)))

(defn- penalizar-cassandra! [cid pid]
  (let [penalizado? (volatile! false)]
    (swap! placares
           (fn [estado]
             (if (pos? (get-in estado [cid pid "pontos"] 0))
               (do
                 (vreset! penalizado? true)
                 (update-in estado [cid pid "pontos"] dec))
               estado)))
    (when @penalizado?
      (persistir-cassandra!))
    (p/resolved @penalizado?)))

(defn- penalizar-datomic! [cid pid operation-id]
  (when (str/blank? operation-id)
    (throw (js/Error. "rank/penalizar! exige operation-id estável")))
  (enfileirar-chat!
   cid
   (fn []
     (-> (platform/rank-penalize! cid pid operation-id)
         (p/then (fn [data]
                   (when-let [player (.-player data)]
                     (let [p (js->clj player :keywordize-keys false)
                           pid* (or (get p "playerId") pid)]
                       (swap! placares assoc-in [cid pid*] (dissoc p "playerId"))))
                   (boolean (.-penalized data))))
         (p/catch (fn [err]
                    (js/console.error "rank(datomic): falha ao penalizar (fail-closed):" err)
                    (p/rejected err)))))))

(defn penalizar!
  "Remove 1 ponto. Retorna promise boolean. operation-id obrigatório em Datomic."
  [cid pid operation-id]
  (if (platform/enabled?)
    (penalizar-datomic! cid pid operation-id)
    (penalizar-cassandra! cid pid)))

(defn- top [cid quantidade]
  (->> (vals (get @placares cid {}))
       (remove #(get % "somente-efeitos-http"))
       (sort-by (fn [info] [(- (long (get info "pontos" 0))) (str (get info "nome" ""))]))
       (take quantidade)))

(defn- identidade-efeito [{:keys [type playerName name game]}]
  {"tipo" type "nome" (or playerName name) "jogo" game})

(defn- aplicar-efeito-cassandra!
  [{:keys [id type chatId playerId playerName name game] :as efeito}]
  (let [anterior (get-in @placares [chatId playerId])
        salvo (get-in anterior ["efeitos-pokemon-http" id])
        identidade (identidade-efeito efeito)
        resultado (if salvo
                    (get salvo "resultado")
                    (or (= type "rank.increment") (pos? (get anterior "pontos" 0))))]
    (when (and salvo (not= identidade (dissoc salvo "resultado")))
      (throw (js/Error. "ID de efeito de rank reutilizado com conteúdo diferente.")))
    (when-not salvo
      (swap! placares update-in [chatId playerId]
             (fn [info]
               (let [base (or info {"nome" (or playerName name "Alguém")
                                   "pontos" 0 "jogos" {}
                                   "somente-efeitos-http" true})
                     nova (case type
                            "rank.increment" (-> base
                                                 (assoc "nome" (or playerName name (get base "nome")))
                                                 (dissoc "somente-efeitos-http")
                                                 (update "pontos" (fnil inc 0))
                                                 (update-in ["jogos" game] (fnil inc 0)))
                            "rank.decrement" (if resultado (update base "pontos" dec) base))]
                 (assoc-in nova ["efeitos-pokemon-http" id]
                           (assoc identidade "resultado" resultado))))))
    (p/let [_ (armazenamento/salvar-confirmado! "rank" @placares)] resultado)))

(defn- aplicar-efeito-datomic!
  [{:keys [id type chatId playerId playerName name game] :as efeito}]
  (-> (platform/rank-effect! #js {:id id
                                  :type type
                                  :chatId chatId
                                  :playerId playerId
                                  :playerName (or playerName name)
                                  :name (or name playerName)
                                  :game game})
      (p/then (fn [data]
                ;; Reconsulta estado do chat após efeito
                (p/then (platform/fetch-rank-chat chatId)
                        (fn [players]
                          (aplicar-chat-wire! chatId players)
                          (boolean (.-resultado data))))))
      (p/catch (fn [err]
                 (js/console.error "rank(datomic): falha efeito (fail-closed):" err)
                 (p/rejected err)))))

(defn aplicar-efeito!
  "Único writer de rank para efeitos HTTP Pokémon. Dedupe por effect.id."
  [{:keys [id type chatId playerId game] :as efeito}]
  (if-not (and (every? #(and (string? %) (not (str/blank? %))) [id chatId playerId])
               (contains? #{"rank.increment" "rank.decrement"} type)
               (or (= type "rank.decrement") (= game "pokemon")))
    (p/rejected (js/Error. "Efeito de rank inválido."))
    (let [anterior (get @filas-efeitos chatId (p/resolved nil))
          atual (-> anterior
                    (p/catch (fn [_] nil))
                    (p/then (fn [_]
                              (if (platform/enabled?)
                                (aplicar-efeito-datomic! efeito)
                                (aplicar-efeito-cassandra! efeito)))))]
      (swap! filas-efeitos assoc chatId atual)
      (p/finally atual
                 (fn []
                   (when (identical? atual (get @filas-efeitos chatId))
                     (swap! filas-efeitos dissoc chatId)))))))

(defn vitorias-jogo
  [cid pid jogo]
  (get-in @placares [cid pid "jogos" jogo] 0))

(def ^:private medalha ["🥇" "🥈" "🥉"])

(defn- formatar-jogador [posicao info]
  (str (get medalha posicao (str (inc posicao) "º")) " " (get info "nome")
       " - " (get info "pontos") " pts"))

(defn formatar-rank
  "Retorna o texto do rank de pontos desse chat (top 10)."
  [cid]
  (let [melhores (top cid 10)]
    (str "🏆 *Rank do tio " config/bot-name " nesse chat:*\n\n"
         (if (empty? melhores)
           (str "Ainda ninguém pontuou por aqui. Jogue " config/prefix "velha, "
                config/prefix "naval, " config/prefix "pokemon ou " config/prefix
                "quiz pra entrar no rank!")
           (str/join "\n" (map-indexed formatar-jogador melhores))))))
