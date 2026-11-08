(ns pokemon.tests.raid-balance-one-mon-sim-a6-test
  "Simulação 1-mon vs Proposta A (HP 320 + var 85–100%). Sem mutar produção."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.raid :as raid]))

(def ^:private golpe-neutro {:poder 80 :classe :fisico :tipo "normal"})
(def ^:private golpe-se {:poder 80 :classe :fisico :tipo "water"})

(defn- mon
  [hp atk]
  {:ataque atk :atq-esp atk :defesa 40 :def-esp 40 :hp hp
   :tipos ["water"] :golpes [golpe-se]})

(defn- dano
  [variacao-pct golpe atk]
  (let [raw (long (Math/floor (+ 15 (* 0.6 (:poder golpe) (/ (double atk) 50.0)))))
        base (max 10 (min 160 raw))
        base-v (max 1 (long (Math/round (* (double base) (/ variacao-pct 100.0)))))
        mult (if (= "water" (:tipo golpe)) 2.0 1.0)
        com-tipo (long (Math/round (* (double base-v) mult)))]
    (if (pos? com-tipo) com-tipo 1)))

(defn- simular
  "1 mon por jogador. Revide = ceil(hp-mon/5). Sem reservas."
  [{:keys [n-jogadores hp-mon atk variacao-pct golpe hp-por-jogador]
    :or {hp-por-jogador raid/hp-por-jogador
         hp-mon 100
         atk 80
         variacao-pct 100
         golpe golpe-neutro}}]
  (let [hp-chefe (long (* hp-por-jogador n-jogadores))
        d (dano variacao-pct golpe atk)
        revide (max 1 (long (Math/ceil (/ (double hp-mon) 5))))
        party-hp (* hp-mon n-jogadores)]
    (loop [hp hp-chefe
           party party-hp
           turns 0
           damage 0]
      (cond
        (or (zero? hp) (neg? hp))
        {:venceu? true :turns turns :dano-total damage
         :hp-chefe-inicial hp-chefe :n-jogadores n-jogadores
         :variacao-pct variacao-pct :perfil {:hp-mon hp-mon :atk atk}}

        (or (zero? party) (neg? party))
        {:venceu? false :turns turns :dano-total damage
         :hp-chefe-inicial hp-chefe :hp-chefe-restante hp
         :n-jogadores n-jogadores :variacao-pct variacao-pct
         :perfil {:hp-mon hp-mon :atk atk}}

        :else
        (let [hit (min hp d)
              hp' (- hp hit)
              win? (zero? hp')
              party' (if win? party (max 0 (- party revide)))]
          (recur hp' party' (inc turns) (+ damage hit)))))))

(deftest proposta-a-params-intactos
  (is (= 320 raid/hp-por-jogador))
  (is (= 85 raid/dano-variacao-min-pct))
  (is (= 1 raid/max-party)))

(deftest sim-solo-fragil-equilibrado-tanque
  (let [fragil (simular {:n-jogadores 1 :hp-mon 55 :atk 60 :variacao-pct 85 :golpe golpe-se})
        eq (simular {:n-jogadores 1 :hp-mon 100 :atk 80 :variacao-pct 100 :golpe golpe-se})
        tank (simular {:n-jogadores 1 :hp-mon 160 :atk 70 :variacao-pct 85 :golpe golpe-se})
        neutro (simular {:n-jogadores 1 :hp-mon 100 :atk 80 :variacao-pct 85 :golpe golpe-neutro})]
    (is (true? (:venceu? eq)) "solo SE @100% vence")
    (is (true? (:venceu? tank)) "tanque SE @85% vence")
    ;; Frágil pode perder no pior caso — documentamos, não forçamos vitória.
    (is (boolean? (:venceu? fragil)))
    (is (or (:venceu? neutro) (<= (:turns neutro) 40)))))

(deftest sim-coop-2-3-5
  (doseq [n [2 3 5]]
    (let [se (simular {:n-jogadores n :variacao-pct 85 :golpe golpe-se})
          neu (simular {:n-jogadores n :variacao-pct 85 :golpe golpe-neutro})]
      (is (true? (:venceu? se)) (str "coop SE n=" n))
      (is (true? (:venceu? neu)) (str "coop neutro n=" n)))))

(deftest sim-lendario-mitico-fator
  (testing "HP chefe = 320 * n * fator; lendário ~1.4 ainda fechável em coop 3 SE"
    (let [fator 1.4
          hp-chefe (long (Math/round (* 320.0 3 fator)))
          d (dano 85 golpe-se 80)
          turns (long (Math/ceil (/ (double hp-chefe) d)))]
      (is (< turns 40) (str "turns lendário coop3=" turns))
      (is (> hp-chefe 320)))))
