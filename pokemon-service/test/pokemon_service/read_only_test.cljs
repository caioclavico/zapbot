(ns pokemon-service.read-only-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [clojure.string :as str]
            [pokemon-service.entry :as entry]
            [zapbot.config :as config]
            [zapbot.armazenamento :as storage]
            [zapbot.pokemon.core :as pokemon]))

(deftest barreira-de-escrita-bloqueia-driver-cache-e-reserva
  (let [chamadas (atom 0)
        cache-antes @storage/cache
        cliente #js {:execute (fn [& _] (swap! chamadas inc))}]
    (with-redefs [config/read-only? true]
      (doseq [consulta ["INSERT INTO x VALUES (?)" "UPDATE x SET a = ?" "DELETE FROM x"
                        "CREATE TABLE x (a text)" "TRUNCATE x" "DROP TABLE x"
                        "BEGIN BATCH INSERT INTO x VALUES (?); APPLY BATCH"
                        "SELECT * FROM x; DELETE FROM x"]]
        (is (thrown? js/Error (storage/executar! cliente consulta []))))
      (is (thrown? js/Error (storage/salvar! "treinador" {"alterado" true})))
      (is (thrown? js/Error (storage/reservar! "pokemon-http-requests" "id" {})))
      (is (thrown? js/Error (entry/command #js {:command "pk treinador"} nil)))
      (is (thrown? js/Error (entry/despachar {} "loja detalhes atadura")))
      (is (thrown? js/Error (pokemon/iniciar! identity)))
      (is (= cache-antes @storage/cache))
      (is (zero? @chamadas)))))

(deftest hidratacao-real-read-only-nao-inicia-timers-nem-envia-escritas
  (async done
    (let [destinos [storage/client storage/pronto storage/hidratando? storage/cache
                    storage/confirmados storage/filas-gravacao storage/falhas-gravacao
                    storage/estatisticas]
          antes (mapv deref destinos)
          registros (mapv (fn [[_ [destino _]]] [destino @destino]) @storage/registros)
          ro config/read-only?
          timeout js/setTimeout
          intervalo js/setInterval
          queries (atom [])
          timers (atom 0)
          fake #js {:connect (fn [] (p/resolved nil))
                     :shutdown (fn [] (p/resolved nil))
                     :getState (fn [] #js {:getConnectedHosts (fn [] #js ["fixture"])})
                     :execute (fn [consulta args _]
                                (swap! queries conj consulta)
                                (when-not (str/starts-with? consulta "SELECT ")
                                  (throw (js/Error. "Escrita inesperada")))
                                (p/resolved
                                 #js {:rows (if (and (str/includes? consulta "estado_particionado")
                                                     (= "pokemon-remocoes-pendentes" (aget args 0)))
                                              #js [#js {:particao "[\"fixture\" \"player\"]"
                                                        :valor "{\"estado\":\"{:token \\\"pending\\\", :pronto-em 0}\"}"}]
                                              #js [])}))}]
      (set! config/read-only? true)
      (set! js/setTimeout (fn [& _] (swap! timers inc) (throw (js/Error. "Timer inesperado"))))
      (set! js/setInterval (fn [& _] (swap! timers inc) (throw (js/Error. "Intervalo inesperado"))))
      (-> (with-redefs [storage/Client (fn [_] fake)] (entry/initialize))
          (p/then (fn [_]
                    (entry/start-timers (fn [& _] (is false "Evento inesperado")))
                    (is (true? (entry/is-ready)))
                    (is (true? (:hydrated (storage/diagnostico))))
                    (is (pos? (:hydratedModules (storage/diagnostico))))
                    (is (= 1 (:hydratedPartitions (storage/diagnostico))))
                    (is (seq @queries))
                    (is (every? #(str/starts-with? % "SELECT ") @queries))
                    (is (zero? (:writeQueries (storage/diagnostico))))
                    (is (zero? @timers))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally
           (fn []
             (set! js/setTimeout timeout)
             (set! js/setInterval intervalo)
             (reset! storage/hidratando? true)
             (doseq [[destino valor] registros] (reset! destino valor))
             (doseq [[destino valor] (map vector destinos antes)] (reset! destino valor))
             (set! config/read-only? ro)
             (done)))))))
