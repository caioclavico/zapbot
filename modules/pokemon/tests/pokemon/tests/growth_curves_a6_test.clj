(ns pokemon.tests.growth-curves-a6-test
  "Curvas oficiais PokéAPI + conversão legado residual → growth-v1."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [datomic.client.api :as d]))

(deftest six-curves-loaded-and-monotonic
  (doseq [rate growth/curve-keys]
    (testing (str rate)
      (is (= 0 (growth/total-xp-for-level rate 1)))
      (is (pos? (growth/total-xp-for-level rate 100)))
      (doseq [n (range 1 100)]
        (is (<= (growth/total-xp-for-level rate n)
                (growth/total-xp-for-level rate (inc n))))))))

(deftest official-totals-match-pokeapi-snapshot
  (let [tables (growth/tables)]
    (is (= 1000000 (growth/total-xp-for-level :medium-fast 100)))
    (is (= 800000 (growth/total-xp-for-level :fast 100)))
    (is (= 1250000 (growth/total-xp-for-level :slow 100)))
    (is (= 1059860 (growth/total-xp-for-level :medium-slow 100)))
    (is (= 600000 (growth/total-xp-for-level :erratic 100)))
    (is (= 1640000 (growth/total-xp-for-level :fluctuating 100)))
    (is (= "medium" (get-in tables [:medium-fast :pokeapi-name])))
    (is (= "slow-then-very-fast" (get-in tables [:erratic :pokeapi-name])))))

(deftest species-resolve-to-official-curves
  (is (= :medium-slow (growth/growth-rate-for-species "bulbasaur")))
  (is (= :medium-fast (growth/growth-rate-for-species "pikachu")))
  (is (= :slow (growth/growth-rate-for-species "magikarp")))
  (is (= :fast (growth/growth-rate-for-species "clefairy")))
  (is (= :erratic (growth/growth-rate-for-species "nincada")))
  (is (= :fluctuating (growth/growth-rate-for-species "wailmer")))
  (is (= :fast (growth/growth-rate-for-species "wishiwashi-solo"))
      "forma resolve para espécie-base wishiwashi (fast)"))

(deftest level-from-total-and-boundaries
  (doseq [rate [:medium-fast :erratic :fluctuating]]
    (is (= 1 (growth/pokemon-level-from-total-xp rate 0)))
    (is (= 2 (growth/pokemon-level-from-total-xp rate
                                                (growth/total-xp-for-level rate 2))))
    (is (= 50 (growth/pokemon-level-from-total-xp rate
                                                 (growth/total-xp-for-level rate 50))))
    (is (= 99 (growth/pokemon-level-from-total-xp rate
                                                 (dec (growth/total-xp-for-level rate 100)))))
    (is (= 100 (growth/pokemon-level-from-total-xp rate
                                                  (growth/total-xp-for-level rate 100))))
    (is (= 100 (growth/pokemon-level-from-total-xp rate
                                                  (+ (growth/total-xp-for-level rate 100) 99999))))))

(deftest erratic-fluctuating-piecewise-samples
  ;; Valores oficiais Pontos L50 / L68 / L99 (PokéAPI)
  (is (= 125000 (growth/total-xp-for-level :erratic 50)))
  (is (= 257834 (growth/total-xp-for-level :erratic 68)))
  (is (= 591882 (growth/total-xp-for-level :erratic 99)))
  (is (= 142500 (growth/total-xp-for-level :fluctuating 50)))
  (is (= 1640000 (growth/total-xp-for-level :fluctuating 100))))

(deftest legacy-residual-conversion-preserves-level
  (let [legado {:species "pikachu" :nivel 24 :xp-desde-nivel 4
                :hp 100 :hp-atual 100 :ataque 50 :defesa 50
                :atq-esp 50 :def-esp 50 :veloc 50}
        conv (growth/convert-legacy-residual legado)
        again (growth/convert-legacy-residual conv)]
    (is (= 24 (:nivel conv)))
    (is (= "growth-v1" (:xp-progress-version conv)))
    (is (= 4 (:xp-legacy-residual conv)))
    (is (= (:xp-total conv) (:xp-total again)) "conversão idempotente")
    (is (<= (growth/total-xp-for-level :medium-fast 24)
            (:xp-total conv)
            (dec (growth/total-xp-for-level :medium-fast 25))))
    (let [frac (/ 4.0 9.0)
          span (growth/level-span :medium-fast 24)
          expected (+ (growth/total-xp-for-level :medium-fast 24)
                      (long (Math/floor (* frac span))))]
      (is (= expected (:xp-total conv))))))

(deftest grant-multi-level-and-cap
  (let [base {:species "caterpie" :nivel 1 :xp-desde-nivel 0
              :hp 100 :hp-atual 100 :ataque 10 :defesa 10
              :atq-esp 10 :def-esp 10 :veloc 10 :amizade 70}
        ;; medium-fast L10 = 1000
        big (xp/aplicar-xp-pokemon base 1000)]
    (is (= 10 (:nivel-final big)))
    (is (= 9 (:subidas big)))
    (is (= 0 (get-in big [:registro :xp-desde-nivel])))
    (let [capped (xp/aplicar-xp-pokemon
                  (assoc (:registro big)
                         :nivel 100
                         :xp-total (growth/total-xp-for-level :medium-fast 100)
                         :xp-progress-version "growth-v1"
                         :growth-rate :medium-fast)
                  50000)]
      (is (= 100 (:nivel-final capped)))
      (is (= 0 (:subidas capped)))
      (is (= (growth/total-xp-for-level :medium-fast 100)
             (get-in capped [:registro :xp-total]))))))

(deftest evolution-keeps-progress-never-lowers-level
  (let [pre {:species "charmander" :nivel 16 :xp-desde-nivel 2
             :hp 100 :hp-atual 100 :ataque 50 :defesa 50
             :atq-esp 50 :def-esp 50 :veloc 50}
        conv (growth/convert-legacy-residual pre)
        evo (growth/after-evolution (assoc conv :species "charmeleon" :nome "Charmeleon"))]
    (is (>= (:nivel evo) 16))
    (is (>= (:xp-total evo) (growth/total-xp-for-level (:growth-rate evo) (:nivel evo))))
    (is (= "growth-v1" (:xp-progress-version evo)))))

(deftest migration-037-is-additive
  (let [client (persistence/client {:system (str "g037-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/g037-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "g037")]
    (schema/install! conn)
    (let [db (d/db conn)
          attrs #{:pokemon/xp-total :pokemon/xp-progress-version
                  :pokemon/xp-legacy-residual :pokemon/growth-rate}]
      (doseq [a attrs]
        (is (some? (d/pull db '[:db/ident] a)))))))

(deftest datomic-grant-persists-growth-v1
  (let [client (persistence/client {:system (str "gxp-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/gxp-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "gxp")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "c" :player-id "ash"})
    (collection/adicionar-pokemon!
     s "c" "ash"
     {:id-pokemon "p1" :species "geodude" :nome "Geodude"
      :nivel 1 :xp-desde-nivel 0 :hp 40 :hp-atual 40
      :ataque 40 :defesa 40 :atq-esp 30 :def-esp 30 :veloc 20
      :tipos ["rock"] :amizade 70
      :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                :poder 40 :classe :fisico}]}
     20 nil)
    (collection/ganhar-xp-pokemon! s "c" "ash" 0 9)
    (let [poke (first (ports/list-collection s "c" "ash" "a"))]
      (is (= 2 (:nivel poke)))
      (is (= "growth-v1" (:xp-progress-version poke)))
      (is (some? (:xp-total poke)))
      (is (= :medium-slow (:growth-rate poke))))))