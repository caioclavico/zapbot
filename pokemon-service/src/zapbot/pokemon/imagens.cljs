(ns zapbot.pokemon.imagens
  "Cache limitado e composição na resolução de envio, somente no serviço."
  (:require [zapbot.http :as http]
            [promesa.core :as p]
            [clojure.string :as str]))

(def ^:private pipeline (js/require "../runtime/images.cjs"))

(defn url-cdn [url]
  (when-let [[_ caminho]
             (and (string? url)
                  (re-matches #"https://raw\.githubusercontent\.com/PokeAPI/sprites/(?:master|refs/heads/master)/(.+)" url))]
    (str "https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/" caminho)))

(defn candidatos [url]
  (vec (distinct (remove str/blank? [(url-cdn url) url]))))

(defn baixar! [url]
  (.download pipeline url
    (fn [prazo]
      (letfn [(tentar [[atual & restantes] erro]
                (if (and atual (< (.now js/Date) prazo))
                  (-> (http/fetch! atual #js {:signal (.timeout js/AbortSignal
                                                               (max 1 (min 6000 (- prazo (.now js/Date)))))})
                      (p/then #(.responseBuffer pipeline %))
                      (p/catch #(tentar restantes %)))
                  (p/rejected (or erro (js/Error. "Tempo de download do sprite esgotado")))))]
        (tentar (candidatos url) nil)))))

(defn sprite!
  ([entrada largura] (.sprite pipeline entrada largura))
  ([entrada largura altura] (.sprite pipeline entrada largura altura))
  ([entrada largura altura escala] (.sprite pipeline entrada largura altura escala)))

(defn cartao!
  ([svg] (.render pipeline svg))
  ([svg camadas] (.render pipeline svg camadas))
  ([svg camadas opcoes] (.render pipeline svg camadas opcoes)))

(defn sobrepor! [base svg] (.overlay pipeline base svg))
(defn arte! [arquivo] (.asset pipeline arquivo))
