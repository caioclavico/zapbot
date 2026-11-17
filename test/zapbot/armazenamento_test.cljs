(ns zapbot.armazenamento-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]))

(deftest operacao-do-cassandra-tenta-novamente-antes-de-falhar
  (async done
    (let [tentativas (atom 0)]
      (-> (armazenamento/tentar-operacao!
           (fn []
             (if (< (swap! tentativas inc) 3)
               (p/rejected (js/Error. "falha temporária"))
               (p/resolved :gravado)))
           2 0)
          (p/then (fn [resultado]
                    (is (= :gravado resultado))
                    (is (= 3 @tentativas))
                    (done)))
          (p/catch (fn [erro]
                     (is false (str "As novas tentativas não recuperaram a gravação: " erro))
                     (done)))))))

(deftest bot-carrega-apenas-modulos-registrados
  (async done
    (let [anteriores @armazenamento/registros
          chamadas (atom [])
          c #js {:execute (fn [consulta parametros _]
                            (swap! chamadas conj [consulta (js->clj parametros)])
                            (p/resolved #js {:rows #js []}))}]
      (reset! armazenamento/registros {"rank" [(atom {}) identity]
                                       "participantes" [(atom {}) identity]})
      (-> (armazenamento/carregar-registrados! c)
          (p/then (fn [_]
                    (is (= 4 (count @chamadas)))
                    (is (= #{["rank"] ["participantes"]} (set (map second @chamadas))))
                    (is (every? #(str/includes? (first %) " WHERE ") @chamadas))))
          (p/catch (fn [erro] (is false (str erro))))
          (p/finally (fn [] (reset! armazenamento/registros anteriores) (done)))))))

(deftest recibo-http-nao-confirma-sem-cassandra
  (async done
    (let [anterior @armazenamento/client]
      (reset! armazenamento/client nil)
      (-> (armazenamento/salvar-confirmado! "rank" {})
          (p/then (fn [_] (is false "Confirmação não pode depender apenas da memória.")))
          (p/catch (fn [erro] (is (str/includes? (.-message erro) "indisponível"))))
          (p/finally (fn [] (reset! armazenamento/client anterior) (done)))))))

(deftest staging-datomic-jvm-nao-constroi-cliente-cassandra
  "M7.7 prova negativa: com cassandra-boot desligado, iniciar! não instancia Client."
  (async done
    (let [antes-construido (armazenamento/cliente-construido?)
          antes-client @armazenamento/client]
      (with-redefs [zapbot.config/cassandra-boot-enabled? false]
        (reset! armazenamento/client nil)
        (-> (armazenamento/iniciar!)
            (p/then (fn [res]
                      (is (= false (:cassandra res)))
                      (is (false? (armazenamento/cliente-ativo?)))
                      ;; Não pode ter construído Client nesta chamada.
                      (is (= antes-construido (armazenamento/cliente-construido?)))
                      (p/catch
                       (p/then (armazenamento/salvar-confirmado! "pokemon-http-deliveries" {"x" 1})
                               (fn [_] (is false "deveria falhar sem Cassandra")))
                       (fn [erro]
                         (is (str/includes? (.-message erro) "indisponível"))))))
            (p/catch (fn [erro]
                       (is false (str erro))
                       nil))
            (p/finally (fn []
                         (reset! armazenamento/client antes-client)
                         (is (nil? (armazenamento/obter "pokemon-http-deliveries")))
                         (done))))))))
