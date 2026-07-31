(ns zapbot.spotify
  "Wrapper fino para a API do Spotify (Client Credentials + busca de faixas),
  usado pelo !musica."
  (:require [promesa.core :as p]
            [zapbot.config :as config]))

;; cache do access token em memória: {:token ... :expira-em epoch-ms}
(defonce ^:private token-cache (atom nil))

(defn- token-valido? [cache]
  (and cache (< (js/Date.now) (:expira-em cache))))

(defn- pedir-token []
  (let [credenciais (.toString (js/Buffer.from (str config/spotify-client-id ":" config/spotify-client-secret))
                                "base64")]
    (-> (js/fetch "https://accounts.spotify.com/api/token"
                  #js {:method  "POST"
                       :headers #js {"Authorization" (str "Basic " credenciais)
                                     "Content-Type"  "application/x-www-form-urlencoded"}
                       :body    "grant_type=client_credentials"})
        (p/then (fn [res] (.json res)))
        (p/then (fn [data]
                  ;; margem de 60s pra não usar um token perto de expirar
                  (let [cache {:token     (.-access_token data)
                               :expira-em (+ (js/Date.now) (* 1000 (- (.-expires_in data) 60)))}]
                    (reset! token-cache cache)
                    (:token cache)))))))

(defn- obter-token []
  (if (token-valido? @token-cache)
    (p/resolved (:token @token-cache))
    (pedir-token)))

(defn buscar-faixas
  "Busca faixas no Spotify para a query dada. Retorna uma promise com o vetor
  de tracks encontradas (vazio se nenhuma)."
  [query]
  (p/let [token (obter-token)
          ;; a Search API limita "limit" a no máximo 10 (valores maiores dão 400)
          res   (js/fetch (str "https://api.spotify.com/v1/search?q=" (js/encodeURIComponent query)
                                "&type=track&limit=10")
                           #js {:headers #js {"Authorization" (str "Bearer " token)}})
          data  (.json res)]
    (or (get-in (js->clj data :keywordize-keys true) [:tracks :items]) [])))
