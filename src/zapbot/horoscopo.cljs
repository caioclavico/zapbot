(ns zapbot.horoscopo
  "Comando !horoscopo - horóscopo diário via freehoroscopeapi.com."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.traducao :as traducao]))

(def ^:private signos
  {"aries" "aries"
   "touro" "taurus"
   "gemeos" "gemini"
   "cancer" "cancer"
   "leao" "leo"
   "virgem" "virgo"
   "libra" "libra"
   "escorpiao" "scorpio"
   "sagitario" "sagittarius"
   "capricornio" "capricorn"
   "aquario" "aquarius"
   "peixes" "pisces"})

(def ^:private nomes-pt
  {"aries" "Áries" "taurus" "Touro" "gemini" "Gêmeos" "cancer" "Câncer"
   "leo" "Leão" "virgo" "Virgem" "libra" "Libra" "scorpio" "Escorpião"
   "sagittarius" "Sagitário" "capricorn" "Capricórnio" "aquarius" "Aquário" "pisces" "Peixes"})

(defn- remover-acentos [s]
  (-> s
      (.normalize "NFD")
      (str/replace #"[\u0300-\u036f]" "")))

(defn signo-aleatorio []
  (rand-nth (keys signos)))

(defn- normalizar [s]
  (-> s remover-acentos str/lower-case str/trim))

(defn buscar-horoscopo [signo-pt]
  (let [chave   (normalizar signo-pt)
        signo-en (get signos chave)]
    (if-not signo-en
      (p/resolved
       (str "❓ Signo não reconhecido. Use um destes: " (str/join ", " (vals nomes-pt))))
      (let [url (str "https://freehoroscopeapi.com/api/v1/get-horoscope/daily?sign="
                      signo-en "&day=today")]
        (-> (p/let [res       (js/fetch url)
                    data      (.json res)
                    data      (js->clj data :keywordize-keys true)
                    descricao (get-in data [:data :horoscope] (get-in data [:data :horoscope_data]))
                    traduzido (traducao/traduzir descricao)]
              (str "🔮 *Horóscopo do tio " config/bot-name " (" (get nomes-pt signo-en) "):*\n\n" traduzido))
            (p/catch (fn [err]
                       (js/console.error "Erro ao buscar horóscopo:" err)
                       "❌ Não consegui buscar o horóscopo agora. Tente novamente mais tarde.")))))))
