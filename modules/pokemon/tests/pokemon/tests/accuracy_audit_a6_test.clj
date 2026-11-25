(ns pokemon.tests.accuracy-audit-a6-test
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.damage :as damage]
            [pokemon.domain.move-effects :as effects]
            [pokemon.domain.rng :as rng]))

(def ^:private tentativas 100000)
(def ^:private atacante {:nome "A" :hp 100 :ataque 80 :atq-esp 80})
(def ^:private defensor {:nome "B" :tipos ["normal"] :defesa 80 :def-esp 80 :veloc 40})
(def ^:private ataque
  {:golpe {:tipo "normal" :poder 40 :classe :fisico}
   :atacante atacante :defensor defensor :hp-atacante-atual 100
   :rng {:crit 99 :damage-var 0}})

(defn- contar-acertos [args]
  (loop [n 0 acertos 0]
    (if (= n tentativas)
      acertos
      (recur (inc n) (if (:errou? (damage/resolver-ataque args))
                       acertos (inc acertos))))))

(defn- limite [p]
  (if (or (zero? p) (= p 1.0))
    0.0
    (* 6.0 (Math/sqrt (* tentativas p (- 1.0 p))))))

(deftest simulacoes-de-precisao-com-rng-real
  (let [base (for [p [100 95 90 85 75 50]]
               [(str "base-" p) (/ p 100.0)
                (assoc-in ataque [:golpe :precisao] p)])
        trovao (effects/enriquecer {:slug "thunder" :tipo "electric"
                                    :poder 110 :classe :especial})
        nevasca (effects/enriquecer {:slug "blizzard" :tipo "ice"
                                     :poder 110 :classe :especial})
        cenarios (concat
                  base
                  [["precisao-mais" 1.0 (-> ataque (assoc-in [:golpe :precisao] 75)
                                             (assoc :estagios-atacante {:precisao 1}))]
                   ["precisao-menos" 0.5 (-> ataque (assoc-in [:golpe :precisao] 75)
                                              (assoc :estagios-atacante {:precisao -1}))]
                   ["evasao-mais" 0.63 (-> ataque (assoc-in [:golpe :precisao] 95)
                                            (assoc :estagios-defensor {:evasao 1}))]
                   ["evasao-menos" 1.0 (-> ataque (assoc-in [:golpe :precisao] 95)
                                             (assoc :estagios-defensor {:evasao -1}))]
                   ["chuva" 1.0 (assoc ataque :golpe trovao :clima :chuva)]
                   ["chuva-evasao" 1.0 (assoc ataque :golpe trovao :clima :chuva
                                              :estagios-defensor {:evasao 6})]
                   ["neve-evasao" 1.0 (assoc ataque :golpe nevasca :clima :neve
                                             :estagios-defensor {:evasao 6})]
                   ["sol" 0.5 (assoc ataque :golpe trovao :clima :sol)]
                   ["ignora" 1.0 (-> ataque (assoc-in [:golpe :precisao] 50)
                                        (assoc-in [:golpe :ignora-precisao] true)
                                        (assoc :estagios-defensor {:evasao 6}))]
                   ["sem-precisao" 1.0 ataque]])]
    (doseq [[nome p args] cenarios]
      (let [acertos (contar-acertos args)
            esperado (* tentativas p)]
        (println (format "AUDITORIA %s %d/%d %.3f%%" nome acertos tentativas
                         (* 100.0 (/ acertos tentativas))))
        (is (<= (Math/abs (- acertos esperado)) (limite p)) nome)))))

(deftest rng-distribuicao-e-independencia
  (let [n 200000
        bins (long-array 100)
        resultado (loop [i 0 anterior nil iguais 0 trincas 0 ultimos 0]
                    (if (= i n)
                      {:iguais iguais :trincas trincas}
                      (let [v (rng/roll nil :accuracy 100)
                            metade (< v 50)
                            falhas (if (>= v 95) (inc ultimos) 0)]
                        (aset-long bins v (inc (aget bins v)))
                        (recur (inc i) metade
                               (if (and (some? anterior) (= anterior metade))
                                 (inc iguais) iguais)
                               (if (>= falhas 3) (inc trincas) trincas)
                               falhas))))
        desvio (* 6.0 (Math/sqrt (* n 0.01 0.99)))]
    (is (every? #(<= (Math/abs (- % (/ n 100))) desvio) bins))
    (is (<= (Math/abs (- (:iguais resultado) (/ (dec n) 2.0)))
            (* 6.0 (Math/sqrt (/ (dec n) 4.0)))))
    (is (pos? (:trincas resultado)))
    (println (format "AUDITORIA rng n=%d pares-mesma-metade=%d sequencias-3-ou-mais=%d"
                     n (:iguais resultado) (:trincas resultado)))))

(deftest defesa-adiciona-esquiva-independente
  (let [args (-> ataque
                 (assoc-in [:golpe :precisao] 95)
                 (assoc :defendendo? true))
        acertos (loop [n 0 acertos 0]
                  (if (= n tentativas)
                    acertos
                    (recur (inc n) (if (:acertou? (damage/resolver-ataque args))
                                     (inc acertos) acertos))))
        p (* 0.95 0.8)]
    (println (format "AUDITORIA defesa-95 %d/%d %.3f%%" acertos tentativas
                     (* 100.0 (/ acertos tentativas))))
    (is (<= (Math/abs (- acertos (* tentativas p))) (limite p)))))

(deftest limites-injetados-e-ausencia-de-rolagem-duplicada
  (doseq [p [100 95 90 85 75 50]]
    (let [args (assoc-in ataque [:golpe :precisao] p)]
      (is (not (:errou? (damage/resolver-ataque
                         (assoc-in args [:rng :accuracy] (dec p)))))
          (str p "% último acerto"))
      (when (< p 100)
        (is (:errou? (damage/resolver-ataque
                      (assoc-in args [:rng :accuracy] p)))
            (str p "% primeiro erro")))))
  (let [original rng/roll
        chamadas (atom [])
        args (assoc-in ataque [:golpe :precisao] 95)]
    (with-redefs [rng/roll (fn [r k n] (swap! chamadas conj k) (original r k n))]
      (damage/resolver-ataque args))
    (is (= 1 (count (filter #{:accuracy} @chamadas)))))
  (let [args (assoc-in ataque [:golpe :precisao] 95)]
    (is (every? :errou? (repeatedly 4 #(damage/resolver-ataque
                                         (assoc-in args [:rng :accuracy] 99)))))))
