(ns odisseu.smoke.m3-collection
  "Runner M3.1: equivalência XP + coleção/HP/economia (Datomic Local)."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :as test]
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
        s1 (test/run-tests 'pokemon.tests.xp-equivalence-test)
        s2 (test/run-tests 'pokemon.tests.collection-m31-test)
        s3 (test/run-tests 'pokemon.tests.trainer-saves-test)
        summaries [s1 s2 s3]
        ok? (every? #(and (zero? (:fail %)) (zero? (:error %))) summaries)
        mem-after (assoc (heap-stats) :rss-mb (rss-mb) :at (str (Instant/now)))]
    (println "=== M3.1 collection/XP summary ===")
    (pprint/pprint {:ok ok?
                    :summaries summaries
                    :memory {:before mem-before :after mem-after}
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
