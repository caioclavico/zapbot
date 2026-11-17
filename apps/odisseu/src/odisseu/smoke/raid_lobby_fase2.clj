(ns odisseu.smoke.raid-lobby-fase2
  "Runner Fase 2 — lobby cooperativo de raides."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-lobby-fase2-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-lobby-fase2-test)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== raid lobby fase 2 summary ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
