(ns pokemon.tests.tx-datoms-conflict-a6-test
  "Classificação de datoms-conflict vs CAS transitório — sem retry cego."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.errors :as err]))

(deftest datoms-conflict-detectado
  (let [ex (ex-info ":db.error/datoms-conflict Two datoms in the same transaction"
                    {:cognitect.anomalies/category :cognitect.anomalies/conflict
                     :db/error :db.error/datoms-conflict})]
    (is (true? (err/datoms-conflict? ex)))
    (is (false? (err/conflict? ex))
        "datoms-conflict NÃO é tratado como CAS recuperável")))

(deftest cas-conflict-continua-recuperavel
  (let [ex (ex-info "CAS failed"
                    {:cognitect.anomalies/category :cognitect.anomalies/conflict})]
    (is (false? (err/datoms-conflict? ex)))
    (is (true? (err/conflict? ex)))))

(deftest mensagem-two-datoms-sem-categoria
  (let [ex (ex-info "two datoms in the same transaction contradict each other"
                    {})]
    (is (true? (err/datoms-conflict? ex)))
    (is (false? (err/conflict? ex)))))
