(ns pokemon-service.shutdown)
(def ^:private observer (js/require "../runtime/shutdown.cjs"))
(defn etapa [nome executar pendentes]
  (.stage observer nome executar #(clj->js (pendentes))))
