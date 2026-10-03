(ns pokemon-service.entry
  "Fachada do domínio para o processo HTTP. Recebe somente dados neutros."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]
            [zapbot.http :as http]
            [zapbot.rank :as rank]
            [zapbot.pokemon.core :as pokemon]
            [zapbot.pokemon.pokedex :as pokedex]
            [zapbot.pokemon.loja :as loja]))

(defn initialize [] (armazenamento/iniciar!))
(defn diagnostics [] (clj->js (armazenamento/diagnostico)))
(defn is-ready [] (boolean (armazenamento/pronto?)))
(defn load-state [modulo] (clj->js (armazenamento/obter modulo)))
(defn store-state [modulo valor] (armazenamento/salvar! modulo (js->clj valor)))
(defn register-module [modulo] (armazenamento/registrar-modulo! modulo))
(defn reserve [modulo chave valor]
  (p/then (armazenamento/reservar! modulo chave (js->clj valor)) clj->js))
(defn take-effects [chat-id] (clj->js (rank/recolher-efeitos! chat-id)))
(defn start-timers [emitir]
  (when-not config/read-only?
    (pokemon/iniciar! (fn [chat-id resposta] (emitir chat-id (clj->js resposta))))))
(defn stop-timers [] (pokemon/parar!))
(defn shutdown []
  (stop-timers)
  (p/let [_ (pokemon/aguardar-operacoes!)] (armazenamento/encerrar!)))

(defn contexto-neutro [^js pedido emitir]
  (let [{:keys [requestId chatId playerId playerName context]} (js->clj pedido :keywordize-keys true)
        alvo (:bugTarget context)]
    {:request-id requestId
     :chat-id chatId
     :player-id playerId
     :player-name (or playerName "Alguém")
     :mentioned-ids (vec (:mentionedIds context))
     :quoted-player-id (:quotedPlayerId context)
     :admin? (true? (:isAdmin context))
     :bot-version (:botVersion context)
     :bug-target (when alvo
                   {:autor (or (:playerId alvo) (:author alvo) (:autor alvo))
                    :corpo (or (:text alvo) (:body alvo) (:corpo alvo) "")
                    :em (or (:timestamp alvo) (:em alvo))
                    :origem (or (:source alvo) (:origem alvo) "anterior")})
     :emit! (when emitir (fn [resposta] (emitir (clj->js resposta))))}))

(defn despachar [contexto texto]
  (armazenamento/exigir-escrita!)
  (let [[comando & partes] (str/split (str/trim (or texto "")) #"\s+")
        comando (str/lower-case comando)
        args (str/join " " partes)]
    (case comando
      ("pk" "pokemon") (pokemon/jogar contexto args)
      ("pokedex" "dex" "pdx") (pokedex/buscar contexto args)
      ("presente" "presentes") (pokemon/jogar contexto (str "presente " args))
      ("missoes" "missões") (pokemon/jogar contexto (str "missoes " args))
      "mochila" (p/resolved (loja/mochila contexto (first partes)))
      "loja" (case (some-> (first partes) str/lower-case (str/replace #":" ""))
               "comprar" (p/resolved (loja/comprar contexto (str/join " " (rest partes))))
               ("detalhes" "detalhe") (p/resolved (loja/detalhes (str/join " " (rest partes))))
               (loja/ver-loja-com-imagem contexto))
      (p/rejected (ex-info "Comando fora do domínio Pokémon." {:status 400})))))

(defn command [^js pedido emitir]
  (armazenamento/exigir-escrita!)
  (let [contexto (contexto-neutro pedido emitir)]
    (http/acompanhar!
     #(desempenho/acompanhar!
       contexto
       (fn [_]
         (p/let [resposta (despachar contexto (.-command pedido))
                 _ (armazenamento/aguardar-todas!)]
           (clj->js resposta)))
       (fn [dados]
         (js/console.log
          (js/JSON.stringify
           (clj->js {:event "pokemon_command" :requestId (.-requestId pedido)
                     :command_processing_ms (:total_ms dados)
                     :stages (:etapas_ms dados) :status (:evento dados)}))))
       "pokemon"))))
