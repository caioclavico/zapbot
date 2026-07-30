(ns zapbot.filme
  "Comando !filme - sinopse, nota e sugestões de filmes via TMDB (requer chave gratuita)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.traducao :as traducao]))

(def ^:private base-url "https://api.themoviedb.org/3")

(defn- chave-ausente []
  (p/resolved
   (str "⚠️ Comando indisponível: configure TMDB_API_KEY no .env "
        "(chave gratuita em https://www.themoviedb.org/settings/api).")))

(defn- buscar-json [caminho]
  (-> (js/fetch (str base-url caminho
                      (if (str/includes? caminho "?") "&" "?")
                      "api_key=" config/tmdb-api-key))
      (p/then (fn [res] (.json res)))
      (p/then #(js->clj % :keywordize-keys true))))

(defn- ano [data-lancamento]
  (if (seq data-lancamento) (subs data-lancamento 0 4) "?"))

(defn- formatar [{:keys [title release_date vote_average]} sinopse]
  (str "🎬 *Filme do tio " config/bot-name " (" title ", " (ano release_date) "):*\n\n"
       sinopse
       "\n\n⭐ Nota: " (.toFixed vote_average 1) "/10"))

(defn- com-sinopse-traduzida [filme]
  (p/let [overview (:overview filme)
          sinopse  (if (str/blank? overview)
                     "(sinopse não disponível)"
                     (traducao/traduzir overview))]
    (formatar filme sinopse)))

(defn buscar-filme
  ([]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (-> (buscar-json (str "/movie/popular?page=" (inc (rand-int 5))))
         (p/then (fn [data] (com-sinopse-traduzida (rand-nth (:results data)))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar filme popular:" err)
                    "❌ Não consegui buscar um filme agora. Tente novamente mais tarde.")))))
  ([titulo]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (-> (buscar-json (str "/search/movie?query=" (js/encodeURIComponent titulo)))
         (p/then (fn [data]
                   (if-let [filme (first (:results data))]
                     (com-sinopse-traduzida filme)
                     (str "❓ Não encontrei o filme \"" titulo "\". Tente outro nome."))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar filme:" err)
                    "❌ Não consegui buscar o filme agora. Tente novamente mais tarde."))))))

