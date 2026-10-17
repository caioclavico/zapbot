(ns pokemon.tests.xp-equivalence-test
  "Equivalência pura das regras de XP vs fixtures do legado."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.xp :as xp]))

(defn- load-fixtures
  []
  (edn/read-string
   (slurp (io/file (persistence/project-root)
                   "modules/pokemon/tests/pokemon/tests/fixtures/legacy_xp.edn"))))

(deftest progresso-treinador-equivale-ao-legado
  (doseq [caso (:trainer-progress-cases (load-fixtures))]
    (testing (str "xp=" (:xp caso))
      (is (= (select-keys (xp/progresso-treinador (:xp caso))
                          [:nivel :xp-atual :xp-necessario])
             (select-keys caso [:nivel :xp-atual :xp-necessario]))))))

(deftest xp-pokemon-equivale-ao-legado
  (doseq [caso (:pokemon-xp-cases (load-fixtures))]
    (testing (:label caso)
      (let [{:keys [registro subidas nivel-final]}
            (xp/aplicar-xp-pokemon (:registro caso) (:quantidade caso))
            expect (:expect caso)]
        (is (= (:nivel expect) (:nivel registro) nivel-final))
        (is (= (:xp-desde-nivel expect) (:xp-desde-nivel registro)))
        (is (= (:subidas expect) subidas))
        (is (= (:hp expect) (:hp registro)))
        (is (= (:hp-atual expect) (:hp-atual registro)))
        (is (= 80 (:amizade registro)))))))
