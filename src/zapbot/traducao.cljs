(ns zapbot.traducao
  "Tradução de texto, com Google Translate como via principal e Gemini como
  contingência quando o endpoint público do Google estiver indisponível."
  (:require [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]))

(defonce ^:private google-pausado-ate (atom 0))

(defn- pausar-google! [res]
  (let [agora (js/Date.now)
        retry-after (some-> res .-headers (.get "retry-after"))
        segundos (js/Number retry-after)
        espera (cond
                 (nil? retry-after) 60000
                 (js/Number.isFinite segundos) (* 1000 segundos)
                 :else (- (js/Date.parse retry-after) agora))
        ;; Respeita Retry-After, limitado entre 1 minuto e 15 minutos.
        espera (if (js/Number.isFinite espera)
                 (max 60000 (min 900000 espera)) 60000)]
    (when (<= @google-pausado-ate agora)
      (js/console.warn "Google Translate respondeu HTTP 429; usando Gemini durante cooldown."))
    (swap! google-pausado-ate max (+ agora espera))))

(defn- traduzir-google [texto origem destino]
  (let [url (str "https://translate.googleapis.com/translate_a/single"
                 "?client=gtx&dt=t&sl=" origem "&tl=" destino
                 "&q=" (js/encodeURIComponent texto))]
    (p/let [res (js/fetch url)]
      (if-not (.-ok res)
        (do
          (when (= 429 (.-status res)) (pausar-google! res))
          (p/rejected (js/Error. (str "Google Translate respondeu HTTP " (.-status res)))))
        (p/let [data (.json res)]
          (->> (aget data 0)
               (map #(aget % 0))
               (apply str)))))))

(defn- traduzir-gemini [texto origem destino]
  (when config/gemini-api-key
    (gemini/gerar-texto
     (str "Translate the following text from " origem " to " destino
          ". Return only the translated text, with no explanation or quotation marks:\n\n" texto))))

(defn traduzir-com-status
  "Retorna a tradução ou nil se nenhum provedor estiver disponível. Ao
  contrário de `traduzir`, não devolve silenciosamente o texto original - útil
  para o comando !traduza poder informar uma falha de verdade ao usuário."
  [texto origem destino]
  (-> (if (< (js/Date.now) @google-pausado-ate)
        (p/resolved (traduzir-gemini texto origem destino))
        (-> (traduzir-google texto origem destino)
            (p/catch (fn [err]
                       (when (<= @google-pausado-ate (js/Date.now))
                         (js/console.warn "Google Translate falhou; tentando Gemini:" err))
                       (traduzir-gemini texto origem destino)))))
      (p/catch (fn [err]
                 (js/console.error "Todos os provedores de tradução falharam:" err)
                 nil))))

(defn traduzir
  "Traduz `texto` de `origem` para `destino` (códigos de idioma, ex.: \"en\", \"pt\").
  Em caso de falha, retorna o texto original sem tradução."
  ([texto] (traduzir texto "en" "pt"))
  ([texto origem destino]
   (-> (traduzir-com-status texto origem destino)
       (p/then #(or % texto)))))
