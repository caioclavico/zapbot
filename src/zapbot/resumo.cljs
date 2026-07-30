(ns zapbot.resumo
  "Comando !resuma - resume as últimas mensagens do chat usando a API Gemini."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.historico :as historico]))

(defn- comando? [texto]
  (str/starts-with? (str/trim (or texto "")) config/prefix))

(defn- montar-transcricao [mensagens]
  (->> mensagens
       (remove (fn [{:keys [corpo]}] (or (str/blank? corpo) (comando? corpo))))
       (map (fn [{:keys [autor corpo]}] (str autor ": " corpo)))
       (str/join "\n")))

(defn- gerar-resumo [transcricao]
  (let [url  "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
        texto (str "Resuma em português, de forma objetiva e em poucos parágrafos, "
                   "a conversa de WhatsApp abaixo:\n\n" transcricao)
        corpo #js {:contents #js [#js {:parts #js [#js {:text texto}]}]}]
    (-> (p/let [res  (js/fetch url #js {:method  "POST"
                                         :headers #js {"Content-Type"  "application/json"
                                                        "x-goog-api-key" config/gemini-api-key}
                                         :body    (js/JSON.stringify corpo)})
                data (.json res)
                data (js->clj data :keywordize-keys true)]
          (if-let [resumo (get-in data [:candidates 0 :content :parts 0 :text])]
            (str "📝 *Resumão do tio " config/bot-name "*\n\n" resumo)
            "❌ Não consegui gerar o resumo agora."))
        (p/catch (fn [err]
                   (js/console.error "Erro ao gerar resumo:" err)
                   "❌ Não consegui gerar o resumo agora. Tente novamente mais tarde.")))))

(defn resumir-chat [message]
  (if (str/blank? config/gemini-api-key)
    (p/resolved
     (str "⚠️ Comando indisponível: configure GEMINI_API_KEY no .env "
          "(chave gratuita em https://aistudio.google.com/apikey)."))
    (let [transcricao (montar-transcricao (historico/obter message))]
      (if (str/blank? transcricao)
        (p/resolved
         (str "❓ Ainda não tenho mensagens suficientes desse chat para resumir "
              "(só vejo o que foi enviado depois que eu liguei)."))
        (gerar-resumo transcricao)))))
