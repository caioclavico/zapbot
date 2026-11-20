(ns pokemon.tests.battle-image-rollback-a6-test
  "Compatibilidade de snapshot entre a JVM b04682f e a master b02576b.
   Rollback de imagem não apaga o EDN. A JVM antiga lê o mapa e ignora
   clima, terreno, campo e volátil. Isso é o ponto de não retorno de uma
   batalha já gravada pela versão nova."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.battle :as battle]))

(def ^:private novo
  {:battle-id "nova"
   :pokemons {:x {:nome "Snorlax"} :o {:nome "Onix"}}
   :volatil {:x {:protecao? true}}
   :clima {:tipo :neve :restante 5}
   :terreno {:tipo :grama :restante 4}
   :campo {:previsto {:x {:restante 2 :nome "Visão do Futuro"}}}})

(defn- visao-antiga
  [state]
  (dissoc state :volatil :clima :terreno :campo))

(deftest ida-e-volta-preserva-as-chaves-novas
  (let [lido (battle/decode-compact-edn (battle/encode-state-edn novo))]
    (is (= "nova" (:battle-id lido)))
    (is (= :neve (get-in lido [:clima :tipo])))
    (is (= true (get-in lido [:volatil :x :protecao?])))
    (is (= 2 (get-in lido [:campo :previsto :x :restante])))))

(deftest jvm-antiga-nao-quebra-e-deixa-de-interpretar-o-clima
  (let [lido (battle/decode-compact-edn (battle/encode-state-edn novo))
        antigo (visao-antiga lido)]
    (is (= "nova" (:battle-id antigo)))
    (is (nil? (:clima antigo)))
    (is (nil? (:volatil antigo)))
    (is (= "Snorlax" (get-in antigo [:pokemons :x :nome])))))

(deftest estado-antigo-abre-na-jvm-nova
  (let [lido (battle/decode-compact-edn "{:battle-id \"antiga\" :pokemons {:x {:nome \"Snorlax\"}}}")]
    (is (= "antiga" (:battle-id lido)))
    (is (nil? (:clima lido)))
    (is (nil? (:campo lido)))))
