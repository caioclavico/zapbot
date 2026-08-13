(ns zapbot.musica
  "Comando !musica - indica uma música (com link do Spotify), por gênero ou aleatória."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.spotify :as spotify]))

(def ^:private generos-aleatorios
  ;; brasileiros - todos conferidos ao vivo na Search API (retornam faixas)
  ["pop" "rock" "sertanejo" "funk" "pagode" "samba" "mpb" "forro"
   "bossa nova" "brega" "arrocha" "tecnobrega" "gospel" "rap" "axe"
   ;; internacionais - "piseiro" e "reggaeton" foram trocados por
   ;; "tecnobrega"/"latin urban" por não retornarem nenhuma faixa na busca
   "eletronica" "hip-hop" "reggae" "k-pop" "indie" "trap" "jazz"
   "blues" "classical" "country" "metal" "punk" "r&b" "soul"
   "disco" "house" "techno" "latin urban" "latin"])

(defn- chave-ausente []
  (p/resolved
   (str "⚠️ Comando indisponível: configure SPOTIFY_CLIENT_ID e SPOTIFY_CLIENT_SECRET "
        "no .env (gratuito em https://developer.spotify.com/dashboard).")))

(defn- pedido-de-lista? [genero]
  (contains? #{"generos" "gêneros" "genero" "gênero" "listar"} (str/lower-case (str/trim genero))))

(defn- listar-generos []
  (str "🎵 *Gêneros* (tio " config/bot-name "):\n\n"
       (str/join ", " generos-aleatorios)
       "\n\n⚠️ O Spotify tirou de apps novos o endpoint que listava os gêneros "
       "oficiais (mudança deles de nov/2024), então essa é só uma amostra "
       "sugerida - pode tentar outros gêneros em português ou inglês também, "
       "a busca não se limita a essa lista."))

(defn- formatar [{:keys [name artists external_urls]}]
  (str "🎵 *Música do tio " config/bot-name "*\n\n"
       "*" name "* - " (str/join ", " (map :name artists))
       "\n\n🎧 " (:spotify external_urls)))

(defn buscar-musica
  ([]
   (buscar-musica (rand-nth generos-aleatorios)))
  ([genero]
   (cond
     (or (str/blank? config/spotify-client-id) (str/blank? config/spotify-client-secret))
     (chave-ausente)

     (pedido-de-lista? genero)
     (p/resolved (listar-generos))

     :else
     (-> (spotify/buscar-faixas (str "genre:\"" (str/lower-case genero) "\""))
         (p/then (fn [faixas]
                   (if (seq faixas)
                     (formatar (rand-nth faixas))
                     (str "❓ Não encontrei músicas do gênero \"" genero "\". "
                          "Tente outro (ex.: pop, rock, sertanejo, funk...)."))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar música:" err)
                    "❌ Não consegui buscar uma música agora. Tente novamente mais tarde."))))))
