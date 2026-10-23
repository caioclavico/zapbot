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
      (-> (http/contexto-pedido message "pokemon" "doar 1")
          (p/then (fn [pedido]
                    (is (= "grupo@g.us" (:chatId pedido)))
                    (is (= "Alguém" (:playerName pedido)))
                    (is (= "citado@lid" (get-in pedido [:context :quotedPlayerId])))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest whatsapp-atual-preserva-id-canonico-dollar-sem-serialized
  (async done
    (let [id "false_grupo@g.us_nonce_jogador@lid"
          message #js {:id #js {:$1 id :fromMe false :remote "grupo@g.us"
                                :id "nonce" :participant #js {:user "jogador" :server "lid"}}
                       :from "grupo@g.us" :author "jogador@lid"
                       :mentionedIds #js []}]
      (-> (p/let [primeiro (http/contexto-pedido message "pokemon" "treinador")
                  replay (http/contexto-pedido message "pokemon" "treinador")]
            (is (= id (:requestId primeiro)))
            (is (= primeiro replay))
            (is (= "pokemon treinador" (:command primeiro)))
            (is (= "jogador@lid" (:playerId primeiro))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest serializacao-preserva-legado-e-rejeita-id-sem-string-estavel
  (is (= "legado" (http/serializar-id #js {:_serialized "legado" :$1 "novo"})))
  (is (= "novo" (http/serializar-id #js {:$1 "novo"})))
  (is (= "direto" (http/serializar-id "direto")))
  (doseq [id [nil #js {} #js {:$1 #js {}} #js {:$1 ""} " "]]
    (is (nil? (http/serializar-id id)))))

(deftest comando-whatsapp-atual-chega-ao-http-e-entrega-resposta
  (async done
    (let [original @http/cliente-http
          pedidos (atom []) envios (atom [])
          id "false_grupo@g.us_nonce_jogador@lid"
          message #js {:id #js {:$1 id} :from "grupo@g.us" :author "jogador@lid"
                       :mentionedIds #js []
                       :reply (fn [texto & _] (swap! envios conj texto) (p/resolved nil))}
          cliente #js {:command (fn [pedido]
                                  (swap! pedidos conj (js->clj pedido :keywordize-keys true))
                                  (p/resolved #js {:requestId (.-requestId pedido)
                                                   :messages #js [#js {:type "text" :text "Perfil"}]
                                                   :effects #js [] :timings #js {}}))}]
      (reset! http/cliente-http cliente)
      (-> (isolado!
           (fn []
             (p/let [resultado (http/executar message "pk" "treinador")]
               (is (nil? resultado))
               (is (= 1 (count @pedidos)))
               (is (= id (:requestId (first @pedidos))))
               (is (= "pk treinador" (:command (first @pedidos))))
               (is (= ["Perfil"] @envios)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn [] (reset! http/cliente-http original) (done)))))))

(deftest missoes-e-presentes-jvm-usam-identidade-da-mensagem-e-recibo-estavel
  (async done
    (let [original @http/cliente-jvm
          jvm? http/jvm-configurado?
          chamados (atom []) envios (atom [])
          message #js {:id #js {:_serialized "msg-missao"}
                       :from "grupo@g.us" :author "ash@c.us"
                       :mentionedIds #js ["misty@c.us"]
                       :reply (fn [texto & _] (swap! envios conj texto) (p/resolved nil))}
          message-gift #js {:id #js {:_serialized "msg-presente"}
                            :from "grupo@g.us" :author "ash@c.us"
                            :mentionedIds #js ["misty@c.us"]
                            :reply (fn [texto & _] (swap! envios conj texto) (p/resolved nil))}
          missions #js {:daily #js {:period "2026-10-21" :level 1
                                    :missions #js [#js {:id "capturas" :nome "Colecionador"
                                                        :objetivo "Capturar" :progresso 2 :meta 2
                                                        :xp 3 :pokebolas 4 :estado "completed"}]}
                        :weekly #js {:period "2026-10-19" :missions #js []}
                        :events #js [#js {:nome "Novo Recomeço" :status "ended"}]}
          client (clj->js {:getMissions (fn [_] (p/resolved missions))
                      :getActiveSave (fn [_] (p/resolved #js {:activeSaveId "a" :fenceGeneration 7}))
                      :claimDailyMissions (fn [body]
                                            (swap! chamados conj [:daily (js->clj body :keywordize-keys true)])
                                            (p/resolved #js {:ids #js ["capturas"] :xp 3
                                                             :items #js [#js {:itemId "pokebola" :qty 4}]
                                                             :pending #js []}))
                      :sendGift (fn [body]
                                  (swap! chamados conj [:gift (js->clj body :keywordize-keys true)])
                                  (p/resolved #js {:sent true :targetPlayerId "misty@c.us"
                                                   :items #js [#js {:itemId "pokebola" :qty 3}]
                                                   :pending #js []}))})]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (p/let [_ (http/executar message "missoes" "resgatar")
                     _ (http/executar message-gift "presente" "@ignorado")]
               (is (= [[:daily {:chatId "grupo@g.us" :playerId "ash@c.us" :saveId "a"
                                 :fenceGeneration 7 :operationId "msg-missao:mission-claim:daily:2026-10-21"
                                 :period "2026-10-21"}]
                       [:gift {:chatId "grupo@g.us" :playerId "ash@c.us" :saveId "a"
                               :fenceGeneration 7 :operationId "msg-presente:gift-send:misty@c.us"
                               :targetPlayerId "misty@c.us"}]] @chamados))
               (is (some #(re-find #"missão\(ões\) resgatada" %) @envios))
               (is (some #(re-find #"Presente enviado para @misty" %) @envios)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? jvm?)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest comandos-de-missoes-preservam-o-filtro-de-apresentacao
  (async done
    (let [original @http/cliente-jvm
          jvm? http/jvm-configurado?
          envios (atom [])
          message (fn [id]
                    #js {:id #js {:_serialized id}
                         :from "grupo@g.us" :author "ash@c.us" :mentionedIds #js []
                         :reply (fn [texto & _] (swap! envios conj texto) (p/resolved nil))})
          missions #js {:daily #js {:period "2026-10-21" :level 1
                                    :missions #js [#js {:id "diaria" :nome "Diária exclusiva"
                                                        :objetivo "Capturar" :progresso 1 :meta 2}]}
                        :weekly #js {:period "2026-10-19"
                                     :missions #js [#js {:id "semanal" :nome "Semanal exclusiva"
                                                         :objetivo "Vencer PvP" :progresso 1 :meta 3}]}
                        :events #js []}
          client (clj->js {:getMissions (fn [_] (p/resolved missions))})]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (p/finally
       (p/catch
        (isolado!
         (fn []
           (p/let [_ (http/executar (message "missoes-todas") "pokemon" "missoes")
                   _ (http/executar (message "missoes-diarias") "pokemon" "missoes diarias")
                   _ (http/executar (message "missoes-semanais") "pokemon" "missoes semanais")]
             (let [[todas diarias semanais] @envios]
               (is (and (re-find #"Diária exclusiva" todas)
                        (re-find #"Semanal exclusiva" todas)))
               (is (and (re-find #"Diária exclusiva" diarias)
                        (nil? (re-find #"Semanal exclusiva" diarias))))
               (is (and (re-find #"Semanal exclusiva" semanais)
                        (nil? (re-find #"Diária exclusiva" semanais))))))))
        (fn [erro] (is false (str erro))))
       (fn []
         (set! http/jvm-configurado? jvm?)
         (reset! http/cliente-jvm original)
         (done))))))

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
          tentativas (atom 0)
          cliente #js {:media (fn [_] (p/rejected (js/Error. "mídia indisponível")))}]
      (-> (http/enviar-acao! cliente
             (fn [conteudo opcoes]
               (if (= 1 (swap! tentativas inc))
                 (p/rejected (js/Error. "envio de mídia indisponível"))
                 (do (swap! envios conj [conteudo (js->clj (.-mentions opcoes))])
                     (p/resolved nil))))
             nil {:type "image" :mediaId "foto" :mimeType "image/png" :filename "foto.png"
                  :text "Resultado da captura" :mentions ["treinador@lid"]})
          (p/then (fn [_]
                    (is (= [["Resultado da captura" ["treinador@lid"]]] @envios))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))


(deftest catalogo-longo-da-loja-fica-na-legenda-no-whatsapp
  (let [texto (apply str (repeat 1800 "x"))
        mensagem {:type "image" :text texto :filename "loja-pokemon.jpg"
                  :mediaId "loja" :mimeType "image/jpeg"}]
    (is (= [mensagem] (http/acoes-de-envio [mensagem])))
    (let [compra (assoc mensagem :text (str "✅ Comprou Poção!\n\n" texto))]
      (is (= [compra] (http/acoes-de-envio [compra]))))))
(deftest contexto-troca-usa-mencao-real-e-nao-consulta-citacao
  (async done
    (let [citacoes (atom 0)
          message #js {:id #js {:_serialized "neg-msg"}
                       :from "grupo@g.us" :author "ash@lid"
                       :mentionedIds #js [#js {:_serialized "misty@lid"}]
                       :hasQuotedMsg true
                       :getQuotedMessage (fn [] (swap! citacoes inc) (p/resolved #js {:author "forjado@lid"}))}]
      (-> (http/contexto-pedido message "pokemon" "troca 2 3 @texto")
          (p/then (fn [pedido]
                    (is (= "ash@lid" (:playerId pedido)))
                    (is (= ["misty@lid"] (get-in pedido [:context :mentionedIds])))
                    (is (nil? (get-in pedido [:context :quotedPlayerId])))
                    (is (zero? @citacoes))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))
(deftest troca-rejeita-mencoes-ausentes-ou-ambiguas-e-indice-invalido
  (async done
    (let [respostas (atom [])
          original @http/cliente-jvm
          original-config http/jvm-configurado?
          client (clj->js {:getActiveSave (fn [_] (p/resolved #js {:activeSaveId "save"
                                                                    :fenceGeneration 1}))
                           :getCollection (fn [_] (p/resolved #js {:collection #js [#js {:pokemonId "p1"}]}))})
          mensagem (fn [id mentions args]
                    #js {:id #js {:_serialized id}
                         :from (str id "@g.us") :author "ash@lid"
                         :mentionedIds mentions
                         :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (p/let [_ (http/executar (mensagem "troca-sem-mencao" #js [] "troca 2 3 @misty") "pokemon" "troca 2 3 @misty")
                     _ (http/executar (mensagem "troca-duas-mencoes"
                                                #js [#js {:_serialized "misty@lid"}
                                                     #js {:_serialized "brock@lid"}]
                                                "troca 2 3 @misty") "pokemon" "troca 2 3 @misty")
                     _ (http/executar (mensagem "troca-indice-invalido"
                                                #js [#js {:_serialized "misty@lid"}]
                                                "troca 9 3 @misty") "pokemon" "troca 9 3 @misty")]
               (is (= 3 (count @respostas)))
               (is (every? #(re-find #"Mencione exatamente um treinador" %)
                           (take 2 @respostas)))
               (is (re-find #"Índice de Pokémon inválido" (last @respostas))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest troca-entrega-uma-resposta-para-listagem-e-transicoes
  (async done
    (let [respostas (atom []) chamadas (atom [])
          original @http/cliente-jvm
          original-config http/jvm-configurado?
          client (clj->js
                  {:getActiveSave (fn [_] (p/resolved #js {:activeSaveId "save"
                                                           :fenceGeneration 1}))
                   :getCollection (fn [_] (p/resolved #js {:collection #js [#js {:pokemonId "p1"}]}))
                   :listTrades (fn [_] (p/resolved #js {:trades #js []}))
                   :createTrade (fn [body] (swap! chamadas conj ["create" body])
                                      (p/resolved #js {:tradeId "t1"}))
                   :acceptTrade (fn [body] (swap! chamadas conj ["accept" body])
                                      (p/resolved #js {:tradeId "t1"}))
                   :completeTrade (fn [body] (swap! chamadas conj ["complete" body])
                                        (p/resolved #js {:tradeId "t1"}))
                   :refuseTrade (fn [body] (swap! chamadas conj ["refuse" body])
                                      (p/resolved #js {:tradeId "t1"}))
                   :cancelTrade (fn [body] (swap! chamadas conj ["cancel" body])
                                      (p/resolved #js {:tradeId "t1"}))})
          mensagem (fn [id player mentions]
                     #js {:id #js {:_serialized id} :from "grupo@g.us" :author player
                          :mentionedIds mentions
                          :reply (fn [texto & _] (swap! respostas conj [id texto]) (p/resolved nil))})]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (p/let [_ (http/executar (mensagem "list" "ash@lid" #js []) "pokemon" "troca")
                     _ (http/executar (mensagem "create" "ash@lid" #js [#js {:_serialized "misty@lid"}]) "pokemon" "troca 1 1 @misty")
                     _ (http/executar (mensagem "accept" "misty@lid" #js []) "pokemon" "troca aceitar t1")
                     _ (http/executar (mensagem "complete" "ash@lid" #js []) "pokemon" "troca confirmar t1")
                     _ (http/executar (mensagem "refuse" "misty@lid" #js []) "pokemon" "troca recusar t1")
                     _ (http/executar (mensagem "cancel" "ash@lid" #js []) "pokemon" "troca cancelar t1")]
               (is (= 6 (count @respostas)))
               (is (= ["create" "accept" "complete" "refuse" "cancel"]
                      (mapv first @chamadas)))
               (is (= 6 (count (set (map first @respostas))))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest troca-envia-erro-da-jvm-uma-unica-vez
  (async done
    (let [respostas (atom []) original @http/cliente-jvm original-config http/jvm-configurado?
          message #js {:id #js {:_serialized "trade-error"} :from "grupo@g.us" :author "ash@lid"
                       :mentionedIds #js []
                       :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm #js {:listTrades (fn [_] (p/rejected (js/Error. "JVM indisponível")))})
      (-> (isolado! #(http/executar message "pokemon" "troca"))
          (p/then (fn [_]
                    (is (= 1 (count @respostas)))
                    (is (string? (first @respostas)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest troca-nao-repete-efeito-quando-envio-whatsapp-falha
  (async done
    (let [envios (atom []) operacoes (atom #{}) chamadas (atom 0)
          original @http/cliente-jvm original-config http/jvm-configurado?
          message #js {:id #js {:_serialized "trade-delivery-failure"} :from "grupo@g.us" :author "ash@lid"
                       :mentionedIds #js [#js {:_serialized "misty@lid"}]
                       :reply (fn [texto & _]
                                (swap! envios conj texto)
                                (p/rejected (js/Error. "WhatsApp indisponível")))}
          client #js {:getActiveSave (fn [_] (p/resolved #js {:activeSaveId "save"
                                                              :fenceGeneration 1}))
                      :getCollection (fn [_] (p/resolved #js {:collection #js [#js {:pokemonId "p1"}]}))
                      :createTrade (fn [body]
                                     (swap! chamadas inc)
                                     (swap! operacoes conj (.-operationId body))
                                     (p/resolved #js {:tradeId "t1"}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado! #(http/executar message "pokemon" "troca 1 1 @misty"))
          (p/then (fn [_]
                    ;; Uma falha de transporte não reexecuta a criação.
                    (is (= 1 @chamadas))
                    (is (= 1 (count @operacoes)))
                    (is (= 1 (count @envios)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest troca-retry-reutiliza-operation-id-e-entrega-uma-resposta
  (async done
    (let [envios (atom []) operacoes (atom [])
          original @http/cliente-jvm original-config http/jvm-configurado?
          message #js {:id #js {:_serialized "trade-retry"} :from "grupo@g.us" :author "ash@lid"
                       :mentionedIds #js [#js {:_serialized "misty@lid"}]
                       :reply (fn [texto & _] (swap! envios conj texto) (p/resolved nil))}
          client #js {:getActiveSave (fn [_] (p/resolved #js {:activeSaveId "save"
                                                              :fenceGeneration 1}))
                      :getCollection (fn [_] (p/resolved #js {:collection #js [#js {:pokemonId "p1"}]}))
                      :createTrade (fn [body]
                                     (swap! operacoes conj (.-operationId body))
                                     (p/resolved #js {:tradeId "t1"}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (p/let [_ (http/executar message "pokemon" "troca 1 1 @misty")
                     _ (http/executar message "pokemon" "troca 1 1 @misty")]
               ;; O recibo global vê a mesma operação; a entrega local também
               ;; não duplica a mensagem já confirmada.
               (is (= 2 (count @operacoes)))
               (is (= 1 (count (set @operacoes))))
               (is (= 1 (count @envios))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest perfil-professor-titulo-amizade-rota-evento-whatsapp
  (async done
    (let [respostas (atom []) original @http/cliente-jvm original-config http/jvm-configurado?
          message (fn [id] #js {:id #js {:_serialized id} :from "grupo@g.us" :author "ash@lid"
                                 :mentionedIds #js []
                                 :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})
          client #js {:getActiveSave (fn [_] (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))
                      :getProfessorCards (fn [_] (p/resolved #js {:cards #js {:pidgey 1}}))
                      :getTitles (fn [_] (p/resolved #js {:selected "batalhador"
                                                           :titles #js [#js {:label "Batalhador"}]}))
                      :getFriendship (fn [_] (p/resolved #js {:species "Pidgey" :value 220
                                                               :label "melhor amigo"}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (p/let [_ (http/executar (message "perfil-professor") "pokemon" "professor cartoes")
                     _ (http/executar (message "perfil-titulo") "pokemon" "titulo")
                     _ (http/executar (message "perfil-amizade") "pokemon" "amizade 1")]
               (is (= 3 (count @respostas)))
               (is (some #(re-find #"Cartões" %) @respostas))
               (is (some #(re-find #"Batalhador" %) @respostas))
               (is (some #(re-find #"220/255" %) @respostas)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest shiny-pc-espaco-rota-jvm-canonica
  (async done
    (let [respostas (atom []) original @http/cliente-jvm original-config http/jvm-configurado?
          message (fn [id] #js {:id #js {:_serialized id} :from "grupo@g.us" :author "ash@lid"
                                 :mentionedIds #js []
                                 :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})
          collection #js {:saveId "a" :count 2 :collection #js [#js {:collectionIndex 0 :idPokemon "p1" :nome "Pikachu" :shiny true :nivel 5}
                                                                    #js {:collectionIndex 1 :idPokemon "p2" :nome "Rattata" :shiny false :nivel 3}]}
          client #js {:getCollection (fn [_] (p/resolved collection))
                      :getShop (fn [_] (p/resolved #js {:capacidadePokemon 30 :ocupacaoPokemon 2 :expansoesPokemon50 0}))
                      :getActiveSave (fn [_] (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))
                      :buyItem (fn [_] (p/resolved #js {:expansao true}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (p/let [_ (http/executar (message "shiny") "pokemon" "shiny")
                     _ (http/executar (message "pc") "pokemon" "computador")
                     _ (http/executar (message "space") "pokemon" "espaco")
                     _ (http/executar (message "space-buy") "pokemon" "espaco comprar")]
               (is (= 4 (count @respostas)))
               (is (some #(re-find #"Pikachu" %) @respostas))
               (is (some #(re-find #"PC Pokémon" %) @respostas))
               (is (some #(re-find #"2/30" %) @respostas))
               (is (some #(re-find #"50 vagas" %) @respostas)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))
