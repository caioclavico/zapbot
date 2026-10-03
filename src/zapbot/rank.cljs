(ns zapbot.rank
  "Placar de pontos por chat, compartilhado entre os jogos (!velha, !naval,
  !pokemon, !quiz). Persistido via zapbot.armazenamento (sobrevive a
  reinícios/deploys, igual ao resto do estado persistido)."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]))

(defonce ^:private placares (atom (or (armazenamento/obter "rank") {})))
(armazenamento/registrar! "rank" placares)
(defonce ^:private filas-efeitos (atom {}))

(defn- persistir! []
  (armazenamento/salvar! "rank" @placares))

(defn pontuar!
  "Registra 1 ponto pra pid (com nome) no jogo indicado (string, ex.
  \"velha\"), nesse chat."
  [cid pid nome jogo]
  (swap! placares update-in [cid pid]
         (fn [info]
           (-> (or info {"nome" nome "pontos" 0 "jogos" {}})
               (assoc "nome" nome)
               (dissoc "somente-efeitos-http")
               (update "pontos" inc)
               (update-in ["jogos" jogo] (fnil inc 0)))))
  (persistir!))

(defn penalizar!
  "Remove 1 ponto de pid por desistência, sem permitir pontuação negativa.
  Retorna true quando um ponto foi removido e false quando o jogador já
  estava em zero ou ainda não fazia parte do rank."
  [cid pid]
  (let [penalizado? (volatile! false)]
    (swap! placares
           (fn [estado]
             (if (pos? (get-in estado [cid pid "pontos"] 0))
               (do
                 (vreset! penalizado? true)
                 (update-in estado [cid pid "pontos"] dec))
               estado)))
    (when @penalizado?
      (persistir!))
    @penalizado?))

(defn- top [cid quantidade]
  (->> (vals (get @placares cid {}))
       (remove #(get % "somente-efeitos-http"))
       (sort-by #(get % "pontos") >)
       (take quantidade)))

(defn- identidade-efeito [{:keys [type playerName name game]}]
  {"tipo" type "nome" (or playerName name) "jogo" game})

(defn- aplicar-efeito-agora!
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
    ;; Mesmo um marcador vindo do cache é reconfirmado: a primeira tentativa
    ;; pode ter falhado depois da mutação em memória. Nunca repetir a pontuação.
    (p/let [_ (armazenamento/salvar-confirmado! "rank" @placares)] resultado)))

(defn aplicar-efeito!
  "Único writer de rank para efeitos HTTP Pokémon. O marcador de dedupe e a
  pontuação são gravados na mesma linha Cassandra. Retorna promise booleana."
  [{:keys [id type chatId playerId game] :as efeito}]
  (if-not (and (every? #(and (string? %) (not (str/blank? %))) [id chatId playerId])
               (contains? #{"rank.increment" "rank.decrement"} type)
               (or (= type "rank.decrement") (= game "pokemon")))
    (p/rejected (js/Error. "Efeito de rank inválido."))
    (let [anterior (get @filas-efeitos chatId (p/resolved nil))
          atual (-> anterior
                    (p/catch (fn [_] nil))
                    (p/then (fn [_] (aplicar-efeito-agora! efeito))))]
      (swap! filas-efeitos assoc chatId atual)
      (p/finally atual
                 (fn []
                   (when (identical? atual (get @filas-efeitos chatId))
                     (swap! filas-efeitos dissoc chatId)))))))

(defn vitorias-jogo
  "Quantas vitórias pid já tem no jogo indicado (string, ex. \"pokemon\"),
  nesse chat - 0 se nunca pontuou."
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
