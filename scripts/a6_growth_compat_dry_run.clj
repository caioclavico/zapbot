#!/usr/bin/env bb
;; Dry-run lógico de conversão growth-v1 — sem escrita em produção.
;; Uso: clojure -M:test-a6-pokemon-growth -e "(load-file \"scripts/a6_growth_compat_dry_run.clj\")"
;; ou:  clojure -i scripts/a6_growth_compat_dry_run.clj  (com classpath do projeto)
(ns a6.growth-compat-dry-run
  (:require [pokemon.domain.growth :as growth]))

(defn- sample-baseline
  "Amostra sintética espelhando a diversidade do baseline (~759), sem PII."
  []
  (vec
   (concat
    (for [i (range 200)]
      {:id (str "mf-" i) :species "pikachu" :nivel (inc (mod i 50))
       :xp-desde-nivel (mod i 9)})
    (for [i (range 150)]
      {:id (str "ms-" i) :species "bulbasaur" :nivel (inc (mod i 40))
       :xp-desde-nivel (mod (* i 3) 10)})
    (for [i (range 120)]
      {:id (str "sl-" i) :species "magikarp" :nivel (inc (mod i 30))
       :vitorias-desde-nivel (mod i 4)})
    (for [i (range 100)]
      {:id (str "fa-" i) :species "clefairy" :nivel (inc (mod i 25))
       :xp  (mod i 8)})
    (for [i (range 80)]
      {:id (str "er-" i) :species "nincada" :nivel (inc (mod i 20))
       :xp-desde-nivel (mod i 12)})
    (for [i (range 70)]
      {:id (str "fl-" i) :species "wailmer" :nivel (inc (mod i 35))
       :xp-desde-nivel (mod i 9)})
    (for [i (range 39)]
      {:id (str "max-" i) :species "dragonite" :nivel 100 :xp-desde-nivel 0}))))

(defn analyze
  [regs]
  (let [converted (mapv (fn [r]
                          (let [c (growth/convert-legacy-residual r)]
                            {:antes r :depois c}))
                        regs)
        lowered (filter (fn [{:keys [antes depois]}]
                          (< (long (:nivel depois))
                             (long (or (:nivel antes) (:level antes) 1))))
                        converted)
        unresolved (filter (fn [{:keys [depois]}]
                             (nil? (:growth-rate depois)))
                           converted)
        incomplete (filter (fn [{:keys [antes]}]
                             (and (nil? (:xp-desde-nivel antes))
                                  (nil? (:xp antes))
                                  (nil? (:vitorias-desde-nivel antes))))
                           converted)
        bumped (filter (fn [{:keys [antes depois]}]
                         (> (long (:nivel depois))
                            (long (or (:nivel antes) 1))))
                       converted)]
    {:quantidade (count regs)
     :niveis-reduzidos (count lowered)
     :sem-curva (count unresolved)
     :sem-progresso-conhecido (count incomplete)
     :subiram-por-residual>=9 (count bumped)
     :fingerprint {:sum-nivel-antes (reduce + 0 (map #(long (or (:nivel (:antes %)) 1)) converted))
                   :sum-nivel-depois (reduce + 0 (map #(long (:nivel (:depois %))) converted))
                   :sum-xp-total (reduce + 0 (map #(long (:xp-total (:depois %) 0)) converted))}}))

(defn -main [& _]
  (let [regs (sample-baseline)
        report (analyze regs)]
    (println "growth-compat-dry-run")
    (prn report)
    (assert (zero? (:niveis-reduzidos report)) "nenhum nível pode cair")
    (assert (= (count regs) (:quantidade report)))
    (println "OK")))

(-main)
