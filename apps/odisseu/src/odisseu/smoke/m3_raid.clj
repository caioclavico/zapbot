(ns odisseu.smoke.m3-raid
  "Runner M3.6: ginásio concorrente + raids + regressões M2–M3.5."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :as test]
            [pokemon.tests.raid-m36-test]
            [pokemon.tests.gym-m36-test]
            [pokemon.tests.gym-m35-test]
            [pokemon.tests.battle-m34-test]
            [pokemon.tests.capture-m33-test]
            [pokemon.tests.reward-m32-test]
            [pokemon.tests.collection-m31-test]
            [pokemon.tests.xp-equivalence-test]
            [pokemon.tests.trainer-saves-test])
  (:import [java.lang.management ManagementFactory]
           [java.time Instant]))

(defn- heap-stats
  []
  (let [rt (Runtime/getRuntime)
        mb (fn [n] (long (/ n (* 1024 1024))))]
    {:heap-used-mb (mb (- (.totalMemory rt) (.freeMemory rt)))
     :heap-total-mb (mb (.totalMemory rt))
     :heap-max-mb (mb (.maxMemory rt))}))

(defn- rss-mb
  []
  (try
    (let [pid (first (str/split (.getName (ManagementFactory/getRuntimeMXBean)) #"@"))
          proc (.. (ProcessBuilder. ["ps" "-o" "rss=" "-p" pid])
                   (redirectErrorStream true)
                   start)
          out (str/trim (slurp (.getInputStream proc)))]
      (.waitFor proc)
      (when (re-matches #"\d+" out)
        (long (/ (Long/parseLong out) 1024))))
    (catch Exception _ nil)))

(defn -main
  [& _]
  (let [mem-before (assoc (heap-stats) :rss-mb (rss-mb) :at (str (Instant/now)))
        summaries [(test/run-tests 'pokemon.tests.raid-m36-test)
                   (test/run-tests 'pokemon.tests.gym-m36-test)
                   (test/run-tests 'pokemon.tests.gym-m35-test)
                   (test/run-tests 'pokemon.tests.battle-m34-test)
                   (test/run-tests 'pokemon.tests.capture-m33-test)
                   (test/run-tests 'pokemon.tests.reward-m32-test)
                   (test/run-tests 'pokemon.tests.xp-equivalence-test)
                   (test/run-tests 'pokemon.tests.collection-m31-test)
                   (test/run-tests 'pokemon.tests.trainer-saves-test)]
        ok? (every? #(and (zero? (:fail %)) (zero? (:error %))) summaries)
        mem-after (assoc (heap-stats) :rss-mb (rss-mb) :at (str (Instant/now)))]
    (println "=== M3.6 raid+gym concurrency summary ===")
    (pprint/pprint {:ok ok? :summaries summaries
                    :memory {:before mem-before :after mem-after}
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
