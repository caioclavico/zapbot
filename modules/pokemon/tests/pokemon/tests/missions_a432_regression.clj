(ns pokemon.tests.missions-a432-regression
  "Mesmas regressões da A4.3.1 mais captura, combate e raid; storage temporário."
  (:require [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.tests.missions-a431-regression :as previous])
  (:import [java.nio.file Files]))

(def suites (into previous/suites
                  '[pokemon.tests.capture-m33-test pokemon.tests.battle-m34-test
                    pokemon.tests.raid-m49-test]))

(defn -main [& _]
  (doseq [suite suites] (require suite))
  (let [dir (str (Files/createTempDirectory "odisseu-a432-regression-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        client persistence/client
        result (with-redefs [persistence/client
                             (fn [options] (client (assoc options :storage-dir dir)))]
                 (apply test/run-tests suites))]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
