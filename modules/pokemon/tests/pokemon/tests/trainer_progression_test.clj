(ns pokemon.tests.trainer-progression-test
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.mission-rewards :as mission-rewards]
            [pokemon.domain.shop :as shop]
            [pokemon.domain.trainer-rewards :as rewards]
            [pokemon.domain.xp :as xp]))

(deftest curva-progressiva-e-carry-over
  (is (= [10 15 20 25 30 35 40 45 50 55]
         (mapv xp/trainer-xp-necessario (range 1 11))))
  (is (= {:nivel 4 :xp-atual 5 :xp-necessario 25 :subidas [2 3 4]}
         (xp/avancar-nivel-treinador 1 0 50)))
  (is (= {:nivel 2 :xp-atual 0 :xp-necessario 15 :subidas [2]}
         (xp/avancar-nivel-treinador 1 0 10))))

(deftest perfil-legado-preserva-nivel-e-residual
  (testing "legado sem residual migra sem baixar nível e sem perder PE parcial"
    (let [perfil (xp/perfil-treinador {:trainer-xp 11 :trainer-level 2})]
      (is (= 2 (:nivel perfil)))
      (is (= 6 (:xp-atual perfil)))
      (is (= 15 (:xp-necessario perfil)))))
  (testing "residual já persistido é autoritativo"
    (let [perfil (xp/perfil-treinador {:trainer-xp 90 :trainer-level 5
                                       :trainer-xp-level-progress 3})]
      (is (= 5 (:nivel perfil)))
      (is (= 3 (:xp-atual perfil)))
      (is (= 30 (:xp-necessario perfil))))))

(deftest pacotes-usam-catalogo-e-sao-reproduziveis
  (doseq [level (range 1 51)]
    (let [a (rewards/recompensas-por-nivel "op-stable" level)
          b (rewards/recompensas-por-nivel "op-stable" level)]
      (is (= a b))
      (is (<= 2 (count (:normal a)) 3))
      (is (every? shop/item (keys (:items a))))
      (is (= (= 50 level) (contains? (:especial a) "fruta-dourada"))
          "fruta dourada comemorativa apenas no nível 50")
      (is (= (zero? (mod level 10)) (boolean (seq (:especial a)))))
      (is (= (zero? (mod level 5)) (some? (:marco-espaco a)))))))

(deftest especial-e-comemorativo-nao-perdem-pacote-normal
  (is (= {"ultra-bola" 1 "pocao-maxima" 1}
         (:especial (rewards/recompensas-por-nivel "x" 10))))
  (let [{:keys [normal especial items]} (rewards/recompensas-por-nivel "x" 50)]
    (is (seq normal))
    (is (= {"fruta-dourada" 1 "ultra-bola" 3 "pocao-maxima" 2 "reviver" 1}
           especial))
    (is (= (merge-with + normal especial) items))))

(deftest marcos-usam-expansoes-existentes-sem-contador-paralelo
  (is (= 100 (capacity/capacidade-pokemon 5 0 0)))
  (is (= 100 (capacity/capacidade-mochila 10 0 50)))
  (is (= 150 (capacity/capacidade-pokemon 15 0 0)))
  (is (= 300 (capacity/capacidade-mochila 50 0 50)))
  (testing "expansões legadas continuam somadas à capacidade por nível"
    (is (= 250 (capacity/capacidade-pokemon 15 2 0)))
    (is (= 200 (capacity/capacidade-mochila 20 1 50)))))

(deftest alocacao-de-missoes-preserva-ordem-e-a-progressao-tem-ordem-local
  (is (not-any? #{"pocao" "pocao-maxima"} mission-rewards/order)
      "o allocator compartilhado de missões mantém a ordem histórica")
  (is (= {"pocao" 1}
         (:added (mission-rewards/allocate {"pocao" 1} {} 1
                                            (into mission-rewards/order
                                                  ["pocao" "pocao-maxima"]))))
      "somente a alocação dos prêmios de nível acrescenta as poções"))

(deftest cartao-presente-e-item-canonico-raro-e-limitado
  (is (shop/item "cartao-presente"))
  (doseq [level (range 1 51)
          op (range 20)]
    (is (<= 0 (get (rewards/pacote-normal (str "card-limit-" op) level)
                   "cartao-presente" 0) 1))))

(deftest simulacao-ponderada-10k-sem-moedas
  (let [draws (for [n (range 10000)
                    :let [level (inc (mod n 50))
                          package (rewards/pacote-normal (str "simulation-" n) level)]]
                package)
        frequencies (frequencies (mapcat keys draws))
        card-count (get frequencies "cartao-presente" 0)]
    (is (= 100.0 (reduce + (map :weight (rewards/pesos-no-nivel 1))))
        "pesos do nível 1 somam 100")
    (is (= 100.0 (reduce + (map :weight (rewards/pesos-no-nivel 50))))
        "pesos do nível 50 somam 100")
    (is (<= 80 card-count 350) "cartão permanece prêmio raro na amostra")
    (is (not (contains? frequencies "moedas")))))
