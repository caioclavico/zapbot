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

(deftest progresso-treinador-legado-equivale-ao-historico
  (doseq [caso (:trainer-progress-cases (load-fixtures))]
    (testing (str "xp=" (:xp caso))
      (is (= (select-keys (xp/progresso-treinador-legado (:xp caso))
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

(deftest xp-insignias-deriva-dos-marcos
  (is (= 0 (xp/xp-insignias-from-metrics {})))
  (is (= 3 (xp/xp-insignias-from-metrics
            {:doacoes-pokemon 1 :vitorias-treinador 0 :maior-sequencia-capturas 0})))
  ;; 1+10 doações (3+6) + 1 vitória (3) + seq 3 (3) = 15
  (is (= 15 (xp/xp-insignias-from-metrics
             {:doacoes-pokemon 10 :vitorias-treinador 1 :maior-sequencia-capturas 3})))
  (is (= (+ 3 6 12 24 3 6 12 24 3 6 12 24)
         (xp/xp-insignias-from-metrics
          {:doacoes-pokemon 100 :vitorias-treinador 100 :maior-sequencia-capturas 20}))))

(deftest catalogo-insignias-espelha-master
  (is (= 12 (count xp/catalogo-insignias)))
  (is (= "Doador de Pokémon" (:nome (first xp/catalogo-insignias))))
  (is (= "Mestre da captura" (:nome (last xp/catalogo-insignias))))
  (let [lista (xp/insignias-treinador {:vitorias-treinador 1 :maior-sequencia-capturas 3})]
    (is (= 2 (count (filter :conquistada lista))))
    (is (some #(and (= "Primeira vitória" (:nome %)) (:conquistada %)) lista))
    (is (some #(and (= "Capturador" (:nome %)) (:conquistada %)) lista))
    (is (not-any? #(and (= "Campeão" (:nome %)) (:conquistada %)) lista))))
