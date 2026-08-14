(ns zapbot.gemini
  "Wrapper fino para a API Gemini (generateContent), usado por !resuma e !pergunta.

  Usa a chave no formato \"auth key\" (AQ....) do Google AI Studio, que precisa
  ir no header x-goog-api-key (não em ?key= na URL)."
  (:require [promesa.core :as p]
            [zapbot.config :as config]))

(def ^:private url "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent")

;; 429 (cota do free tier) e 503 (modelo sobrecarregado) costumam ser
;; transitórios - ver https://ai.google.dev/gemini-api/docs/rate-limits.
(def ^:private status-transitorios #{429 503})

(defn- esperar [ms]
  (p/create (fn [resolve _] (js/setTimeout resolve ms))))

(defn- chamar-uma-vez [prompt]
  (let [corpo #js {:contents #js [#js {:parts #js [#js {:text prompt}]}]}]
    (p/let [res  (js/fetch url #js {:method  "POST"
                                     :headers #js {"Content-Type"   "application/json"
                                                    "x-goog-api-key" config/gemini-api-key}
                                     :body    (js/JSON.stringify corpo)})
            data (.json res)
            data (js->clj data :keywordize-keys true)]
      {:ok? (.-ok res) :status (.-status res) :data data})))

(defn- tentar [prompt tentativas-restantes]
  (p/let [{:keys [ok? status data]} (chamar-uma-vez prompt)]
    (cond
      ok?
      (or (get-in data [:candidates 0 :content :parts 0 :text])
          (do (js/console.warn "Gemini respondeu sem texto - finishReason:"
                                (get-in data [:candidates 0 :finishReason])
                                "promptFeedback:" (:promptFeedback data))
              nil))

      (and (status-transitorios status) (pos? tentativas-restantes))
      (do
        (js/console.warn "Gemini status" status "- tentando de novo em breve...")
        (-> (esperar 2000)
            (p/then (fn [_] (tentar prompt (dec tentativas-restantes))))))

      :else
      (do
        (js/console.error "Gemini falhou (status" status "):" (:error data))
        nil))))

(defn gerar-texto
  "Envia o prompt ao Gemini e retorna uma promise com o texto da resposta, ou
  nil se não houver candidato (ex.: bloqueio de segurança) ou se todas as
  tentativas falharem. Re-tenta automaticamente (até 2x) em erros
  transitórios (429 cota excedida / 503 sobrecarga)."
  [prompt]
  (tentar prompt 2))
