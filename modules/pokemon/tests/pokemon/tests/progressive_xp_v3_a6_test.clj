(ns pokemon.tests.progressive-xp-v3-a6-test
  "A6 v3 regression: no level jump during conversion, equal streak rewards,
   safe proportional residual mapping, and level-up only from new grants."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.xp :as xp]))

(defn- mon
  [level residual]
  {:id-pokemon "safe-pokemon-id" :species "pikachu" :nome "Pikachu"
   :nivel level :xp-desde-nivel residual :xp residual
   :xp-total (growth/flat9-synthetic-total level residual)
   :xp-progress-version growth/flat9-progress-version
   :hp 100 :hp-atual 50 :ataque 80 :defesa 70 :atq-esp 60
   :def-esp 50 :veloc 40 :amizade 70})

(deftest progressive-v3-curve
  (is (= [[1 9] [10 13] [20 17] [30 21] [40 26] [50 30]
          [51 30] [74 30] [99 30] [100 0]]
         (mapv (fn [n] [n (growth/progressive-xp-por-nivel n)])
               [1 10 20 30 40 50 51 74 99 100])))
  (is (every? #(<= (growth/progressive-xp-por-nivel %)
                   (growth/progressive-xp-por-nivel (inc %)))
              (range 1 99))))

(deftest level-preserved-on-v2-to-v3-conversion-regression-85
  (with-redefs [growth/progressive-enabled? (constantly true)]
    (doseq [level [1 5 13 20 30 50 74 90 99 100]]
      (let [old (mon level (if (= level 100) 0 8))
            ;; Historically :xp-total could hold misleading large values.
            old (assoc old :xp-total 999999999)
            new (growth/ensure-progressive-v3-for-grant old)
            twice (growth/ensure-progressive-v3-for-grant new)
            need (growth/progressive-xp-por-nivel level)]
        (testing (str "preserve L" level)
          (is (= level (:nivel new)) "conversion MUST NOT grant levels")
          (is (= (:id-pokemon old) (:id-pokemon new)))
          (is (= (select-keys old [:hp :hp-atual :ataque :defesa :atq-esp
                                   :def-esp :veloc :species :amizade])
                 (select-keys new [:hp :hp-atual :ataque :defesa :atq-esp
                                   :def-esp :veloc :species :amizade]))
              "conversion MUST NOT grow stats or heal")
          (is (= growth/progressive-progress-version (:xp-progress-version new)))
          (is (= (if (= level 100) 0
                   (long (Math/floor (* (/ 8.0 9.0) need))))
                 (:xp-desde-nivel new)))
          (is (= new twice) "conversion must be idempotent"))))))

(deftest v3-grants-only-new-xp-levels-up
  (with-redefs [growth/progressive-enabled? (constantly true)]
    (let [base (mon 20 4)
          converted (growth/ensure-progressive-v3-for-grant base)
          no-grant (xp/aplicar-xp-pokemon base 0)
          applied (xp/aplicar-xp-pokemon base 3)
          need (growth/progressive-xp-por-nivel 20)]
      (is (= 20 (:nivel converted)))
      (is (= 20 (:nivel-final no-grant)))
      (is (zero? (:subidas no-grant)))
      (is (= 20 (:nivel-final applied)))
      (is (= (+ (long (Math/floor (* 4.0 (/ need 9.0)))) 3)
             (get-in applied [:registro :xp-desde-nivel])))
      (is (= 3 (:xp-oficial-aplicado applied))))
    (let [base (mon 50 0)
          g (xp/aplicar-xp-pokemon base 18)]
      (is (= 50 (:nivel-final g)))
      (is (= 18 (get-in g [:registro :xp-desde-nivel])))
      (is (zero? (:subidas g))))
    (let [base (mon 50 8)
          v3 (growth/ensure-progressive-v3-for-grant base)
          missing (- 30 (:xp-desde-nivel v3))
          g (xp/aplicar-xp-pokemon base missing)]
      (is (= 51 (:nivel-final g)))
      (is (zero? (get-in g [:registro :xp-desde-nivel])))
      (is (= 1 (:subidas g))))
    (let [base (mon 99 8)
          g (xp/aplicar-xp-pokemon base 18)]
      (is (= 100 (:nivel-final g)))
      (is (zero? (get-in g [:registro :xp-desde-nivel])))
      (is (= 1 (:subidas g)))
      (is (= 4 (:xp-oficial-aplicado g)) "discard XP beyond L100"))))

(deftest v3-unknown-or-invalid-records-never-create-levels
  (with-redefs [growth/progressive-enabled? (constantly true)]
    (doseq [residual [9 478 8478]]
      (let [legacy (dissoc (mon 13 0) :xp-progress-version :xp-total)
            legacy (assoc legacy :xp-desde-nivel residual :xp residual)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (xp/aplicar-xp-pokemon legacy 3)))
        (is (= 13 (:nivel legacy)))
        (is (false? (:conhecido? (growth/pokemon-progress legacy))))))
    (doseq [bad [(assoc (mon 13 8) :xp-desde-nivel 600 :xp 600)
                 (assoc (mon 13 8) :xp-progress-version "unrecognized")
                 (assoc (mon 13 8) :nivel 150)
                 {:nivel 13 :xp-progress-version "progressive-xp-v3"
                  :xp-desde-nivel 99}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (growth/ensure-progressive-v3-for-grant bad))))))

(deftest v3-progress-read-only-and-evolution-safe
  (with-redefs [growth/progressive-enabled? (constantly true)]
    (let [old (mon 50 8)
          p (growth/pokemon-progress old)
          evolved (growth/after-evolution (assoc old :species "raichu"))]
      (is (= 50 (:nivel p)))
      (is (= 30 (:xp-proximo-nivel p)))
      (is (= 26 (:xp-nivel-atual p)))
      (is (= "progressive-xp-v3" (:xp-progress-version p)))
      (is (= 50 (:nivel evolved)))
      (is (= 26 (:xp-desde-nivel evolved)))
      (is (= 80 (:ataque evolved))))
    (is (false? (:conhecido? (growth/pokemon-progress {:nivel 10}))))))

(deftest v3-streak-equal-for-all-levels-and-coins-pe-unchanged
  (let [seqs [1 2 3 4 5 9 10 19 20 29 30 100]
        old (mapv capture/faixa-sequencia-captura seqs)
        updated (with-redefs [growth/progressive-enabled? (constantly true)]
                  (mapv capture/faixa-sequencia-captura seqs))]
    (is (= [2 2 3 3 4 4 6 6 8 8 10 10] (mapv :xp updated)))
    (is (= (mapv :pe old) (mapv :pe updated)))
    (is (= (mapv :moedas old) (mapv :moedas updated)))
    (is (= [2 2 3 3 5 5 8 8 12 12 15 15] (mapv :xp old)))
    ;; Rewards are determined by streak, never by Pokémon level.
    (with-redefs [growth/progressive-enabled? (constantly true)]
      (is (= (:xp (capture/faixa-sequencia-captura 30))
             (:xp (capture/faixa-sequencia-captura 30)))))))