(ns zapbot.previsao
  "Comando !previsao - previsão do tempo via wttr.in (não requer chave de API)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]))

(defn- formatar-dia [dia]
  (let [meio-dia (get-in dia [:hourly 4 :weatherDesc 0 :value] "")]
    (str "📅 " (:date dia) ": mín " (:mintempC dia) "°C / máx " (:maxtempC dia) "°C - " meio-dia)))

(defn buscar-previsao
  ([] (buscar-previsao config/default-city))
  ([cidade]
   (let [url (str "https://wttr.in/" (js/encodeURIComponent cidade) "?format=j1")]
     (-> (p/let [res  (js/fetch url)
                 data (.json res)]
           (let [data       (js->clj data :keywordize-keys true)
                 atual      (first (:current_condition data))
                 desc-atual (get-in atual [:weatherDesc 0 :value] "")
                 dias       (take 3 (:weather data))]
             (str "🌦️ *Previsão do tempo em " cidade "*\n\n"
                  "Agora: " (:temp_C atual) "°C, " desc-atual "\n\n"
                  (str/join "\n" (map formatar-dia dias)))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar previsão:" err)
                    (str "❌ Não consegui buscar a previsão para \"" cidade "\". Verifique o nome da cidade.")))))))
