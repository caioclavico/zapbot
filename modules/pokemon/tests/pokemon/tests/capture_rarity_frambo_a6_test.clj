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
    (is (= 95 c) "80 × 19/13 estoura o teto selvagem da Ultra")))

(deftest sequencia-nao-altera-chance
  (let [poke {:raridade "comum" :hp 40 :ataque 40 :defesa 40
              :atq-esp 40 :def-esp 40 :veloc 40}
        c0 (capture/chance-base-pos-batalha poke nil 0)
        c50 (capture/chance-base-pos-batalha poke nil 50)
        c100 (capture/chance-base-pos-batalha poke nil 100)]
    (is (= c0 c50 c100))
    (is (= "wild-catch-balance-v2" capture/politica-chance-captura-versao))))

(deftest sim-10k-streak-invariante-e-centros
  "10k tentativas: taxa ≈ chance publicada; streak 0 vs 100 idênticos."
  (let [poke {:raridade "comum" :hp 40 :ataque 40 :defesa 40
              :atq-esp 40 :def-esp 40 :veloc 40}
        base0 (capture/chance-base-pos-batalha poke nil 0)
        base100 (capture/chance-base-pos-batalha poke nil 100)
        chance (capture/chance-com-bola base0 "pokebola")
        n 10000
        sucessos
        (long
         (reduce
          (fn [acc roll]
            (if (:capturou?
                 (capture/attempt-capture
                  {:pokemon poke :bola "pokebola"
                   :chance-base base0 :roll (mod roll 100)
                   :fuga-roll 99 :tentativas-anteriores 0}))
              (inc acc)
              acc))
          0
          (range n)))
        taxa (* 100.0 (/ sucessos n))]
    (is (= base0 base100))
    (is (<= 55 chance 70))
    (is (< (Math/abs (- taxa (double chance))) 1.5)
        (str "taxa=" taxa " chance=" chance))
    (is (= 65 (get capture/raridade-chance-base "comum")))
    (is (= 48 (get capture/raridade-chance-base "incomum")))
    (is (= 90 capture/chance-captura-maxima))
    (is (= 95 capture/chance-captura-maxima-raid))))
