(ns zapbot.armazenamento-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]))

(defn- fake-client [executar]
  #js {:execute executar :connect (fn [] (p/resolved nil))
       :shutdown (fn [] (p/resolved nil))
       :getState (fn [] #js {:getConnectedHosts (fn [] #js ["fake"])})})

(deftest le-modulo-com-paginacao-e-sem-leitura-geral
  (async done
    (let [consultas (atom [])
          c (fake-client
             (fn [consulta args ^js opcoes]
               (swap! consultas conj [consulta (js->clj args)])
               (p/resolved
                (if (str/includes? consulta "particionado")
                  (if (.-pageState opcoes)
                    #js {:rows #js [#js {:particao "outro-chat" :valor "{\"xp\":2}"}]}
                    #js {:rows #js [#js {:particao "chat" :valor "{\"xp\":1}"}]
                         :pageState "pagina-2"})
                  #js {:rows #js []}))))]
      (-> (armazenamento/carregar-modulo! c "treinador")
          (p/then (fn [[modulo dados]]
                    (is (= "treinador" modulo))
                    (is (= {"chat" {"xp" 1} "outro-chat" {"xp" 2}} dados))
                    (is (= 3 (count @consultas)))
                    (is (every? #(= ["treinador"] (second %)) @consultas))
                    (is (every? #(str/includes? (first %) " WHERE ") @consultas))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally done)))))

(deftest recusa-legado-pendente-sem-escrever-ou-migrar
  (async done
    (let [consultas (atom [])
          c (fake-client
             (fn [consulta _ _]
               (swap! consultas conj consulta)
               (p/resolved
                (if (str/includes? consulta "particionado")
                  #js {:rows #js []}
                  #js {:rows #js [#js {:valor "{\"chat\":{\"ash\":{\"xp\":5}}}"}]}))))]
      (-> (armazenamento/carregar-modulo! c "treinador")
          (p/then (fn [_] (is false "Legado sem marcador precisa bloquear startup.")))
          (p/catch (fn [erro]
                     (is (str/includes? (.-message erro) "sem marcador"))
                     (is (every? #(str/starts-with? % "SELECT ") @consultas))))
          (p/finally done)))))

(deftest reserva-lwt-confirma-cache-e-recupera-valor-ja-reservado
  (async done
    (let [antes [@armazenamento/client @armazenamento/modulos @armazenamento/cache
                 @armazenamento/confirmados @armazenamento/filas-gravacao]
          chamadas (atom 0)
          c (fake-client
             (fn [consulta _ _]
               (is (str/ends-with? consulta "IF NOT EXISTS"))
               (let [primeira? (= 1 (swap! chamadas inc))]
                 (p/resolved #js {:wasApplied (fn [] primeira?)
                                 :first (fn [] #js {:valor "{\"status\":\"processing\"}"})}))))]
      (reset! armazenamento/client c)
      (reset! armazenamento/modulos #{"pokemon-http-requests"})
      (reset! armazenamento/cache {})
      (reset! armazenamento/confirmados {})
      (reset! armazenamento/filas-gravacao {})
      (-> (p/let [aplicado (armazenamento/reservar! "pokemon-http-requests" "request-1" {"status" "processing"})
                  repetido (armazenamento/reservar! "pokemon-http-requests" "request-1" {"status" "outro"})]
            (is (true? aplicado))
            (is (false? repetido))
            (is (= {"status" "processing"}
                   (get (armazenamento/obter "pokemon-http-requests") "request-1"))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally
           (fn []
             (doseq [[destino valor] (map vector
                                          [armazenamento/client armazenamento/modulos armazenamento/cache
                                           armazenamento/confirmados armazenamento/filas-gravacao] antes)]
               (reset! destino valor))
             (done)))))))

(deftest falha-lwt-nao-confirma-cache
  (async done
    (let [antes [@armazenamento/client @armazenamento/modulos @armazenamento/cache]
          c (fake-client (fn [_ _ _] (p/rejected (js/Error. "timeout incerto"))))]
      (reset! armazenamento/client c)
      (reset! armazenamento/modulos #{"pokemon-http-requests"})
      (reset! armazenamento/cache {})
      (-> (armazenamento/reservar! "pokemon-http-requests" "request-incerto" {"status" "processing"})
          (p/then (fn [_] (is false "Timeout de LWT não é confirmação.")))
          (p/catch (fn [_] (is (nil? (armazenamento/obter "pokemon-http-requests")))))
          (p/finally
           (fn []
             (doseq [[destino valor] (map vector [armazenamento/client armazenamento/modulos armazenamento/cache] antes)]
               (reset! destino valor))
             (done)))))))

(deftest hidratacao-de-watch-nao-grava-no-cassandra
  (async done
    (let [antes @armazenamento/hidratando?]
      (reset! armazenamento/hidratando? true)
      (-> (armazenamento/salvar! "pokemon-batalhas-ativas" {"chat" {"terminal" true}})
          (p/then (fn [_] (is (nil? (armazenamento/obter "pokemon-batalhas-ativas")))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn [] (reset! armazenamento/hidratando? antes) (done)))))))
