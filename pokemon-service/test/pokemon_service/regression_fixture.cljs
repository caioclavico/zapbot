(ns pokemon-service.regression-fixture
  "Driver local mínimo para executar as regressões antigas sem Cassandra real."
  (:require [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]))

(defn- resultado [rows aplicado?]
  #js {:rows (clj->js rows)
       :first (fn [] (when-let [row (first rows)] (clj->js row)))
       :wasApplied (fn [] (boolean aplicado?))})

(defn- fake-client [db]
  #js {:getState (fn [] #js {:getConnectedHosts (fn [] #js [#js {}])})
       :execute
       (fn [sql js-args _]
         (let [args (js->clj js-args)]
           (if-not (str/includes? sql "estado_particionado")
             (js/Promise.resolve (resultado [] false))
             (cond
               (str/starts-with? sql "SELECT")
               (let [[modulo particao] args
                     valor (get @db [modulo particao])]
                 (js/Promise.resolve
                  (resultado (when (some? valor) [{:valor valor}]) false)))

               (and (str/starts-with? sql "INSERT") (str/includes? sql "IF NOT EXISTS"))
               (let [[modulo particao valor] args
                     aplicado? (not (contains? @db [modulo particao]))]
                 (when aplicado? (swap! db assoc [modulo particao] valor))
                 (js/Promise.resolve (resultado [] aplicado?)))

               (and (str/starts-with? sql "UPDATE") (str/includes? sql "IF valor = ?"))
               (let [[valor modulo particao esperado] args
                     aplicado? (= esperado (get @db [modulo particao]))]
                 (when aplicado? (swap! db assoc [modulo particao] valor))
                 (js/Promise.resolve (resultado [] aplicado?))

               :else (js/Promise.resolve (resultado [] false)))))))
       :shutdown #(js/Promise.resolve nil)})

(defn iniciar! []
  (reset! armazenamento/client (fake-client (atom {})))
  (reset! armazenamento/pronto true)
  (reset! armazenamento/falhas-gravacao {})
  (reset! armazenamento/filas-gravacao {}))

(defn parar! []
  (reset! armazenamento/pronto false)
  (reset! armazenamento/client nil))
