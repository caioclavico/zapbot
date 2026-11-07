(ns zapbot.conteudo-dinamico
  "Geração variada de piadas/curiosidades e antirrepetição global persistente."
  (:require [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]))

(def ^:private history-store
  (.createStore (js/require "../scripts/lib/ai-content-history-file.cjs")
                #js {:filePath config/ai-content-history-path
                     :windowSize config/ai-content-history-window}))
(defonce ^:private historico
  (atom {:piada {:cursor 0 :items []}
         :curiosidade {:cursor 0 :items []}}))

(defn- normalizar-historico [value]
  (let [value (or value {})
        read-kind (fn [kind]
                    (let [raw (or (get value kind) (get value (name kind)) {})
                          cursor (or (:cursor raw) (get raw "cursor") 0)
                          items (or (:items raw) (get raw "items") [])]
                      {:cursor (if (and (number? cursor) (<= 0 cursor)) cursor 0)
                       :items (->> items
                                   (filter map?)
                                   (take-last config/ai-content-history-window)
                                   vec)}))]
    {:piada (read-kind :piada)
     :curiosidade (read-kind :curiosidade)}))

(defn- carregar-historico! []
  (-> (.load history-store)
      (p/then (fn [raw]
                (let [state (normalizar-historico (js->clj raw :keywordize-keys true))]
                  (reset! historico state)
                  (clj->js state))))))

(defn- salvar-historico! [novo]
  (-> (.save history-store novo)
      (p/then (fn [raw]
                (reset! historico (normalizar-historico (js->clj raw :keywordize-keys true)))
                true))))

(defonce ^:private engine
  (.createService (js/require "../scripts/lib/ai-content-generator.cjs")
    #js {:generate (fn [prompt max-output-tokens]
                     (gemini/gerar-texto-detalhado prompt max-output-tokens))
         :loadHistory carregar-historico!
         :saveHistory salvar-historico!
         :windowSize config/ai-content-history-window
         :maxAttempts config/ai-content-generation-attempts
         :maxOutputTokens config/ai-content-max-output-tokens
         :logger (fn [event]
                   (js/console.info (js/JSON.stringify event)))}))

(defn gerar!
  "Gera conteúdo para :piada ou :curiosidade; topic é somente um tema."
  [kind topic]
  (-> (.generate engine (name kind) (or topic ""))
      (p/then
       (fn [result]
         (cond
           (.-ok result)
           (str (if (= kind :piada) "🤡 *Piada do tio " "🧠 *Curiosidade do tio ")
                config/bot-name ":*\n\n"
                (.-content result)
                (when (.-fallback result)
                  (if (.-persistenceWarning result)
                    "\n\n_(acervo local; histórico persistente temporariamente indisponível)_"
                    "\n\n_(do acervo local; não foi gerado agora)_")))

           (= "invalid_topic" (.-reason result))
           "⚠️ Envie apenas um assunto curto, sem instruções ou dados privados."

           (= "persistence_unavailable" (.-reason result))
           "⚠️ Não consegui guardar o histórico agora. Tente novamente em instantes."

           (= "busy" (.-reason result))
           "⚠️ Há muitos pedidos agora. Tente novamente em instantes."

           :else
           "⚠️ Não consegui preparar uma resposta agora. Tente novamente em instantes.")))))
