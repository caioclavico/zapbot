(ns pokemon-service.command-http-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [pokemon-service.entry :as entry]
            [zapbot.armazenamento :as storage]))

(deftest treinador-real-atravessa-http-com-cassandra-fake-e-jpeg-real
  (async done
    (let [http (js/require "node:http")
          runtime (js/require "../runtime/service.cjs")
          destinos [storage/client storage/pronto storage/cache storage/confirmados
                    storage/filas-gravacao storage/falhas-gravacao storage/hidratando?]
          antes (mapv deref destinos)
          registros (mapv (fn [[_ [destino _]]] [destino @destino]) @storage/registros)
          chamadas (atom 0)
          imagens (atom 0)
          dominio #js {:registerModule entry/register-module :load entry/load-state
                       :store entry/store-state :reserve entry/reserve
                       :isReady entry/is-ready :takeEffects entry/take-effects
                       :command (fn [pedido emitir]
                                  (swap! chamadas inc)
                                  (entry/command pedido emitir))}
          media #js {:put (fn [imagem]
                           (swap! imagens inc)
                           (is (.isBuffer js/Buffer (.-buffer imagem)))
                           (is (= "ffd8"
                                  (.toString (.subarray (.-buffer imagem) 0 2) "hex")))
                           (is (= "image/jpeg" (.-mime imagem)))
                           (is (= "treinador-pokemon.jpg" (.-filename imagem)))
                           (p/resolved #js {:mediaId (.repeat "a" 64) :mimeType (.-mime imagem)
                                           :filename (.-filename imagem)}))}
          service (new (.-PokemonService runtime) #js {:domain dominio :media media :logger (fn [_])})
          server (.createServer http ((.-handler runtime) service #js {:token "fixture-token"}))
          pedido #js {:requestId "isolated-trainer-http" :chatId "isolated-http-chat"
                      :playerId "isolated-http-player@lid" :playerName "Teste"
                      :command "pk treinador"
                      :context #js {:mentionedIds #js [] :quotedPlayerId nil :isAdmin false
                                    :botVersion "teste" :bugTarget nil}}]
      ;; No real driver/client or timers. All domain writes stay in memory.
      (reset! storage/client
              #js {:getState (fn [] #js {:getConnectedHosts (fn [] #js ["fake"])})
                   :execute (fn [& _]
                              (p/resolved #js {:rows #js [] :wasApplied (fn [] true)
                                               :first (fn [] #js {})}))})
      (reset! storage/pronto true)
      (reset! storage/filas-gravacao {})
      (reset! storage/falhas-gravacao {})
      (-> (p/let [_ (p/create (fn [resolve _] (.listen server 0 "127.0.0.1" resolve)))
                  url (str "http://127.0.0.1:" (.-port (.address server)) "/commands")
                  chamar (fn [] (js/fetch url #js {:method "POST"
                                                  :headers #js {:authorization "Bearer fixture-token"
                                                                :content-type "application/json"}
                                                  :body (js/JSON.stringify pedido)}))
                  res (chamar)
                  resposta (.json res)
                  replay (chamar)
                  repetida (.json replay)]
            (is (= 200 (.-status res)))
            (is (= (.-requestId pedido) (.-requestId resposta)))
            (is (.isArray js/Array (.-messages resposta)))
            (is (.isArray js/Array (.-effects resposta)))
            (if (.-enabled (js/require "../runtime/metrics.cjs"))
              (is (number? (.. resposta -timings -request_total_ms)))
              (is (= {} (js->clj (.-timings resposta)))))
            (is (= "image" (.-type (aget (.-messages resposta) 0))))
            (is (= (js->clj resposta) (js->clj repetida)))
            (is (= 1 @chamadas))
            (is (= 1 @imagens)))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally
           (fn []
             (.closeAllConnections server)
             (.close server)
             (reset! storage/hidratando? true)
             (doseq [[destino valor] registros] (reset! destino valor))
             (doseq [[destino valor] (map vector destinos antes)] (reset! destino valor))
             (done)))))))
