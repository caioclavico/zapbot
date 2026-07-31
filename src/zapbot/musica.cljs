(ns zapbot.musica
  "Comando !musica - indica uma música (com link do Spotify), por gênero ou aleatória."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.spotify :as spotify]))

(def ^:private generos-aleatorios
  ["pop" "rock" "sertanejo" "funk" "pagode" "samba" "mpb" "forro"
   "eletronica" "hip-hop" "reggae" "k-pop" "indie" "axe" "trap"])

(defn- chave-ausente []
  (p/resolved
   (str "⚠️ Comando indisponível: configure SPOTIFY_CLIENT_ID e SPOTIFY_CLIENT_SECRET "
        "no .env (gratuito em https://developer.spotify.com/dashboard).")))

(defn- formatar [{:keys [name artists external_urls]}]
  (str "🎵 *Música do tio " config/bot-name "*\n\n"
       "*" name "* - " (str/join ", " (map :name artists))
       "\n\n🎧 " (:spotify external_urls)))

(defn buscar-musica
  ([]
   (buscar-musica (rand-nth generos-aleatorios)))
  ([genero]
   (if (or (str/blank? config/spotify-client-id) (str/blank? config/spotify-client-secret))
     (chave-ausente)
     (-> (spotify/buscar-faixas (str "genre:\"" (str/lower-case genero) "\""))
         (p/then (fn [faixas]
                   (if (seq faixas)
                     (formatar (rand-nth faixas))
                     (str "❓ Não encontrei músicas do gênero \"" genero "\". "
                          "Tente outro (ex.: pop, rock, sertanejo, funk...)."))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar música:" err)
                    "❌ Não consegui buscar uma música agora. Tente novamente mais tarde."))))))
