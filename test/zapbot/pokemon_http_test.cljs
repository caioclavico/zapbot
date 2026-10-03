(ns zapbot.pokemon-http-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            [zapbot.pokemon-http :as http]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.rank :as rank]
            [zapbot.bloqueio :as bloqueio]))

(defn- isolado! [executar]
  (let [originais @http/entregas
        salvar armazenamento/salvar-confirmado!
        efeito rank/aplicar-efeito!]
    (reset! http/entregas {})
    (set! armazenamento/salvar-confirmado! (fn [& _] (p/resolved nil)))
    (-> (p/resolved nil)
        (p/then (fn [_] (executar)))
        (p/finally (fn []
                     (reset! http/entregas originais)
                     (set! armazenamento/salvar-confirmado! salvar)
                     (set! rank/aplicar-efeito! efeito))))))

(deftest contexto-http-preserva-identidade-e-prioridade-da-mencao
  (async done
    (let [citacoes (atom 0)
          message #js {:id #js {:_serialized "mensagem-estavel"}
                       :from "grupo@g.us" :author "jogador@lid"
                       :mentionedIds #js [#js {:_serialized "destinatario@lid"}]
                       :hasQuotedMsg true
                       :getContact (fn [] (p/resolved #js {:pushname "Nome Jogador" :name "Outro"}))
                       :getQuotedMessage (fn [] (swap! citacoes inc) (p/resolved #js {:author "citado@c.us"}))}]
      (-> (http/contexto-pedido message "pokemon" "doar 1")
          (p/then (fn [pedido]
                    (is (= "mensagem-estavel" (:requestId pedido)))
                    (is (= "grupo@g.us" (:chatId pedido)))
                    (is (= "jogador@lid" (:playerId pedido)))
                    (is (= "Nome Jogador" (:playerName pedido)))
                    (is (= ["destinatario@lid"] (get-in pedido [:context :mentionedIds])))
                    (is (nil? (get-in pedido [:context :quotedPlayerId])))
                    (is (zero? @citacoes))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest doacao-http-usa-autor-citado-sem-mencao-e-chat-de-saida
  (async done
    (let [message #js {:id "req" :fromMe true :to "grupo@g.us" :from "bot@c.us"
                       :hasQuotedMsg true
                       :getQuotedMessage (fn [] (p/resolved #js {:author "citado@lid"}))}]
      (-> (http/contexto-pedido message "pokemon" "neg 1 2")
          (p/then (fn [pedido]
                    (is (= "grupo@g.us" (:chatId pedido)))
                    (is (= "Alguém" (:playerName pedido)))
                    (is (= "citado@lid" (get-in pedido [:context :quotedPlayerId])))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest bug-tambem-usa-servico-como-unico-dono-dos-relatorios
  (async done
    (let [chamadas (atom [])
          resultado (with-redefs [http/executar (fn [_ cmd args]
                                                (swap! chamadas conj [cmd args])
                                                (p/resolved "registrado"))]
                      (http/jogar #js {} "bug res 7"))]
      (-> resultado
          (p/then (fn [resposta]
                    (is (= "registrado" resposta))
                    (is (= [["pokemon" "bug res 7"]] @chamadas))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest documentos-e-legendas-longas-viram-envios-ordenados
  (let [texto (apply str (repeat 901 "x"))
        acoes (http/acoes-de-envio
               [{:type "image" :text texto :caption "Perfil" :mediaId "foto"}
                {:type "document" :text "Equipe" :mediaId "csv"}])]
    (is (= ["image" "text" "text" "document"] (mapv :type acoes)))
    (is (= ["Perfil" texto "Equipe" ""] (mapv :text acoes)))))

(deftest entrega-repetida-reutiliza-marcadores-e-substitui-penalizacao
  (async done
    (let [envios (atom []) efeitos (atom [])
          resposta {:messages [{:type "text" :text "Resultado: {{rank:e1}}"}]
                    :effects [{:id "e1" :type "rank.decrement" :token "{{rank:e1}}"
                               :whenTrue "perdeu ponto" :whenFalse "nenhum desconto"}]}
          enviar (fn [texto _] (swap! envios conj texto) (p/resolved nil))]
      (-> (isolado!
           (fn []
             (set! rank/aplicar-efeito!
                   (fn [efeito] (swap! efeitos conj (:id efeito)) (p/resolved false)))
             (p/let [_ (http/entregar! #js {} enviar nil "event:test" resposta)
                     _ (http/entregar! #js {} enviar nil "event:test" resposta)]
               (is (= ["Resultado: nenhum desconto"] @envios))
               (is (= ["e1" "e1"] @efeitos))
               (is (= 1 (count @http/entregas))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest evento-so-confirma-depois-da-persistencia-e-ack-repetido-nao-reenvia
  (async done
    (let [envios (atom 0) gravacoes (atom 0) acks (atom 0)
          cliente-original @http/cliente-http
          whatsapp-original @http/cliente-whatsapp
          configurado http/configurado?
          evento #js {:id "event-1" :chatId "grupo@g.us"
                       :messages #js [#js {:type "text" :text "Raide apareceu"}] :effects #js []}
          cliente #js {:pendingEvents (fn [] (p/resolved #js {:events #js [evento]}))
                        :ack (fn [_] (if (= 1 (swap! acks inc))
                                       (p/rejected (js/Error. "ack perdido"))
                                       (p/resolved #js {:acknowledged true})))}]
      (set! http/configurado? (fn [] true))
      (reset! http/cliente-http cliente)
      (reset! http/cliente-whatsapp #js {:sendMessage (fn [& _] (swap! envios inc) (p/resolved nil))})
      (-> (isolado!
           (fn []
             (set! armazenamento/salvar-confirmado!
                   (fn [& _] (if (= 1 (swap! gravacoes inc))
                               (p/rejected (js/Error. "Cassandra indisponível"))
                               (p/resolved nil))))
             (p/let [_ (http/consultar-eventos!)
                     _ (do (is (= 1 @envios)) (is (zero? @acks)))
                     _ (http/consultar-eventos!)
                     _ (do (is (= 1 @envios)) (is (= 1 @acks)))
                     _ (http/consultar-eventos!)]
               (is (= 1 @envios))
               (is (= 2 @acks))
               (is (= 3 @gravacoes)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/configurado? configurado)
                       (reset! http/cliente-http cliente-original)
                       (reset! http/cliente-whatsapp whatsapp-original)
                       (done)))))))

(deftest falha-de-midia-preserva-o-texto-sem-renderizar-no-bot
  (async done
    (let [envios (atom [])
          cliente #js {:media (fn [_] (p/rejected (js/Error. "mídia indisponível")))}]
      (-> (http/enviar-acao! cliente
             (fn [conteudo opcoes]
               (swap! envios conj [conteudo (js->clj (.-mentions opcoes))])
               (p/resolved nil))
             nil {:type "image" :mediaId "foto" :mimeType "image/png" :filename "foto.png"
                  :text "Resultado da captura" :mentions ["treinador@lid"]})
          (p/then (fn [_]
                    (is (= [["Resultado da captura" ["treinador@lid"]]] @envios))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))
