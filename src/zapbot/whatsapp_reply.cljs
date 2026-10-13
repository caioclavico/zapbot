(ns zapbot.whatsapp-reply
  "Compatibilidade do ID citado por Message.reply entre versões do WhatsApp."
  (:require [clojure.string :as str]))

(defn preparar! [message]
  ;; A biblioteca fixada usa id._serialized em reply; WhatsApp recente usa $1.
  ;; Copia o ID para preservar os campos originais sem alterar o objeto recebido.
  (let [id (.-id message)
        candidatos (if (string? id) [id]
                       (when id [(.-_serialized id) (aget id "$1")]))
        canonico (first (filter #(and (string? %) (not (str/blank? %))) candidatos))]
    (when (and canonico (not= canonico (when id (.-_serialized id))))
      (set! (.-id message)
            (js/Object.assign #js {} (if (string? id) #js {} id)
                              #js {:_serialized canonico})))
    message))
