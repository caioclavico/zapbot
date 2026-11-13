(ns pokemon.tests.growth-v2-simple-a6-test
  "Progressão simples growth-v2 + conversão proporcional a partir de growth-v1."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [datomic.client.api :as d]))

(deftest xp-necessario-table
  (doseq [[n need] [[1 10] [5 18] [10 28] [20 48] [30 68]
                    [40 88] [50 108] [75 158] [99 206]]]
    (is (= need (growth/xp-necessario n)) (str "L" n)))
  (is (nil? (growth/xp-necessario 100)))
  (is (zero? (growth/level-span-simple 100))))

(deftest total-xp-minimo-closed-form
  (doseq [n [1 2 5 10 20 30 50 99 100]]
    (is (= (* (dec n) (+ n 8)) (growth/total-xp-for-level-simple n))
        (str "total-min L" n)))
  (testing "soma dos spans bate com a forma fechada"
    (is (= (growth/total-xp-for-level-simple 20)
           (reduce + 0 (map growth/level-span-simple (range 1 20)))))))

(deftest grant-without-level-up
  (let [r {:species "pikachu" :nivel 10 :xp-desde-nivel 5
           :xp-progress-version "growth-v2" :growth-rate :simple
           :xp-total (+ (growth/total-xp-for-level-simple 10) 5)
           :hp 50 :hp-atual 50 :ataque 40 :defesa 30
           :atq-esp 40 :def-esp 40 :veloc 50 :amizade 70}
        g (xp/aplicar-xp-pokemon r 10)]
    (is (= 10 (:nivel-final g)))
    (is (zero? (:subidas g)))
    (is (= 15 (get-in g [:registro :xp-desde-nivel])))
    (is (= 28 (growth/xp-necessario 10)))))

(deftest grant-exact-one-level-preserves-excess
  "Exemplo da missão: L10 com 25/28 + 10 XP → L11 com 7/30."
  (let [r {:species "charmeleon" :nivel 10 :xp-desde-nivel 25
           :xp-progress-version "growth-v2" :growth-rate :simple
           :xp-total (+ (growth/total-xp-for-level-simple 10) 25)
           :hp 80 :hp-atual 80 :ataque 50 :defesa 40
           :atq-esp 50 :def-esp 50 :veloc 60 :amizade 70}
        g (xp/aplicar-xp-pokemon r 10)
        reg (:registro g)]
    (is (= 11 (:nivel-final g)))
    (is (= 1 (:subidas g)))
    (is (= 7 (:xp-desde-nivel reg)))
    (is (= 30 (growth/xp-necessario 11)))
    (is (= (+ (growth/total-xp-for-level-simple 11) 7) (:xp-total reg)))))

(deftest grant-multi-level-and-cap-100
  (let [r {:species "pikachu" :nivel 98 :xp-desde-nivel 0
           :xp-progress-version "growth-v2" :growth-rate :simple
           :xp-total (growth/total-xp-for-level-simple 98)
           :hp 50 :hp-atual 50 :ataque 40 :defesa 30
           :atq-esp 40 :def-esp 40 :veloc 50 :amizade 70}
        need (+ (growth/level-span-simple 98) (growth/level-span-simple 99) 50)
        g (xp/aplicar-xp-pokemon r need)
        reg (:registro g)]
    (is (= 100 (:nivel-final g)))
    (is (= 2 (:subidas g)))
    (is (zero? (:xp-desde-nivel reg)))
    (is (= (growth/total-xp-for-level-simple 100) (:xp-total reg)))
    (let [again (xp/aplicar-xp-pokemon reg 999)]
      (is (= 100 (:nivel-final again)))
      (is (zero? (:subidas again)))
      (is (zero? (:xp-oficial-aplicado again))))))

(deftest invalid-negative-xp-ignored
  (let [r {:species "pikachu" :nivel 5 :xp-desde-nivel 3
           :xp-progress-version "growth-v2" :growth-rate :simple
           :xp-total (+ (growth/total-xp-for-level-simple 5) 3)
           :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1}
        g (xp/aplicar-xp-pokemon r -50)]
    (is (= 5 (:nivel-final g)))
    (is (= 3 (get-in g [:registro :xp-desde-nivel])))
    (is (zero? (:xp-oficial-aplicado g)))))

(deftest unknown-xp-stays-unknown-on-read
  (let [p (growth/pokemon-progress {:species "eevee" :nivel 12
                                    :hp 40 :hp-atual 40})]
    (is (false? (:conhecido? p)))
    (is (nil? (:xp-nivel-atual p)))
    (is (nil? (:xp-proximo-nivel p)))
    (is (= 12 (:nivel p)))))

(defn- clamp-frac [f]
  (cond (or (Double/isNaN (double f)) (neg? (double f))) 0.0
        (>= (double f) 1.0) 0.999
        :else (double f)))

