(ns odisseu.smoke.m2-trainer
  "Runner M2: executa testes do núcleo treinador + reporta memória JVM."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :as test]
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
        summary (test/run-tests 'pokemon.tests.trainer-saves-test)
        mem-after (assoc (heap-stats) :rss-mb (rss-mb) :at (str (Instant/now)))
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== M2 trainer/saves summary ===")
    (pprint/pprint {:ok ok?
                    :summary summary
                    :memory {:before mem-before :after mem-after}
                    :java (System/getProperty "java.version")
                    :nota "JVM isolada; sem Node/Chromium/Cassandra."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
