(ns pokemon-service.entry
  "Fachada do domínio para o processo HTTP. Recebe somente dados neutros."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [pokemon-service.shutdown :as shutdown-log]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]
            [zapbot.http :as http]
            [zapbot.rank :as rank]
            [zapbot.pokemon.core :as pokemon]
            [zapbot.pokemon.adventure :as adventure]
            [zapbot.pokemon.egg-commands :as egg-commands]
            [zapbot.pokemon.pokedex :as pokedex]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.ajuda :as ajuda]))

(defn initialize []
  (pokemon/configurar-recompensa-ovo! adventure/drenar-eventos!)
  (pokemon/configurar-comando-ovos! egg-commands/comando!)
  (adventure/preparar-inicializacao!)
  ;; Preserva a criação síncrona do driver usada pelo bootstrap e pelos testes.
  (p/then (armazenamento/iniciar!) (fn [_] (adventure/inicializar!))))
(defn diagnostics [] (clj->js (armazenamento/diagnostico)))
(defn is-ready [] (boolean (and (armazenamento/pronto?) (adventure/disponivel?))))
(defn load-state [modulo] (clj->js (armazenamento/obter modulo)))
(defn store-state [modulo valor] (armazenamento/salvar! modulo (js->clj valor)))
(defn register-module [modulo] (armazenamento/registrar-modulo! modulo))
(defn reserve [modulo chave valor]
  (p/then (armazenamento/reservar! modulo chave (js->clj valor)) clj->js))
(defn take-effects [chat-id] (clj->js (rank/recolher-efeitos! chat-id)))

;; Exports internos para composição/testes; não estão no dispatcher HTTP.
(defn eggs-activate [cid pid] (p/then (adventure/ativar! cid pid) clj->js))
(defn eggs-create [cid pid event-id categoria]
  (p/then (adventure/criar-ovo! cid pid event-id categoria) clj->js))
(defn eggs-incubate [cid pid event-id egg-id]
  (p/then (adventure/incubar! cid pid event-id egg-id) clj->js))
(defn eggs-distance [cid pid evento]
  (p/then (adventure/aplicar-distancia! cid pid (js->clj evento :keywordize-keys true)) clj->js))
(defn eggs-state [cid pid] (p/then (adventure/consultar! cid pid) clj->js))
(defn eggs-recover [cid pid] (p/then (adventure/recuperar! cid pid) clj->js))
(defn eggs-history [cid pid month cursor]
  (p/then (adventure/historico! cid pid month cursor) clj->js))
(defn activity-activate [cid pid event-id]
  (p/then (adventure/atividade-ativar! cid pid event-id) clj->js))
(defn activity-incubate [cid pid event-id egg-id]
  (p/then (adventure/atividade-incubar! cid pid event-id egg-id) clj->js))
(defn activity-drain [cid]
  (pokemon/enfileirar-operacao-interna! cid (fn [] (adventure/drenar-eventos! cid))))
(defn activity-grant-incubator [cid pid event-id kind]
  (p/then (adventure/conceder-incubadora! cid pid event-id (keyword kind)) clj->js))
(defn activity-points [cid pid event-id points]
  (p/then (adventure/atividade-pontos! cid pid event-id points) clj->js))
(defn activity-state [cid pid]
  (p/then (adventure/atividade-consultar! cid pid) clj->js))
(defn activity-time [cid pid now]
  (p/then (adventure/atividade-avancar-tempo! cid pid now) clj->js))
(defn activity-tick [] (adventure/processar-vencidos!))
(defn start-timers [emitir]
  (when-not config/read-only?
    (adventure/iniciar-notificacoes! (fn [cid resposta] (emitir cid (clj->js resposta))))
    (pokemon/iniciar! (fn [chat-id resposta] (emitir chat-id (clj->js resposta))))))
(defn stop-timers [] (adventure/parar!) (pokemon/parar!))
(defn shutdown-pending []
  (clj->js (merge (pokemon/filas-pendentes) (armazenamento/pendentes))))
(defn shutdown []
  (stop-timers)
  (p/let [_ (shutdown-log/etapa "aguardar_operacoes_BANG_" pokemon/aguardar-operacoes!
                               pokemon/filas-pendentes)]
    (armazenamento/encerrar!)))

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
      "mochila" (if (contains? #{"ajuda" "help" "?"} (some-> (first partes) str/lower-case))
                   (p/resolved (ajuda/resposta "mochila ajuda"))
                   (p/resolved (loja/mochila contexto (first partes))))
      "loja" (case (some-> (first partes) str/lower-case (str/replace #":" ""))
               ("ajuda" "help" "?") (p/resolved (ajuda/resposta "loja ajuda"))
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
