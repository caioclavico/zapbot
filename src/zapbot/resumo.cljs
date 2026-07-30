(ns zapbot.resumo
  "Comando !resuma - resume as últimas mensagens do chat usando a API Gemini."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.historico :as historico]
            [zapbot.gemini :as gemini]))

(defn- comando? [texto]
  (str/starts-with? (str/trim (or texto "")) config/prefix))

(defn- montar-transcricao [mensagens]
  (->> mensagens
       (remove (fn [{:keys [corpo]}] (or (str/blank? corpo) (comando? corpo))))
       (map (fn [{:keys [autor corpo]}] (str autor ": " corpo)))
       (str/join "\n")))

(defn- gerar-resumo [transcricao]
  (-> (gemini/gerar-texto
       (str "Resuma em português, de forma objetiva e em poucos parágrafos, "
            "a conversa de WhatsApp abaixo:\n\n" transcricao))
      (p/then (fn [resumo]
                (if resumo
                  (str "📝 *Resumão do tio " config/bot-name "*\n\n" resumo)
                  "❌ Não consegui gerar o resumo agora.")))
      (p/catch (fn [err]
                 (js/console.error "Erro ao gerar resumo:" err)
                 "❌ Não consegui gerar o resumo agora. Tente novamente mais tarde."))))

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
