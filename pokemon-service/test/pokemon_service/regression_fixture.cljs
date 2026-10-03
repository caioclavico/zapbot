(ns pokemon-service.regression-fixture
  "Driver local mínimo para executar as regressões antigas sem Cassandra real."
  (:require [zapbot.armazenamento :as armazenamento]))

(defn iniciar! []
  (reset! armazenamento/client
          #js {:getState (fn [] #js {:getConnectedHosts (fn [] #js [#js {}])})
               :execute (fn [& _] (js/Promise.resolve #js {:rows #js []}))})
  (reset! armazenamento/pronto true)
  (reset! armazenamento/falhas-gravacao {})
  (reset! armazenamento/filas-gravacao {}))

(defn parar! []
  (reset! armazenamento/pronto false)
  (reset! armazenamento/client nil))
