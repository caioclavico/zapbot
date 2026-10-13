(ns zapbot.gemini-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]
            [zapbot.ia-identidade :as identidade]
            [zapbot.pergunta :as pergunta]
            [zapbot.whatsapp-reply :as reply]))

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
      (-> (with-redefs [config/gemini-api-key "fixture" config/ai-retry-base-ms 1 gemini/requisitar! requisitar] (gemini/gerar-texto prompt))
          (p/then (fn [resposta]
                    (is (= "Resposta." resposta))
                    (is (= 3 (count @pedidos)))
                    (is (every? #(= (identidade/pedido prompt) %) @pedidos))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))


(deftest groq-fallback-da-pergunta-preserva-reply-e-identidade
  (async done
    (let [pedidos (atom []) envios (atom [])
          message #js {:id #js {:$1 "false_grupo_pergunta_ash"}}
          requisitar (fn [url opcoes]
                       (swap! pedidos conj (js->clj (js/JSON.parse (.-body opcoes)) :keywordize-keys true))
                       (if (.includes url "googleapis.com")
                         (p/resolved #js {:ok false :status 401})
                         (p/resolved #js {:ok true :status 200
                                          :json (fn [] (p/resolved #js {:choices #js [#js {:message #js {:content "Resposta via Groq."}}]}))})))]
      (set! (.-reply message) (fn [texto] (swap! envios conj [texto (.. message -id -_serialized)])))
      (reply/preparar! message)
      (-> (with-redefs [config/gemini-api-key "fixture" config/groq-api-key "fixture"
                        gemini/requisitar! requisitar]
            (pergunta/perguntar message "Minha pergunta"))
          (p/then (fn [texto]
                    (.reply message texto)
                    (is (= 2 (count @pedidos)))
                    (is (= identidade/instrucao-sistema (get-in (last @pedidos) [:messages 0 :content])))
                    (is (.includes (get-in (last @pedidos) [:messages 1 :content]) "Minha pergunta"))
                    (is (.includes (ffirst @envios) "Resposta via Groq."))
                    (is (= "false_grupo_pergunta_ash" (second (first @envios))))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest pergunta-informa-indisponibilidade-de-ambos-os-provedores
  (async done
    (-> (with-redefs [config/gemini-api-key "fixture" config/groq-api-key "fixture"
                      gemini/requisitar! (fn [& _] (p/resolved #js {:ok false :status 401}))]
          (pergunta/perguntar #js {} "Minha pergunta"))
        (p/then (fn [texto] (is (= "⚠️ IA temporariamente indisponível. Tente novamente em instantes." texto))))
        (p/catch (fn [erro] (is false (str erro))))
        (p/finally done))))
