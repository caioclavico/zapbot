(ns pokemon.tests.raid-opened-media-test
  "Mídia de revelação do chefe no raid-opened (sem migration)."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]))

(deftest opened-raid-id-parse
  (is (= "raid-auto:c:1" (raid-store/opened-raid-id "raid-opened:raid-auto:c:1")))
  (is (nil? (raid-store/opened-raid-id "raid-reminder:x")))
  (is (nil? (raid-store/opened-raid-id nil))))

(deftest opened-media-from-raid-extrai-chefe
  (let [media (raid-store/opened-media-from-raid
               {:nome-ginasio "Torre"
                :ginasio "torre"
                :boss-categoria "mitico"
                :chefe {:nome "Mew"
                        :species "mew"
                        :imagem "https://example/mew.png"
                        :raridade "mitico"}})]
    (is (= "Mew" (:nome media)))
    (is (= "mew" (:species media)))
    (is (= "https://example/mew.png" (:imagem media)))
    (is (= "mitico" (:categoria media)))
    (is (= "Torre" (:ginasio media))))
  (testing "sem chefe → nil"
    (is (nil? (raid-store/opened-media-from-raid {})))
    (is (nil? (raid-store/opened-media-from-raid nil)))))
