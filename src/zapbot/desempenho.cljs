(ns zapbot.desempenho
  "Tempos por comando; contexto explícito e isolado, sem dados pessoais."
  (:require ["node:perf_hooks" :refer [performance]]
            [clojure.string :as str]
            [zapbot.config :as config]))

(defonce ^:private contextos (js/WeakMap.))
(defn contexto-de [message] (.get contextos message))
(defn dependencia! [ctx anterior]
  (when ctx
    (reset! (:aguardando ctx)
            (when anterior (select-keys anterior [:id :comando])))))
(defonce ^:private sequencia (atom 0))
(defonce ^:private operacoes (atom {}))
(defn pendentes []
  (mapv #(% "em_andamento") (vals @operacoes)))
(defn agora [] (.now performance))
(defn- emitir! [dados]
  (js/console.log "[Desempenho]" (js/JSON.stringify (clj->js dados))))

(defn treinador? [texto]
  (let [texto (str/trim (or texto ""))]
    (when (str/starts-with? texto config/prefix)
      (let [[cmd sub] (str/split (str/lower-case (subs texto (count config/prefix))) #"\s+")]
        (and (contains? #{"pk" "pokemon"} cmd)
             (contains? #{"treinador" "tre"} sub))))))

(defn pokemon? [texto]
  (let [texto (str/trim (or texto ""))]
    (and (str/starts-with? texto config/prefix)
         (contains? #{"pk" "pokemon"}
                    (first (str/split (str/lower-case (subs texto (count config/prefix))) #"\s+"))))))

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
   (acompanhar! message executar registrar-log! "pk treinador"))
  ([message executar registrar-log! comando]
   (let [inicio (agora)
         ctx {:id (swap! sequencia inc) :comando comando :aguardando (atom nil) :etapas (atom {})
              :pendentes (atom #{}) :falhas (atom #{})}
         resumo (fn [evento]
                  {:id (:id ctx) :comando comando :evento evento
                   :timestamp (.toISOString (js/Date.))
                   :total_ms (js/Math.round (- (agora) inicio))
                   :etapas_ms @(:etapas ctx) :pendentes (vec @(:pendentes ctx))
                   :falhas (vec @(:falhas ctx)) :aguardando @(:aguardando ctx)})
         timer (js/setTimeout #(registrar-log! (resumo "pendente_30s")) 30000)
         finalizar (fn [evento]
                     (js/clearTimeout timer)
                     (swap! operacoes dissoc (:id ctx))
                     (.delete contextos message)
                     (registrar-log! (resumo evento)))]
     (.unref timer)
     (.set contextos message ctx)
     (swap! operacoes assoc (:id ctx) resumo)
     (registrar-log! (resumo "inicio"))
     (try
       (.then (js/Promise.resolve (executar ctx))
              (fn [valor] (finalizar "fim") valor)
              (fn [erro] (finalizar "erro") (throw erro)))
       (catch :default erro
         (finalizar "erro")
         (throw erro))))))

(defn observar-operacao!
  "Mede operações internas sem registrar argumentos ou identificadores de chat."
  [comando executar]
  (acompanhar! #js {} executar
               #(when (or (= "erro" (:evento %)) (>= (:total_ms %) 30000)) (emitir! %))
               comando))

(defn acompanhar-mensagem! [message executar]
  (acompanhar! message executar emitir!
               (if (treinador? (.-body message)) "pk treinador" "pokemon")))
