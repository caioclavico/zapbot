(ns pokemon.tests.hunt-streak-sim-a6-test
  "Simulação quantitativa (≥10k encontros) da tabela streak-unit-v1.
  Não altera RNG de captura/cooldown — só aplica a política de recompensas."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.capture :as capture]))

(def ^:private battle-xp-comum
  (:xp (capture/recompensa-batalha-caca {:raridade "comum"})))

(def ^:private battle-pe
  (:pe (capture/recompensa-batalha-caca {:raridade "comum"})))

(defn- capture-rew [seq-after]
  (capture/recompensa-pos-captura {:capturou? true :sequencia-nova seq-after}))

(defn- simulate
  "Simula `n` encontros com política:
  - :always-release — sempre vence e libera
  - :always-capture — sempre captura (chance 100%)
  - :alternate — captura, libera, captura…
  - :capture-p — captura com probabilidade p (senão libera)
  Retorna acumulados."
  [{:keys [n mode p]}]
  (let [p (double (or p 1.0))]
    (loop [i 0
           seq-cur 0
           xp 0 pe 0 coins 0
           caps 0 releases 0 balls 0]
      (if (>= i n)
        {:encontros n :xp xp :pe pe :coins coins
         :capturas caps :liberacoes releases :bolas balls
         :seq-final seq-cur
         :xp-medio (/ (double xp) n)
         :pe-medio (/ (double pe) n)
         :coins-medio (/ (double coins) n)}
        (let [capturar? (case mode
                          :always-release false
                          :always-capture true
                          :alternate (even? i)
                          :capture-p (< (rand) p)
                          false)]
          (if capturar?
            (let [seq' (inc seq-cur)
                  rew (capture-rew seq')]
              (recur (inc i) seq'
                     (+ xp battle-xp-comum (long (:xp rew)))
                     (+ pe battle-pe (long (:pe rew)))
                     (+ coins (long (:moedas rew)))
                     (inc caps) releases (inc balls)))
            (recur (inc i) 0
                   (+ xp battle-xp-comum)
                   (+ pe battle-pe)
                   coins
                   caps (inc releases) balls)))))))

(deftest sim-10000-estilos-de-jogo
  (let [n 10000
        liberador (simulate {:n n :mode :always-release})
        capturador (simulate {:n n :mode :always-capture})
        alternado (simulate {:n n :mode :alternate})
        baixa-bola (simulate {:n n :mode :capture-p :p 0.25})
        alta-bola (simulate {:n n :mode :capture-p :p 0.85})]
    (println "HUNT_STREAK_SIM"
             {:liberador (select-keys liberador [:xp :pe :coins :capturas :xp-medio :coins-medio])
              :capturador (select-keys capturador [:xp :pe :coins :capturas :xp-medio :coins-medio])
              :alternado (select-keys alternado [:xp :pe :coins :capturas :xp-medio :coins-medio])
              :p25 (select-keys baixa-bola [:xp :pe :coins :capturas :xp-medio :coins-medio])
              :p85 (select-keys alta-bola [:xp :pe :coins :capturas :xp-medio :coins-medio])})
    (testing "liberar só ganha batalha"
      (is (= 0 (:capturas liberador)))
      (is (= (* n battle-xp-comum) (:xp liberador)))
      (is (= (* n battle-pe) (:pe liberador)))
      (is (zero? (:coins liberador))))
    (testing "capturar contínuo supera liberar em XP/PE e ganha moedas no teto"
      (is (> (:xp capturador) (:xp liberador)))
      (is (> (:pe capturador) (:pe liberador)))
      (is (pos? (:coins capturador)))
      ;; Após seq 30, +10 moedas/captura; 10k encontros ⇒ moedas altas mas lineares.
      (is (< (:coins-medio capturador) 11.0) "teto 10 moedas/captura"))
    (testing "alternar quebra sequência — menos moedas que always-capture"
      (is (< (:coins alternado) (:coins capturador)))
      (is (< (:xp-medio alternado) (:xp-medio capturador))))))

(deftest sim-marcos-sequencia
  (doseq [[s xp pe moedas] [[1 2 1 0] [2 2 1 0] [3 3 2 0] [5 5 3 1]
                            [10 8 5 3] [20 12 7 6] [30 15 9 10] [50 15 9 10] [100 15 9 10]]]
    (is (= {:xp xp :pe pe :moedas moedas}
           (select-keys (capture/faixa-sequencia-captura s) [:xp :pe :moedas]))
        (str "marco seq=" s))))
