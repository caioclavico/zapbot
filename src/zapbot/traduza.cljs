(ns zapbot.traduza
  "Comando !traduza - traduz uma frase qualquer para português."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.traducao :as traducao]))

(defn traduzir-frase [frase]
  (if (str/blank? frase)
    (p/resolved (str "❓ Use: " config/prefix "traduza <frase>"))
    (p/let [traduzido (traducao/traduzir frase "auto" "pt")]
      (str "🌐 *Tradução:*\n\n" traduzido))))
