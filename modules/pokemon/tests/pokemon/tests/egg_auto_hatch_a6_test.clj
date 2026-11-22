(ns pokemon.tests.egg-auto-hatch-a6-test
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]))

(deftest newly-ready-eggs-detects-transition
  (let [ovo (eggs/novo-ovo "e1" :raro 0 "bulbasaur")
        antes (-> (eggs/estado-inicial)
                  (eggs/conceder-ovo ovo)
                  (eggs/incubar "e1" "permanente" 1000 "save-a" 1))
        depois (eggs/aplicar-pontos antes "evt-1" 250)
        ready (egg-activity/newly-ready-eggs antes depois)]
    (is (= 1 (count ready)))
    (is (= "e1" (:egg-id (first ready))))
    (is (egg-activity/ready-egg? (first ready)))
    (is (empty? (egg-activity/newly-ready-eggs depois depois)))))

(deftest aplicar-pontos-zero-nao-nasce
  (let [ovo (assoc (eggs/novo-ovo "e2" :comum 0 "squirtle") :points 99)
        estado (-> (eggs/estado-inicial)
                   (update :eggs conj ovo)
                   (assoc-in [:incubators 0 :active-egg-id] "e2")
                   (assoc-in [:eggs 0 :state] :incubating)
                   (assoc-in [:eggs 0 :incubator-id] "permanente")
                   (assoc-in [:eggs 0 :save-id] "save-a")
                   (assoc-in [:eggs 0 :fence-generation] 1)
                   (assoc-in [:eggs 0 :points] 99))
        depois (eggs/aplicar-pontos estado "evt-z" 0)]
    (is (= 99 (get-in depois [:eggs 0 :points])))
    (is (empty? (egg-activity/newly-ready-eggs estado depois)))))

(deftest auto-hatch-flag-off-by-default-in-test-env
  ;; Sem env ADVENTURE_AUTO_HATCH → falsey / off
  (is (not (egg-activity/auto-hatch-enabled?))))
