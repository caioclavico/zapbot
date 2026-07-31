(ns zapbot.filme
  "Comando !filme - sinopse, nota e capa de filmes via TMDB (requer chave gratuita)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.traducao :as traducao]))

(def ^:private MessageMedia (.-MessageMedia wwjs))
(def ^:private base-url "https://api.themoviedb.org/3")
(def ^:private poster-base-url "https://image.tmdb.org/t/p/w500")

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

(defn- enviar-com-capa [message poster_path legenda]
  (-> (p/let [media (.fromUrl MessageMedia (str poster-base-url poster_path))
              _     (.reply message media nil #js {:caption legenda})]
        nil)
      (p/catch (fn [err]
                 (js/console.error "Erro ao enviar capa do filme:" err)
                 ;; se a capa falhar, ainda respondemos com o texto
                 legenda))))

(defn- responder [message filme legenda]
  (if (:poster_path filme)
    (enviar-com-capa message (:poster_path filme) legenda)
    (p/resolved legenda)))

(defn- com-sinopse-traduzida [message filme]
  (p/let [overview (:overview filme)
          sinopse  (if (str/blank? overview)
                     "(sinopse não disponível)"
                     (traducao/traduzir overview))]
    (responder message filme (formatar filme sinopse))))

(defn buscar-filme
  ([message]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (-> (buscar-json (str "/movie/popular?page=" (inc (rand-int 5))))
         (p/then (fn [data] (com-sinopse-traduzida message (rand-nth (:results data)))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar filme popular:" err)
                    "❌ Não consegui buscar um filme agora. Tente novamente mais tarde.")))))
  ([message titulo]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (-> (buscar-json (str "/search/movie?query=" (js/encodeURIComponent titulo)))
         (p/then (fn [data]
                   (if-let [filme (first (:results data))]
                     (com-sinopse-traduzida message filme)
                     (str "❓ Não encontrei o filme \"" titulo "\". Tente outro nome."))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar filme:" err)
                    "❌ Não consegui buscar o filme agora. Tente novamente mais tarde."))))))


