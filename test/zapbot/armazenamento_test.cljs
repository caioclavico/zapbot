(ns zapbot.armazenamento-test
  (:require [cljs.test :refer-macros [async deftest is]]
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
