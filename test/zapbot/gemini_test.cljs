(ns zapbot.gemini-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]
            [zapbot.ia-identidade :as identidade]))

(deftest chamada-do-wrapper-inclui-identidade-sem-misturar-prompt
  (async done
    (let [pedidos (atom [])
          prompt "Quem é você?"
          requisitar (fn [_ opcoes]
                       (swap! pedidos conj (js->clj (js/JSON.parse (.-body opcoes)) :keywordize-keys true))
                       (p/resolved #js {:ok true :status 200
                                        :json (fn [] (p/resolved #js {:candidates #js [#js {:content #js {:parts #js [#js {:text "Sou Odisseu."}]}}]}))}))]
      (-> (with-redefs [config/gemini-api-key "fixture" gemini/requisitar! requisitar]
            (gemini/gerar-texto prompt))
          (p/then (fn [resposta]
                    (is (= "Sou Odisseu." resposta))
                    (is (= 1 (count @pedidos)))
                    (is (= (identidade/pedido prompt) (first @pedidos)))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest novas-tentativas-preservam-identidade-e-pergunta
  (async done
    (let [pedidos (atom [])
          prompt "Explique desigualdade social."
          requisitar (fn [_ opcoes]
                       (swap! pedidos conj (js->clj (js/JSON.parse (.-body opcoes)) :keywordize-keys true))
                       (let [status (case (count @pedidos) 1 429 2 503 200)]
                         (p/resolved #js {:ok (= 200 status) :status status
                                          :json (fn [] (p/resolved #js {:candidates #js [#js {:content #js {:parts #js [#js {:text "Resposta."}]}}]}))})))]
      (-> (with-redefs [gemini/requisitar! requisitar] (gemini/gerar-texto prompt))
          (p/then (fn [resposta]
                    (is (= "Resposta." resposta))
                    (is (= 3 (count @pedidos)))
                    (is (every? #(= (identidade/pedido prompt) %) @pedidos))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))
