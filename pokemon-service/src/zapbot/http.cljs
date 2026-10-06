(ns zapbot.http
  "HTTP externo com prazo finito e tempos por operação, sem retries de comandos."
  (:require [promesa.core :as p]))

(def timeout-ms 8000)

(defn- categoria [url]
  (cond
    (.includes (str url) "pokeapi.co/") "pokeapi_ms"
    (or (.includes (str url) "sprites") (.includes (str url) "cdn.jsdelivr.net")) "sprite_download_ms"
    :else "translation_ms"))

(defn fetch!
  ([url] (fetch! url #js {}))
  ([url opcoes] (fetch! url opcoes timeout-ms))
  ([url opcoes prazo-ms]
   (let [metricas (js/require "../runtime/metrics.cjs")
         medida (categoria url)
         sinal (.-signal opcoes)
         limite (.timeout js/AbortSignal prazo-ms)
         sinal (if sinal (.any js/AbortSignal #js [sinal limite]) limite)
         opcoes (.assign js/Object #js {} opcoes #js {:signal sinal})]
     (p/then
      ((.-measure metricas) medida #(js/fetch url opcoes))
      (fn [res]
        ;; O corpo ainda chega depois dos headers: contabiliza a leitura sem
        ;; abandonar o AbortSignal que limita a resposta completa.
        (doseq [metodo ["json" "arrayBuffer"]
                :let [original (unchecked-get res metodo)]
                :when (fn? original)]
          (unchecked-set res metodo
                         (fn [] ((.-measure metricas) medida #(.call original res)))))
        res)))))

(defn acompanhar! [executar]
  ;; O servidor já abre o contexto de métricas; não crie outro que o esconda.
  (p/promise (executar)))
