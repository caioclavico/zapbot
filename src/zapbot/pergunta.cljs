(ns zapbot.pergunta
  "Comando !pergunta - responde perguntas livres usando a API Gemini."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]))

(defn perguntar [pergunta]
  (cond
    (str/blank? config/gemini-api-key)
    (p/resolved
     (str "⚠️ Comando indisponível: configure GEMINI_API_KEY no .env "
          "(chave gratuita em https://aistudio.google.com/apikey)."))

    (str/blank? pergunta)
    (p/resolved
     (str "❓ Faça uma pergunta depois do comando, ex.: "
          config/prefix "pergunta qual a capital do Japão?"))

    :else
    (-> (gemini/gerar-texto
         (str "Responda de forma clara e objetiva, em português, à pergunta abaixo:\n\n" pergunta))
        (p/then (fn [resposta]
                  (if resposta
                    (str "🤔 *Pergunta ao tio " config/bot-name "*\n\n" resposta)
                    "❌ Não consegui pensar em uma resposta agora.")))
        (p/catch (fn [err]
                   (js/console.error "Erro ao responder pergunta:" err)
                   "❌ Não consegui pensar em uma resposta agora. Tente novamente mais tarde.")))))
