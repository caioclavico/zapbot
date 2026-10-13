(ns zapbot.whatsapp-reply-test
  (:require [cljs.test :refer-macros [deftest is]]
            [zapbot.whatsapp-reply :as reply]))

(deftest resposta-cita-a-pergunta-com-id-atual
  (let [id #js {:$1 "false_grupo@g.us_pergunta_ash@lid" :id "pergunta"}
        envios (atom [])
        message #js {:id id :from "grupo@g.us"}]
    ;; Mesmo contrato de Message.reply na versão fixada: lê _serialized.
    (set! (.-reply message)
          (fn [texto] (swap! envios conj [texto (.. message -id -_serialized)])))
    (is (identical? message (reply/preparar! message)))
    (.reply message "Resposta do Odisseu")
    (is (= [["Resposta do Odisseu" "false_grupo@g.us_pergunta_ash@lid"]] @envios))
    (is (= "pergunta" (.. message -id -id)))
    (is (nil? (.-_serialized id)))))

(deftest ids-legados-diretos-e-opcoes-invalidas
  (let [id #js {:_serialized "legado" :$1 "atual"}
        message #js {:id id}]
    (reply/preparar! message)
    (is (identical? id (.-id message)))
    (is (= "legado" (.. message -id -_serialized))))
  (let [message #js {:id "direto"}]
    (reply/preparar! message)
    (is (= "direto" (.. message -id -_serialized))))
  (doseq [id [nil #js {} #js {:$1 ""}]]
    (let [message #js {:id id}]
      (is (identical? message (reply/preparar! message)))
      (is (identical? id (.-id message))))))
