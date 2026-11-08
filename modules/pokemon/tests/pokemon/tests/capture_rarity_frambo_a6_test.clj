(ns pokemon.tests.capture-rarity-frambo-a6-test
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.capture :as capture]))

(deftest raridade-lendario-mitico-mais-duros
  (is (<= (get capture/raridade-chance-base "mitico")
          (get capture/raridade-chance-base "lendario")
          (get capture/raridade-chance-base "epico")
          (get capture/raridade-chance-base "raro")))
  (is (< (get capture/raridade-chance-max "mitico")
         (get capture/raridade-chance-min "comum"))))

(deftest frambo-aumenta-chance-documentado
  (let [base 18
        sem (capture/chance-com-bola-frambo base "pokebola" false)
        com (capture/chance-com-bola-frambo base "pokebola" true)]
    (is (= base sem))
    (is (= (long (Math/round (* 18 1.5))) com))
    (is (> com sem))))

(deftest ultra-frambo-respeita-cap
  (let [c (capture/chance-com-bola-frambo 80 "ultra-bola" true)]
    (is (= 95 c))))
