(ns pokemon.tests.missions-a431-regression
  "Regressões existentes com armazenamento redirecionado para diretório temporário."
  (:require [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.nio.file Files]))

(def suites
  '[pokemon.tests.active-a2-test pokemon.tests.pvp-a3-test
    pokemon.tests.explore-a41-test pokemon.tests.pokedex-a42-test
    pokemon.tests.onboarding-m96-test pokemon.tests.hunt-m45-test
    pokemon.tests.gym-m48-test pokemon.tests.shop-m411-test])

(defn -main [& _]
  (doseq [suite suites] (require suite))
  (let [dir (str (Files/createTempDirectory "odisseu-a431-regression-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        client persistence/client
        result (with-redefs [persistence/client
                             (fn [options] (client (assoc options :storage-dir dir)))]
                 (apply test/run-tests suites))]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
