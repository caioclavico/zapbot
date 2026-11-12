(ns zapbot.pokemon-mt-http-test
  "Handlers reais !pk mt — identidade canônica, consumo e recuperação."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [promesa.core :as p]
            [zapbot.pokemon-http :as http]
            [zapbot.armazenamento :as armazenamento]))

(defn- isolado! [executar]
  (let [originais @http/entregas
        mts @http/mts-pendentes
        salvar armazenamento/salvar!
        salvar-c armazenamento/salvar-confirmado!]
    (reset! http/entregas {})
    (reset! http/mts-pendentes {})
    (set! armazenamento/salvar! (fn [& _] (p/resolved nil)))
    (set! armazenamento/salvar-confirmado! (fn [& _] (p/resolved true)))
    (-> (p/resolved nil)
        (p/then (fn [_] (executar)))
        (p/finally (fn []
                     (reset! http/entregas originais)
                     (reset! http/mts-pendentes mts)
                     (set! armazenamento/salvar! salvar)
                     (set! armazenamento/salvar-confirmado! salvar-c))))))

(defn- wartortle [id]
  #js {:nome "Wartortle"
       :species "wartortle"
       :idPokemon id
       :nivel 16
       :tipos #js ["water"]
       :golpes #js [#js {:slug "tackle" :nomeExibicao "Investida" :tipo "normal"
                         :poder 40 :classe "fisico"}
                    #js {:slug "water-gun" :nomeExibicao "Jato de Água" :tipo "water"
                         :poder 40 :classe "especial"}
                    #js {:slug "bite" :nomeExibicao "Mordida" :tipo "dark"
                         :poder 60 :classe "fisico"}
                    #js {:slug "rapid-spin" :nomeExibicao "Pirouete" :tipo "normal"
                         :poder 50 :classe "fisico"}]})

(defn- mensagem [id]
  #js {:id #js {:_serialized id}
       :from "grupo@g.us" :author "ash@lid"
       :mentionedIds #js []
       :reply (fn [_texto & _] (p/resolved nil))})

