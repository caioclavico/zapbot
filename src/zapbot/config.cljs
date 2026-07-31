(ns zapbot.config
  "Carrega variáveis de ambiente (.env) e expõe a configuração do bot."
  (:require [clojure.string :as str]
            ["dotenv" :as dotenv]))

(.config dotenv)

(defn- env
  ([k] (env k nil))
  ([k default]
   (let [v (unchecked-get js/process.env k)]
     (if (and v (not= v "")) v default))))

(defn- env-list [k default]
  (if-let [v (env k)]
    (->> (str/split v #",")
         (map str/trim)
         (remove str/blank?)
         vec)
    default))

(def bot-name (env "BOT_NAME" "Odisseu"))
(def prefix (env "PREFIX" "!"))
(def admin-numbers (set (env-list "ADMIN_NUMBERS" [])))
(def default-city (env "WEATHER_DEFAULT_CITY" "Sao Paulo"))
(def news-feed-url (env "NEWS_FEED_URL" "https://g1.globo.com/rss/g1/"))
(def default-currencies (env-list "CURRENCY_DEFAULT" ["USD-BRL" "EUR-BRL" "BTC-BRL"]))
;; usado em ARM64/Docker, onde o Puppeteer não baixa um Chromium próprio
(def puppeteer-executable-path (env "PUPPETEER_EXECUTABLE_PATH"))
;; chave gratuita em https://www.themoviedb.org/settings/api (usada pelo !filme)
(def tmdb-api-key (env "TMDB_API_KEY"))
;; chave gratuita em https://aistudio.google.com/apikey (usada pelo !resuma)
(def gemini-api-key (env "GEMINI_API_KEY"))
;; credenciais gratuitas em https://developer.spotify.com/dashboard (usadas pelo !musica)
(def spotify-client-id (env "SPOTIFY_CLIENT_ID"))
(def spotify-client-secret (env "SPOTIFY_CLIENT_SECRET"))
