(ns zapbot.noticias
  "Comando !noticias - busca as últimas manchetes de um feed RSS."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["rss-parser" :as RSSParser]
            [zapbot.config :as config]))

(def ^:private parser (RSSParser.))

(defn buscar-noticias
  ([] (buscar-noticias config/news-feed-url 5))
  ([url] (buscar-noticias url 5))
  ([url limite]
   (-> (p/let [feed  (.parseURL parser url)
               itens (take limite (.-items feed))]
         (if (seq itens)
           (str "📰 *Últimas notícias*\n\n"
                (str/join "\n\n"
                          (map-indexed
                           (fn [i item]
                             (str (inc i) ". *" (.-title item) "*\n" (.-link item)))
                           itens)))
           "Não encontrei notícias no momento."))
       (p/catch (fn [err]
                  (js/console.error "Erro ao buscar notícias:" err)
                  "❌ Não consegui buscar as notícias agora. Tente novamente mais tarde.")))))
