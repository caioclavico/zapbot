(ns zapbot.recursos
  "Métricas passivas, sem consulta ao WhatsApp nem dados de mensagens."
  (:require ["node:perf_hooks" :refer [performance]]
            [zapbot.config :as config]))

(def ^:private coletor (js/require "../scripts/lib/odisseu-resource-monitor.cjs"))
(defonce ^:private monitor (atom nil))

(defn- registrar! [dados]
  (js/console.log "[RecursosOdisseu]" (js/JSON.stringify dados)))

(defn iniciar! [^js client]
  (when (and config/performance-metrics-enabled config/odisseu-resource-metrics-enabled (nil? @monitor))
    (try
      (reset! monitor
              (.startResourceMonitor coletor
                #js {:intervalMs config/odisseu-resource-metrics-interval-ms
                     :log registrar!
                     :browserProcess (fn []
                                       (let [^js browser (.-pupBrowser client)]
                                         (when (and browser (fn? (.-process browser)))
                                           (.process browser))))}))
      (catch :default _
        (js/console.warn "[RecursosOdisseu] Coletor indisponível; execução do bot preservada.")))))

(defn parar! []
  (when-let [^js ativo @monitor]
    (reset! monitor nil)
    (try (.stop ativo) (catch :default _ nil))))

(defn amostra []
  (when-let [^js ativo @monitor]
    (try (.snapshot ativo) (catch :default _ nil))))

(defn- registrar-latencia! [^js ativo inicio]
  ;; Uma falha de diagnóstico não deve modificar a resposta do comando.
  (try
    (.recordCommandLatency ativo (- (.now performance) inicio))
    (catch :default _ nil)))

(defn medir-comando! [executar]
  (if-let [^js ativo (when config/performance-metrics-enabled @monitor)]
    (let [inicio (.now performance)]
      (try
        (let [resultado (executar)]
          (if (and resultado (fn? (.-then ^js resultado)))
            (.then (js/Promise.resolve resultado)
                   (fn [valor] (registrar-latencia! ativo inicio) valor)
                   (fn [erro] (registrar-latencia! ativo inicio) (throw erro)))
            (do (registrar-latencia! ativo inicio) resultado)))
        (catch :default erro
          (registrar-latencia! ativo inicio)
          (throw erro))))
    (executar)))
