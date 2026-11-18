(ns odisseu.smoke.raid-auto-reminder-fase1271
  "Runner Fase 12.7.1 — lembretes + lobby dinâmico + iniciar antecipado."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-auto-reminder-fase1271-test]
            [pokemon.tests.raid-auto-lobby-iniciar-fase1271-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [s1 (test/run-tests 'pokemon.tests.raid-auto-reminder-fase1271-test)
        s2 (test/run-tests 'pokemon.tests.raid-auto-lobby-iniciar-fase1271-test)
        summary {:test (+ (:test s1) (:test s2))
                 :pass (+ (:pass s1) (:pass s2))
                 :fail (+ (:fail s1) (:fail s2))
                 :error (+ (:error s1) (:error s2))}
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== raid auto 12.7.1 (reminder + lobby/iniciar) summary ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
