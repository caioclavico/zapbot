(ns zapbot.core-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [zapbot.recursos :as recursos]
            [zapbot.desempenho :as desempenho]
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

(deftest diagnostico-separa-midia-e-texto-extra-sem-mudar-ordem
  (async done
    (let [chamadas (atom []) logs (atom [])
          media #js {:mimetype "image/png" :data "imagem-base64"}
          texto (apply str (repeat 901 "x"))
          message (mensagem-com-reply chamadas (fn [_] (js/Promise.resolve nil)))]
      (-> (desempenho/acompanhar! message
            (fn [_] (core/responder-com-midia message {:media media :texto texto}))
            #(swap! logs conj %))
          (.then (fn [_]
                   (is (= 2 (count @chamadas)))
                   (is (identical? media (ffirst @chamadas)))
                   (is (= texto (first (second @chamadas))))
                   (is (= #{"envio_midia" "envio_texto_extra"}
                          (set (keys (:etapas_ms (last @logs))))))
                   (is (empty? (:pendentes (last @logs))))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

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

(deftest main-impede-duas-inicializacoes-inclusive-apos-erro
  (async done
    (let [criados (atom 0) inicializados (atom 0) monitores (atom 0)
          client #js {:on (fn [& _]) :sendMessage (fn [& _])
                      :initialize (fn []
                                    (swap! inicializados inc)
                                    (js/Promise.reject (js/Error. "falha simulada")))}
          guarda (atom false)
          resultado
          (with-redefs [core/iniciado? guarda
                        core/Client (fn [_] (swap! criados inc) client)
                        core/LocalAuth (fn [] #js {})
                        core/encerrar-com-sessao! (fn [& _])
                        recursos/iniciar! (fn [recebido]
                                            (is (identical? client recebido))
                                            (swap! monitores inc))
                        zapbot.whatsapp-saude/servir! (fn [& _])
                        zapbot.armazenamento/iniciar! (fn [] (js/Promise.resolve nil))]
            (let [primeira (core/main)]
              (is (nil? (core/main)))
              primeira))]
      (-> resultado
          (.then (fn [_]
                   (is (= 1 @criados))
                   (is (= 1 @inicializados))
                   (is (= 1 @monitores))
                   (with-redefs [core/iniciado? guarda]
                     (is (nil? (core/main))))))
          (.catch (fn [erro] (is false (str erro))))
          (.finally done)))))

(deftest flag-gpu-reversivel-preserva-demais-opcoes
  (let [padrao (with-redefs [zapbot.config/chromium-disable-gpu false]
                 (core/opcoes-puppeteer))
        teste (with-redefs [zapbot.config/chromium-disable-gpu true]
                (core/opcoes-puppeteer))]
    (is (= 300000 (:protocolTimeout teste)))
    (is (= (conj (js->clj (:args padrao)) "--disable-gpu")
           (js->clj (:args teste))))
    (is (= (dissoc padrao :args) (dissoc teste :args)))))

(deftest modo-chromium-reversivel-preserva-flags-gpu-timeout-e-executavel
  (doseq [modo? [false true]
          gpu? [false true]
          executavel [nil "/usr/bin/chromium"]]
    (let [opcoes (with-redefs [zapbot.config/chromium-low-resource-mode modo?
                              zapbot.config/chromium-disable-gpu gpu?
                              zapbot.config/puppeteer-executable-path executavel]
                   (core/opcoes-puppeteer))
          esperado (cond-> ["--no-sandbox" "--disable-setuid-sandbox"
                             "--disable-quic" "--disable-features=Quic"]
                     modo? (into ["--disable-extensions" "--disable-default-apps" "--no-first-run"])
                     gpu? (conj "--disable-gpu"))]
      (is (js/Array.isArray (:args opcoes)))
      (is (= esperado (js->clj (:args opcoes))))
      (is (= 300000 (:protocolTimeout opcoes)))
      (is (= (cond-> {:protocolTimeout 300000}
               executavel (assoc :executablePath executavel))
             (dissoc opcoes :args))))))

(deftest latencia-mede-apenas-comandos-permitidos-preservando-contexto-pokemon
  (let [medidos (atom 0) processados (atom []) ctx #js {:teste true}]
    (with-redefs [zapbot.config/app-env "development"
                  zapbot.config/dev-group-id "teste@g.us"
                  zapbot.config/prefix "!"
                  recursos/medir-comando! (fn [executar] (swap! medidos inc) (executar))
                  desempenho/acompanhar-mensagem! (fn [_ executar] (executar ctx))
                  core/processar-mensagem (fn [message contexto]
                                            (swap! processados conj [(.-body message) contexto])
                                            "resultado")]
      (doseq [body ["!pk treinador" "  !ping" "mensagem normal" nil]]
        (is (= "resultado" (core/on-message #js {:from "teste@g.us" :body body}))))
      (is (= "resultado" (core/on-message #js {:from "outro@g.us" :body "!pk treinador"})))
      (is (= 2 @medidos))
      (is (identical? ctx (second (first @processados))))
      (is (every? nil? (map second (rest @processados)))))))
