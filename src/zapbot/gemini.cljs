(ns zapbot.gemini
  "Fachada compatível de IA: Gemini principal, Groq opcional como fallback."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.ia-identidade :as identidade]))

(def ^:private transporte (js/require "../scripts/lib/ai-client.cjs"))

(defn disponivel? []
  (or (not (str/blank? config/gemini-api-key))
      (not (str/blank? config/groq-api-key))))

(defn- requisitar! [url opcoes] (js/fetch url opcoes))

(defn gerar-texto
  "Mantém o contrato string/nil; todas as tarefas compartilham identidade,
  fallback, tentativas limitadas e prazo total. Sem chave Groq, usa só Gemini."
  [prompt]
  (let [cliente (.createClient transporte
                  #js {:geminiApiKey config/gemini-api-key :geminiModel config/gemini-model
                       :groqApiKey config/groq-api-key :groqModel config/groq-model
                       :systemPrompt identidade/instrucao-sistema :fetch requisitar!
                       :requestTimeoutMs config/ai-request-timeout-ms
                       :totalTimeoutMs config/ai-total-timeout-ms
                       :retryBaseMs config/ai-retry-base-ms})]
    (.generate cliente prompt)))
