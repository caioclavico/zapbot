(ns zapbot.gemini
  "Wrapper fino para a API Gemini (generateContent), usado por !resuma e !pergunta.

  Usa a chave no formato \"auth key\" (AQ....) do Google AI Studio, que precisa
  ir no header x-goog-api-key (não em ?key= na URL)."
  (:require [promesa.core :as p]
            [zapbot.config :as config]))

(defn gerar-texto
  "Envia o prompt ao Gemini e retorna uma promise com o texto da resposta,
  ou nil se não houver nenhum candidato na resposta."
  [prompt]
  (let [url   "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
        corpo #js {:contents #js [#js {:parts #js [#js {:text prompt}]}]}]
    (p/let [res  (js/fetch url #js {:method  "POST"
                                     :headers #js {"Content-Type"   "application/json"
                                                    "x-goog-api-key" config/gemini-api-key}
                                     :body    (js/JSON.stringify corpo)})
            data (.json res)
            data (js->clj data :keywordize-keys true)]
      (get-in data [:candidates 0 :content :parts 0 :text]))))
