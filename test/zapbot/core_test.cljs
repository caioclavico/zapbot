(ns zapbot.core-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [zapbot.core :as core]))

(defn- mensagem-com-reply [chamadas responder]
  #js {:reply (fn [& args]
                (swap! chamadas conj (vec args))
                (responder (count @chamadas)))})

(deftest envio-seguro-desativa-send-seen-sem-perder-opcoes
  (let [chamadas (atom [])
        client #js {:sendMessage
                    (fn [chat-id conteudo opcoes]
                      (swap! chamadas conj [chat-id conteudo opcoes])
                      "enviado")}
        opcoes #js {:caption "Teste" :sendSeen true}]
    (is (identical? client (core/configurar-envio-seguro! client)))
    (is (= "enviado" (.sendMessage client "grupo@g.us" "Oi" opcoes)))
    (is (= "enviado" (.sendMessage client "outro@g.us" "Olá")))
    (let [[[chat-id conteudo opcoes-seguras]
           [_ _ opcoes-sem-entrada]] @chamadas]
      (is (= "grupo@g.us" chat-id))
      (is (= "Oi" conteudo))
      (is (= "Teste" (.-caption opcoes-seguras)))
      (is (false? (.-sendSeen opcoes-seguras)))
      (is (false? (.-sendSeen opcoes-sem-entrada)))
      ;; Não altera o objeto recebido do chamador.
      (is (true? (.-sendSeen opcoes))))))

(deftest resposta-longa-envia-midia-com-legenda-curta-e-texto-separado
  (async done
    (let [chamadas (atom [])
          media #js {:mimetype "image/png" :data "imagem-base64"}
          texto (apply str (repeat 901 "x"))
          message (mensagem-com-reply chamadas
                                      (fn [_] (js/Promise.resolve nil)))]
      (-> (core/responder-com-midia
           message {:media media
                    :texto texto
                    :legenda "🧢 Perfil do treinador"
                    :mentions ["123@c.us"]})
          (.then (fn [_]
                   (let [[envio-midia envio-texto] @chamadas
                         opcoes-midia (nth envio-midia 2)
                         opcoes-texto (nth envio-texto 2)]
                     (is (= 2 (count @chamadas)))
                     (is (identical? media (first envio-midia)))
                     (is (= "🧢 Perfil do treinador" (.-caption opcoes-midia)))
                     (is (< (count (.-caption opcoes-midia)) 900))
                     (is (= texto (first envio-texto)))
                     (is (= ["123@c.us"] (js->clj (.-mentions opcoes-midia))))
                     (is (= ["123@c.us"] (js->clj (.-mentions opcoes-texto))))
                     (done))))
          (.catch (fn [erro]
                    (is false (str "Falha ao separar texto longo da mídia: " erro))
                    (done)))))))

(deftest resposta-curta-envia-os-dados-na-legenda-da-propria-imagem
  (async done
    (let [chamadas (atom [])
          media #js {:mimetype "image/png" :data "imagem-base64"}
          texto "🧢 Dados completos do treinador"
          message (mensagem-com-reply chamadas (fn [_] (js/Promise.resolve nil)))]
      (-> (core/responder-com-midia
           message {:media media :texto texto :legenda "legenda alternativa"})
          (.then (fn [_]
                   (let [[envio] @chamadas]
                     (is (= 1 (count @chamadas)))
                     (is (identical? media (first envio)))
                     (is (= texto (.-caption (nth envio 2))))
                     (done))))
          (.catch (fn [erro]
                    (is false (str "Falha ao usar os dados como legenda: " erro))
                    (done)))))))

(deftest falha-no-envio-da-midia-faz-fallback-para-texto
  (async done
    (let [chamadas (atom [])
          media #js {:mimetype "image/png" :data "imagem-base64"}
          texto "Ficha do treinador em texto"
          message (mensagem-com-reply
                   chamadas
                   (fn [numero-da-chamada]
                     (if (= 1 numero-da-chamada)
                       (js/Promise.reject (js/Error. "mídia recusada"))
                       (js/Promise.resolve nil))))]
      (-> (core/responder-com-midia message {:media media :texto texto})
          (.then (fn [_]
                   (let [[envio-midia envio-texto] @chamadas]
                     (is (= 2 (count @chamadas)))
                     (is (identical? media (first envio-midia)))
                     (is (= texto (.-caption (nth envio-midia 2))))
                     (is (= texto (first envio-texto)))
                     (done))))
          (.catch (fn [erro]
                    (is false (str "O fallback textual também falhou: " erro))
                    (done)))))))
