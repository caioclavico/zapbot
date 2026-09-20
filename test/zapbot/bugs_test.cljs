(ns zapbot.bugs-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [zapbot.bugs :as bugs]
            [zapbot.historico :as historico]))

(defn- mensagem []
  #js {:body "!pk bug" :from "grupo@g.us" :author "jogador@c.us"})

(deftest comando-bug-persiste-mensagem-e-versao
  (async done
    (let [citada #js {:body "[object Object]" :from "bot@c.us" :timestamp 123}
          message #js {:body "!pk bug" :from "grupo@g.us" :author "jogador@c.us"
                       :hasQuotedMsg true
                       :getQuotedMessage (fn [] (js/Promise.resolve citada))}]
      (-> (bugs/comando! message "bug" [])
          (.then (fn [texto]
                   (let [item (first (filter #(= "[object Object]" (get % "mensagem"))
                                             (vals @bugs/relatorios)))]
                     (is (= "[object Object]" (get item "mensagem")))
                     (is (= "0.15.3" (get item "versao")))
                     (is (= "citada" (get item "origem")))
                     (is (str/includes? texto "Bug registrado"))
                     (done))))
          (.catch (fn [erro]
                    (is false (str "Falha ao registrar bug: " erro))
                    (done)))))))

(deftest consulta-de-bugs-exige-administrador
  (async done
    ;; Conversa direta sem número cadastrado em ADMIN_NUMBERS não é admin e
    ;; não precisa consultar os metadados de participantes do WhatsApp.
    (-> (bugs/comando! #js {:body "!pk bugs" :from "jogador@c.us"} "bugs" [])
        (.then (fn [texto]
                 (is (str/includes? texto "Apenas administradores"))
                 (done)))
        (.catch (fn [erro]
                  (is false (str "Falha ao conferir autorização: " erro))
                  (done))))))

(deftest mensagem-respondida-tem-prioridade-sobre-a-anterior
  (async done
    (let [citada #js {:body "Ataque do líder: [object Object]"
                      :from "bot@c.us" :timestamp 1700000000}
          message #js {:body "!pk bug" :from "grupo@g.us" :author "jogador@c.us"
                       :hasQuotedMsg true
                       :getQuotedMessage (fn [] (js/Promise.resolve citada))}]
      (with-redefs [historico/mensagem-anterior (fn [_] {:corpo "não deve ser usada"})]
        (-> (bugs/mensagem-alvo message)
            (.then (fn [alvo]
                     (is (= "citada" (:origem alvo)))
                     (is (= "Ataque do líder: [object Object]" (:corpo alvo)))
                     (is (= 1700000000000 (:em alvo)))
                     (done)))
            (.catch (fn [erro]
                      (is false (str "Falha ao ler mensagem respondida: " erro))
                      (done))))))))
