(ns zapbot.cotacao
  "Comando !cotacao - cotação de moedas via AwesomeAPI (economia.awesomeapi.com.br)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]))

(def ^:private nomes
  {"USD" "Dólar americano" "EUR" "Euro" "BTC" "Bitcoin" "GBP" "Libra esterlina"
   "ARS" "Peso argentino" "JPY" "Iene" "CAD" "Dólar canadense" "CHF" "Franco suíço"
   "AUD" "Dólar australiano" "CNY" "Yuan chinês"})

(defn- formatar-item [[_ v]]
  (let [{:keys [code codein bid]} v
        nome (get nomes code code)]
    (str "*" nome "* (" code "/" codein "): R$ " bid)))

(defn buscar-cotacoes
  ([] (buscar-cotacoes config/default-currencies))
  ([pares]
   (let [url (str "https://economia.awesomeapi.com.br/json/last/" (str/join "," pares))]
     (-> (p/let [res  (js/fetch url)
                 data (.json res)]
           (let [data (js->clj data :keywordize-keys true)]
             (if (empty? data)
               "Não encontrei cotações para os pares informados. Use o formato ex.: USD-BRL."
               (str "💱 *Cotações*\n\n" (str/join "\n" (map formatar-item data))))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar cotações:" err)
                    "❌ Não consegui buscar as cotações agora. Tente novamente mais tarde."))))))
