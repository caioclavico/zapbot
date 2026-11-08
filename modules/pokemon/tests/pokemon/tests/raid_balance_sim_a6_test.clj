(ns pokemon.tests.raid-balance-sim-a6-test
  "Simulação before/after Proposta A (HP 320 + var 85–100%).
  Não altera produção — só métricas locais determinísticas."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.raid :as raid]))

(def ^:private golpe-neutro
  {:poder 80 :classe :fisico :tipo "normal"})

(def ^:private golpe-se
  {:poder 80 :classe :fisico :tipo "water"})

(def ^:private mon
  {:ataque 80 :atq-esp 80 :defesa 40 :def-esp 40 :hp 100
   :tipos ["water"] :golpes [golpe-se]})

(defn- dano-com
  [hp-por-jogador variacao-pct golpe]
  (let [raw (long (Math/floor (+ 15 (* 0.6 (:poder golpe)
                                       (/ (double (:ataque mon)) 50.0)))))
        base (max 10 (min 160 raw))
        base-v (max 1 (long (Math/round (* (double base) (/ variacao-pct 100.0)))))
        mult (if (= "water" (:tipo golpe)) 2.0 1.0)
        com-tipo (long (Math/round (* (double base-v) mult)))
        dano (if (pos? com-tipo) com-tipo 1)]
    {:hp-chefe hp-por-jogador
     :base base
     :variacao-pct variacao-pct
     :base-variada base-v
     :multiplicador mult
     :dano dano
     :hits-para-ko (long (Math/ceil (/ (double hp-por-jogador) dano)))
     :fracao-por-hit (/ (double dano) hp-por-jogador)}))

(defn- simular-raid
  "Simula solo ou duo até KO do chefe ou HP da party esgotar.
  Revide = ceil(hp-mon/5) por hit sem vitória."
  [{:keys [n-jogadores hp-por-jogador variacao-pct golpe party-hp]
    :or {party-hp 100}}]
  (let [hp-chefe (long (* hp-por-jogador n-jogadores))
        revide (max 1 (long (Math/ceil (/ (double party-hp) 5))))
        d (:dano (dano-com hp-por-jogador variacao-pct golpe))]
    (loop [hp hp-chefe
           party (* party-hp n-jogadores)
           turns 0
           damage 0]
      (cond
        (or (zero? hp) (neg? hp))
        {:venceu? true :derrota? false :turns turns :dano-total damage
         :hp-chefe-inicial (* hp-por-jogador n-jogadores)
         :dano-medio-turno (if (pos? turns) (/ (double damage) turns) 0.0)
         :n-jogadores n-jogadores :variacao-pct variacao-pct}

        (or (zero? party) (neg? party))
        {:venceu? false :derrota? true :turns turns :dano-total damage
         :hp-chefe-inicial (* hp-por-jogador n-jogadores)
         :hp-chefe-restante hp
         :dano-medio-turno (if (pos? turns) (/ (double damage) turns) 0.0)
         :n-jogadores n-jogadores :variacao-pct variacao-pct}

        :else
        (let [hit (min hp d)
              hp' (- hp hit)
              win? (zero? hp')
              party' (if win? party (max 0 (- party revide)))]
          (recur hp' party' (inc turns) (+ damage hit)))))))

(deftest proposta-a-parametros-efetivos
  (is (= 320 raid/hp-por-jogador))
  (is (= 85 raid/dano-variacao-min-pct))
  (is (= 16 raid/dano-variacao-span))
  (let [lo (raid/calcular-dano golpe-neutro mon
                               {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["normal"]}}
                               {:raid-damage-var 0})
        hi (raid/calcular-dano golpe-neutro mon
                               {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["normal"]}}
                               {:raid-damage-var 15})]
    (is (= 85 (:variacao-pct lo)))
    (is (= 100 (:variacao-pct hi)))
    (is (< (:dano lo) (:dano hi)))
    (is (= (:base lo) (:base hi)))))

(deftest sim-before-after-solo-duo
  (testing "antes (220, 100% implícito) vs depois (320, 85–100%)"
    (let [;; Neutro mede duração; SE mede que continua vencível.
          antes-n (simular-raid {:n-jogadores 1 :hp-por-jogador 220
                                 :variacao-pct 100 :golpe golpe-neutro})
          depois-n100 (simular-raid {:n-jogadores 1 :hp-por-jogador 320
                                     :variacao-pct 100 :golpe golpe-neutro})
          depois-n85 (simular-raid {:n-jogadores 1 :hp-por-jogador 320
                                    :variacao-pct 85 :golpe golpe-neutro})
          depois-se100 (simular-raid {:n-jogadores 1 :hp-por-jogador 320
                                      :variacao-pct 100 :golpe golpe-se})
          depois-se85 (simular-raid {:n-jogadores 1 :hp-por-jogador 320
                                     :variacao-pct 85 :golpe golpe-se})
          antes-duo (simular-raid {:n-jogadores 2 :hp-por-jogador 220
                                   :variacao-pct 100 :golpe golpe-se
                                   :party-hp 100})
          depois-duo (simular-raid {:n-jogadores 2 :hp-por-jogador 320
                                    :variacao-pct 92 :golpe golpe-se
                                    :party-hp 100})]
      (is (true? (:venceu? antes-n)))
      (is (true? (:venceu? depois-n100)))
      (is (true? (:venceu? depois-n85)))
      (is (true? (:venceu? depois-se100)))
      (is (true? (:venceu? depois-se85)))
      ;; HP +45% alonga a raid neutra; variação 85% alonga ainda mais.
      (is (< (:turns antes-n) (:turns depois-n100)))
      (is (< (:turns depois-n100) (:turns depois-n85)))
      (is (true? (:venceu? antes-duo)))
      (is (true? (:venceu? depois-duo)))
      (is (< (:dano-medio-turno depois-n85)
             (* 0.95 (:dano-medio-turno depois-n100))))
      ;; SE solo a 85% ainda fecha rápido (< 8 turns).
      (is (< (:turns depois-se85) 8)))))

(deftest sim-neutro-ainda-vencivel-com-party-maior
  (testing "golpe neutro + party 3×100 HP: solo 320 ainda vence"
    (let [r (simular-raid {:n-jogadores 1 :hp-por-jogador 320
                           :variacao-pct 85 :golpe golpe-neutro
                           :party-hp 300})]
      (is (true? (:venceu? r)))
      (is (false? (:derrota? r)))
      (is (< (:turns r) 25)))))
