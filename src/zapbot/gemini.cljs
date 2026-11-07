(ns zapbot.gemini
  "Fachada compatível de IA: Gemini principal, Groq opcional como fallback."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.ia-identidade :as identidade]))

(def ^:private transporte (js/require "../scripts/lib/ai-client.cjs"))

(defn disponivel? []
  (or (not (str/blank? config/gemini-api-key))
      (not (str/blank? config/groq-api-key))))

(defn status-provedores
  "Presença/formato das chaves — nunca inclui valores secretos."
  []
  (let [g? (not (str/blank? config/gemini-api-key))
        q? (not (str/blank? config/groq-api-key))
        g-len (if g? (count (str/trim config/gemini-api-key)) 0)
        q-len (if q? (count (str/trim config/groq-api-key)) 0)
        g-fmt (boolean (and g? (or (str/starts-with? (str/trim config/gemini-api-key) "AIza")
                                   (>= g-len 20))))
        q-fmt (boolean (and q? (or (str/starts-with? (str/trim config/groq-api-key) "gsk_")
                                   (>= q-len 20))))]
    {:gemini {:configured g? :len g-len :format-ok g-fmt :model config/gemini-model}
     :groq {:configured q? :len q-len :format-ok q-fmt :model config/groq-model}
     :disponivel? (or g? q?)}))

(defn log-status-boot!
  "Log seguro no boot: só presença/formato, sem valores."
  []
  (let [st (status-provedores)]
    (js/console.log
     (str "[IA] gemini=" (if (get-in st [:gemini :configured]) "configured" "missing")
          " format=" (get-in st [:gemini :format-ok])
          " model=" (get-in st [:gemini :model])
          " | groq=" (if (get-in st [:groq :configured]) "configured" "missing")
          " format=" (get-in st [:groq :format-ok])
          " model=" (get-in st [:groq :model])
          " | disponivel=" (:disponivel? st)))))

(defn- requisitar! [url opcoes] (js/fetch url opcoes))

(defonce ^:private cliente (atom {:signature nil :instance nil}))

(defn- cliente! []
  (let [signature [config/gemini-api-key config/gemini-model config/groq-api-key config/groq-model
                   identidade/instrucao-sistema config/ai-request-timeout-ms
                   config/ai-total-timeout-ms config/ai-retry-base-ms requisitar!]
        current @cliente]
    (if (= signature (:signature current))
      (:instance current)
      (let [instance (.createClient transporte
                       #js {:geminiApiKey config/gemini-api-key :geminiModel config/gemini-model
                            :groqApiKey config/groq-api-key :groqModel config/groq-model
                            :systemPrompt identidade/instrucao-sistema :fetch requisitar!
                            :requestTimeoutMs config/ai-request-timeout-ms
                            :totalTimeoutMs config/ai-total-timeout-ms
                            :retryBaseMs config/ai-retry-base-ms})]
        ;; Recria somente se a configuração do processo mudou. Em produção,
        ;; credenciais/modelos ficam estáveis e há uma instância compartilhada.
        (reset! cliente {:signature signature :instance instance})
        instance))))

(defn gerar-texto
  "Mantém o contrato string/nil; todas as tarefas compartilham identidade,
  fallback, tentativas limitadas e prazo total. Sem chave Groq, usa só Gemini."
  [prompt]
  (.generate (cliente!) prompt))

(defn gerar-texto-detalhado
  "Retorna texto, provedor, latência e tentativas sem expor prompt ou resposta nos logs."
  [prompt max-output-tokens]
  (.generateDetailed (cliente!) prompt #js {:maxOutputTokens max-output-tokens}))