(deftest convert-v1-preserves-level-and-proportion
  (doseq [[frac label] [[0.0 "0%"] [0.25 "25%"] [0.5 "50%"] [0.75 "75%"] [0.99 "99%"]]]
    (testing label
      (let [nivel 30
            rate :medium-fast
            span-v1 (growth/level-span rate nivel)
            residual-v1 (long (Math/floor (* frac span-v1)))
            ;; Fração efetiva após floor no residual v1 (fonte da conversão).
            frac-efetiva (if (zero? span-v1) 0.0 (/ (double residual-v1) (double span-v1)))
            v1 {:species "pikachu" :nivel nivel
                :xp-progress-version "growth-v1" :growth-rate rate
                :xp-total (+ (growth/total-xp-for-level rate nivel) residual-v1)
                :xp-desde-nivel residual-v1 :xp residual-v1
                :hp 50 :hp-atual 50 :ataque 40 :defesa 30
                :atq-esp 40 :def-esp 40 :veloc 50}
            v2 (growth/convert-to-growth-v2 v1)
            again (growth/convert-to-growth-v2 v2)
            span-v2 (growth/level-span-simple nivel)
            expected (long (Math/floor (* (clamp-frac frac-efetiva) (double span-v2))))]
        (is (= nivel (:nivel v2)) "nível preservado")
        (is (= "growth-v2" (:xp-progress-version v2)))
        (is (= (:xp-total v2) (:xp-total again)) "idempotente")
        (is (= (:xp-desde-nivel v2) (:xp-desde-nivel again)))
        (is (= expected (:xp-desde-nivel v2)))
        (is (< (:xp-desde-nivel v2) span-v2))))))

(deftest convert-v1-50pct-level-30-example
  (let [nivel 30
        rate :medium-fast
        span-v1 (growth/level-span rate nivel)
        residual-v1 (quot span-v1 2)
        v1 {:species "pikachu" :nivel nivel
            :xp-progress-version "growth-v1" :growth-rate rate
            :xp-total (+ (growth/total-xp-for-level rate nivel) residual-v1)
            :xp-desde-nivel residual-v1
            :hp 50 :hp-atual 50 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1}
        v2 (growth/convert-to-growth-v2 v1)
        span-v2 (growth/level-span-simple 30)]
    (is (= 30 (:nivel v2)))
    (is (= 68 span-v2))
    (is (= (long (Math/floor (* (/ (double residual-v1) (double span-v1)) 68)))
           (:xp-desde-nivel v2)))))

(deftest convert-does-not-level-up
  (let [v1 {:species "pikachu" :nivel 20
            :xp-progress-version "growth-v1" :growth-rate :medium-fast
            :xp-total (dec (growth/total-xp-for-level :medium-fast 21))
            :xp-desde-nivel (dec (growth/level-span :medium-fast 20))
            :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1}
        v2 (growth/convert-to-growth-v2 v1)]
    (is (= 20 (:nivel v2)))
    (is (< (:xp-desde-nivel v2) (growth/level-span-simple 20)))))

(deftest retry-grant-idempotent-after-convert
  (let [v1 {:species "eevee" :nivel 7
            :xp-progress-version "growth-v1" :growth-rate :medium-fast
            :xp-total (growth/total-xp-for-level :medium-fast 7)
            :xp-desde-nivel 0
            :hp 40 :hp-atual 40 :ataque 20 :defesa 20
            :atq-esp 20 :def-esp 20 :veloc 30 :amizade 70}
        a (xp/aplicar-xp-pokemon v1 0)
        b (xp/aplicar-xp-pokemon (:registro a) 0)]
    (is (= 7 (:nivel-final a) (:nivel-final b)))
    (is (= (get-in a [:registro :xp-total]) (get-in b [:registro :xp-total])))
    (is (= "growth-v2" (get-in a [:registro :xp-progress-version])))))

(deftest datomic-convert-and-grant-roundtrip
  (let [client (persistence/client {:system (str "gv2-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/gv2-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "gv2")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "c" :player-id "ash"})
    (let [nivel 10
          rate :medium-fast
          span (growth/level-span rate nivel)
          residual (quot span 2)]
      (collection/adicionar-pokemon!
       s "c" "ash"
       {:id-pokemon "p-v1" :species "pikachu" :nome "Pikachu"
        :nivel nivel :xp-desde-nivel residual
        :xp-total (+ (growth/total-xp-for-level rate nivel) residual)
        :xp-progress-version "growth-v1" :growth-rate rate
        :hp 35 :hp-atual 35 :ataque 55 :defesa 40
        :atq-esp 50 :def-esp 50 :veloc 90 :tipos ["electric"] :amizade 70
        :golpes [{:slug "thunder-shock" :nome-exibicao "Thunder Shock" :tipo "electric"
                  :poder 40 :classe :especial}]}
       20 nil)
      (collection/ganhar-xp-pokemon! s "c" "ash" 0 0)
      (let [poke (first (ports/list-collection s "c" "ash" "a"))]
        (is (= 10 (:nivel poke)) "conversão não sobe nível")
        (is (= "growth-v2" (:xp-progress-version poke)))
        (is (= :simple (:growth-rate poke)))
        (let [before (:xp-desde-nivel poke)]
          (collection/ganhar-xp-pokemon! s "c" "ash" 0 0)
          (let [again (first (ports/list-collection s "c" "ash" "a"))]
            (is (= before (:xp-desde-nivel again)) "retry não altera residual")
            (is (= 10 (:nivel again)))))))))
