(ns zapbot.traducao
  "Tradução de texto via endpoint não-oficial do Google Translate (sem chave)."
  (:require [promesa.core :as p]))

(defn traduzir
  "Traduz `texto` de `origem` para `destino` (códigos de idioma, ex.: \"en\", \"pt\").
  Em caso de falha, retorna o texto original sem tradução."
  ([texto] (traduzir texto "en" "pt"))
  ([texto origem destino]
   (let [url (str "https://translate.googleapis.com/translate_a/single"
                  "?client=gtx&dt=t&sl=" origem "&tl=" destino
                  "&q=" (js/encodeURIComponent texto))]
     (-> (p/let [res  (js/fetch url)
                 data (.json res)]
           (->> (aget data 0)
                (map #(aget % 0))
                (apply str)))
         (p/catch (fn [err]
                    (js/console.error "Erro ao traduzir texto:" err)
                    texto))))))
