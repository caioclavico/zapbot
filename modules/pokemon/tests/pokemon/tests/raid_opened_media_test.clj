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

(deftest started-combat-from-raid-anexa-snapshot
  (let [raid {:fase "combate"
              :raid-id "raid-1"
              :nome-ginasio "🔥 Fogo"
              :ginasio "fogo"
              :hp-chefe 100
              :hp-max 100
              :vez "ash"
              :turn-index 0
              :ordem ["ash"]
              :chefe {:nome "Magmar" :species "magmar" :tipos ["fire"]
                      :raridade "raro" :hp 100
                      :imagem "https://example/magmar.png"}
              :participantes
              {"ash" {:nome "Ash" :hp 80
                      :pokemon {:nome "Wartortle" :species "wartortle"
                                :hp 100 :tipos ["water"] :raridade "incomum"
                                :imagem "https://example/wartortle.png"
                                :golpes [{:nome-exibicao "Water Gun" :tipo "water"
                                          :poder 40 :classe :especial}
                                         {:nome "Bite" :tipo "dark" :poder 60}]}}}}
        snap (raid-store/started-combat-from-raid raid)]
    (is (= "combate" (:fase snap)))
    (is (true? (:auto snap)))
    (is (= "Magmar" (get-in snap [:chefe :nome])))
    (is (= "Wartortle" (get-in snap [:vez-pokemon :nome])))
    (is (seq (get-in snap [:vez-pokemon :golpes])))
    (is (= "water" (get-in snap [:vez-pokemon :golpes 0 :tipo])))
    (is (some? (get-in snap [:chefe :imagem])))
    (is (some? (get-in snap [:vez-pokemon :raridade]))))
  (testing "lobby não gera snapshot de combate"
    (is (nil? (raid-store/started-combat-from-raid {:fase "inscricoes"})))))
