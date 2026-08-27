(ns zapbot.traducao
  "Tradução de texto, com Google Translate como via principal e Gemini como
  contingência quando o endpoint público do Google estiver indisponível."
  (:require [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]))

(defn- traduzir-google [texto origem destino]
  (let [url (str "https://translate.googleapis.com/translate_a/single"
                 "?client=gtx&dt=t&sl=" origem "&tl=" destino
                 "&q=" (js/encodeURIComponent texto))]
    (p/let [res (js/fetch url)]
      (if-not (.-ok res)
        (p/rejected (js/Error. (str "Google Translate respondeu HTTP " (.-status res))))
        (p/let [data (.json res)]
          (->> (aget data 0)
               (map #(aget % 0))
               (apply str)))))))

(defn- traduzir-gemini [texto origem destino]
  (when config/gemini-api-key
    (gemini/gerar-texto
     (str "Translate the following text from " origem " to " destino
          ". Return only the translated text, with no explanation or quotation marks:\n\n" texto))))

(defn traduzir-com-status
  "Retorna a tradução ou nil se nenhum provedor estiver disponível. Ao
  contrário de `traduzir`, não devolve silenciosamente o texto original - útil
  para o comando !traduza poder informar uma falha de verdade ao usuário."
  [texto origem destino]
  (-> (traduzir-google texto origem destino)
      (p/catch (fn [err]
                 (js/console.warn "Google Translate falhou; tentando Gemini:" err)
                 (traduzir-gemini texto origem destino)))
      (p/catch (fn [err]
                 (js/console.error "Todos os provedores de tradução falharam:" err)
                 nil))))

(defn traduzir
  "Traduz `texto` de `origem` para `destino` (códigos de idioma, ex.: \"en\", \"pt\").
  Em caso de falha, retorna o texto original sem tradução."
  ([texto] (traduzir texto "en" "pt"))
  ([texto origem destino]
   (-> (traduzir-com-status texto origem destino)
       (p/then #(or % texto)))))