(deftest mt-ajuda-e-sem-mt
  (async done
    (let [respostas (atom [])
          original @http/cliente-jvm
          original-config http/jvm-configurado?
          calls (atom [])
          client #js {:getInventory (fn [_] (p/resolved #js {:itens #js []}))
                      :getCollection (fn [_]
                                       (p/resolved #js {:collection #js [(wartortle "w1")]}))
                      :getActiveSave (fn [_]
                                       (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (let [msg (fn [rid]
                         #js {:id #js {:_serialized rid}
                              :from "grupo@g.us" :author "ash@lid"
                              :mentionedIds #js []
                              :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
               (p/let [_ (http/executar (msg "mt-help") "pokemon" "mt")
                       _ (http/executar (msg "mt-usar-sem") "pokemon" "mt usar 1")]
                 (is (= 2 (count @respostas)))
                 (is (re-find #"Máquinas Técnicas" (first @respostas)))
                 (is (re-find #"não tem MT|Compre" (second @respostas)))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest mt-usar-slot-confirmar-envia-id-canonico-e-consumeItemId
  (async done
    (let [respostas (atom [])
          bodies (atom [])
          original @http/cliente-jvm
          original-config http/jvm-configurado?
          client #js {:getInventory (fn [_]
                                      (p/resolved #js {:itens #js [#js {:itemId "mt" :qty 2}]}))
                      :getCollection (fn [_]
                                       (p/resolved #js {:collection #js [(wartortle "id-wart-1")]}))
                      :getActiveSave (fn [_]
                                       (p/resolved #js {:activeSaveId "a" :fenceGeneration 3}))
                      :learnMove (fn [body]
                                   (swap! bodies conj (js->clj body :keywordize-keys true))
                                   (p/resolved #js {:pokemon #js {:nome "Wartortle" :idPokemon "id-wart-1"}
                                                    :golpeAnterior #js {:slug "tackle"
                                                                        :nomeExibicao "Investida"}
                                                    :golpeNovo #js {:slug "water-pulse"
                                                                    :nomeExibicao "Pulso d'Água"}
                                                    :itemQtyAfter 1
                                                    :consumeItemId "mt"
                                                    :duplicate false}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (let [msg (fn [rid]
                         #js {:id #js {:_serialized rid}
                              :from "grupo@g.us" :author "ash@lid"
                              :mentionedIds #js []
                              :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
               (p/let [_ (http/executar (msg "mt-u") "pokemon" "mt usar 1 1")
                       _ (http/executar (msg "mt-c") "pokemon" "mt confirmar")]
                 (is (re-find #"pendente|Troca de golpe" (first @respostas)))
                 (is (re-find #"MÁQUINA TÉCNICA UTILIZADA" (second @respostas)))
                 (is (= 1 (count @bodies)))
                 (let [b (first @bodies)]
                   (is (= "id-wart-1" (:idPokemon b)))
                   (is (= "mt" (:consumeItemId b)))
                   (is (= 0 (:slot b)))
                   (is (= "tackle" (:expectedSlotSlug b)))
                   (is (string? (:operationId b)))
                   (is (re-find #"^mt-confirm:" (:operationId b)))
                   (is (= "mt-learnset-v1-2026-11" (:catalogVersion b))))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest mt-cancelar-nao-chama-learnMove
  (async done
    (let [respostas (atom []) learn (atom 0)
          original @http/cliente-jvm original-config http/jvm-configurado?
          client #js {:getInventory (fn [_]
                                      (p/resolved #js {:itens #js [#js {:itemId "mt" :qty 1}]}))
                      :getCollection (fn [_]
                                       (p/resolved #js {:collection #js [(wartortle "w1")]}))
                      :getActiveSave (fn [_]
                                       (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))
                      :learnMove (fn [_] (swap! learn inc) (p/resolved #js {}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (let [msg (fn [rid]
                         #js {:id #js {:_serialized rid}
                              :from "grupo@g.us" :author "ash@lid"
                              :mentionedIds #js []
                              :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
               (p/let [_ (http/executar (msg "u") "pokemon" "mt usar 1 2")
                       _ (http/executar (msg "x") "pokemon" "mt cancelar")]
                 (is (zero? @learn))
                 (is (re-find #"cancelada|Nenhuma MT consumida" (last @respostas)))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest mt-confirmar-duplicado-reusa-mesmo-operation-id
  (async done
    (let [ops (atom [])
          respostas (atom [])
          original @http/cliente-jvm original-config http/jvm-configurado?
          client #js {:getInventory (fn [_]
                                      (p/resolved #js {:itens #js [#js {:itemId "mt" :qty 1}]}))
                      :getCollection (fn [_]
                                       (p/resolved #js {:collection #js [(wartortle "w1")]}))
                      :getActiveSave (fn [_]
                                       (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))
                      :learnMove (fn [body]
                                   (swap! ops conj (.-operationId body))
                                   (p/resolved #js {:duplicate true
                                                    :pokemon #js {:nome "Wartortle"}
                                                    :golpeAnterior #js {:nomeExibicao "Investida"}
                                                    :golpeNovo #js {:nomeExibicao "Pulso d'Água"}
                                                    :itemQtyAfter 0
                                                    :consumeItemId "mt"}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (let [msg (fn [rid]
                         #js {:id #js {:_serialized rid}
                              :from "grupo@g.us" :author "ash@lid"
                              :mentionedIds #js []
                              :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
               (p/let [_ (http/executar (msg "u") "pokemon" "mt usar 1 1")
                       op-antes (get-in @http/mts-pendentes ["grupo@g.us|ash@lid" :operation-id])
                       _ (http/executar (msg "c1") "pokemon" "mt confirmar")
                       ;; Segunda confirmação: pendência já limpa → mensagem sem learnMove.
                       _ (http/executar (msg "c2") "pokemon" "mt confirmar")]
                 (is (string? op-antes))
                 (is (= [op-antes] @ops))
                 (is (re-find #"MÁQUINA TÉCNICA" (first (filter #(re-find #"MÁQUINA" %) @respostas))))
                 (is (re-find #"Nenhuma MT pendente" (last @respostas)))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest mt-pokemon-inexistente-e-slot-invalido
  (async done
    (let [respostas (atom [])
          original @http/cliente-jvm original-config http/jvm-configurado?
          client #js {:getInventory (fn [_]
                                      (p/resolved #js {:itens #js [#js {:itemId "mt" :qty 1}]}))
                      :getCollection (fn [_]
                                       (p/resolved #js {:collection #js [(wartortle "w1")]}))
                      :getActiveSave (fn [_]
                                       (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (let [msg (fn [rid]
                         #js {:id #js {:_serialized rid}
                              :from "grupo@g.us" :author "ash@lid"
                              :mentionedIds #js []
                              :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
               (p/let [_ (http/executar (msg "x") "pokemon" "mt usar 9")
                       _ (http/executar (msg "y") "pokemon" "mt usar 1 9")]
                 (is (re-find #"número da coleção|time" (first @respostas)))
                 (is (re-find #"Slot inválido" (second @respostas)))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))

(deftest mt-pendencia-sobrevive-simulado-restart-atom-reload
  (async done
    (let [respostas (atom []) bodies (atom [])
          original @http/cliente-jvm original-config http/jvm-configurado?
          client #js {:getInventory (fn [_]
                                      (p/resolved #js {:itens #js [#js {:itemId "mt" :qty 1}]}))
                      :getCollection (fn [_]
                                       (p/resolved #js {:collection #js [(wartortle "w1")]}))
                      :getActiveSave (fn [_]
                                       (p/resolved #js {:activeSaveId "a" :fenceGeneration 1}))
                      :learnMove (fn [body]
                                   (swap! bodies conj (.-operationId body))
                                   (p/resolved #js {:pokemon #js {:nome "Wartortle"}
                                                    :golpeAnterior #js {:nomeExibicao "Investida"}
                                                    :golpeNovo #js {:nomeExibicao "Pulso d'Água"}
                                                    :itemQtyAfter 0}))}]
      (set! http/jvm-configurado? (fn [] true))
      (reset! http/cliente-jvm client)
      (-> (isolado!
           (fn []
             (let [msg (fn [rid]
                         #js {:id #js {:_serialized rid}
                              :from "grupo@g.us" :author "ash@lid"
                              :mentionedIds #js []
                              :reply (fn [texto & _] (swap! respostas conj texto) (p/resolved nil))})]
               (p/let [_ (http/executar (msg "u") "pokemon" "mt usar 1 1")
                       snap @http/mts-pendentes
                       ;; Simula restart: limpa atom e reidrata do snapshot persistido.
                       _ (reset! http/mts-pendentes {})
                       _ (reset! http/mts-pendentes snap)
                       _ (http/executar (msg "c") "pokemon" "mt confirmar")]
                 (is (= 1 (count @bodies)))
                 (is (re-find #"MÁQUINA TÉCNICA" (last @respostas)))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn []
                       (set! http/jvm-configurado? original-config)
                       (reset! http/cliente-jvm original)
                       (done)))))))
