(ns zapbot.desempenho
  "Tempos por comando; contexto explícito e isolado, sem dados pessoais."
  (:require ["node:perf_hooks" :refer [performance]]
            [clojure.string :as str]
            [zapbot.config :as config]))

(defonce ^:private contextos (js/WeakMap.))
(defn contexto-de [message] (.get contextos message))
(defonce ^:private sequencia (atom 0))
(defn agora [] (.now performance))
(defn- emitir! [dados]
  (js/console.log "[Desempenho]" (js/JSON.stringify (clj->js dados))))

(defn treinador? [texto]
  (let [texto (str/trim (or texto ""))]
    (when (str/starts-with? texto config/prefix)
      (let [[cmd sub] (str/split (str/lower-case (subs texto (count config/prefix))) #"\s+")]
        (and (contains? #{"pk" "pokemon"} cmd)
             (contains? #{"treinador" "tre"} sub))))))

(defn registrar! [ctx etapa inicio]
  (when ctx
    (swap! (:etapas ctx) update etapa (fnil + 0) (js/Math.round (- (agora) inicio)))))

(defn medir! [ctx etapa executar]
  (if ctx
    (let [inicio (agora)
          terminar (fn []
                     (registrar! ctx etapa inicio)
                     (swap! (:pendentes ctx) disj etapa))
          falhar (fn [erro]
                   (swap! (:falhas ctx) conj etapa)
                   (terminar)
                   (throw erro))]
      (swap! (:pendentes ctx) conj etapa)
      (try
        (let [resultado (executar)]
          (if (and resultado (fn? (.-then ^js resultado)))
            (.then (js/Promise.resolve resultado)
                   (fn [valor] (terminar) valor) falhar)
            (do (terminar) resultado)))
        (catch :default erro (falhar erro))))
    (executar)))

(defn acompanhar!
  ([message executar] (acompanhar! message executar emitir!))
  ([message executar registrar-log!]
   (let [inicio (agora)
         ctx {:id (swap! sequencia inc) :etapas (atom {})
              :pendentes (atom #{}) :falhas (atom #{})}
         resumo (fn [evento]
                  {:id (:id ctx) :comando "pk treinador" :evento evento
                   :timestamp (.toISOString (js/Date.))
                   :total_ms (js/Math.round (- (agora) inicio))
                   :etapas_ms @(:etapas ctx) :pendentes (vec @(:pendentes ctx))
                   :falhas (vec @(:falhas ctx))})
         timer (js/setTimeout #(registrar-log! (resumo "pendente_30s")) 30000)
         finalizar (fn [evento]
                     (js/clearTimeout timer)
                     (.delete contextos message)
                     (registrar-log! (resumo evento)))]
     (.unref timer)
     (.set contextos message ctx)
     (registrar-log! (resumo "inicio"))
     (try
       (.then (js/Promise.resolve (executar ctx))
              (fn [valor] (finalizar "fim") valor)
              (fn [erro] (finalizar "erro") (throw erro)))
       (catch :default erro
         (finalizar "erro")
         (throw erro))))))
