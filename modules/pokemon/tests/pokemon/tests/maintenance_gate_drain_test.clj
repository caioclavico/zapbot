(ns pokemon.tests.maintenance-gate-drain-test
  "Concurrency and freeze/drain unit tests for the JVM maintenance gate.
   No Datomic, WhatsApp, or production volumes."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pokemon.infrastructure.http.maintenance-gate :as gate])
  (:import [java.nio.file Files]
           [java.util.concurrent CyclicBarrier Executors TimeUnit]))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "a6-gate-drain-" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest write-admission-closed-during-drain
  (let [dir (tmp-dir)
        g (gate/create (str dir))]
    (is (true? (gate/acquire-write! g)))
    (let [pending (future (gate/enter! g 2000))]
      (loop [n 200]
        (when (and (pos? n) (= :open (:phase (gate/status g))))
          (Thread/sleep 5)
          (recur (dec n))))
      (is (= :draining (:phase (gate/status g))))
      (is (false? (gate/acquire-write! g)))
      (is (true? (gate/acquire-read! g)))
      (gate/release-read! g)
      (gate/release-write! g)
      (is (true? (:quiescent? @pending)))
      (is (= :quiescent (:phase (gate/status g))))
      (is (false? (gate/acquire-write! g)))
      (is (true? (gate/acquire-read! g)))
      (gate/release-read! g)
      (let [frozen (gate/freeze! g)]
        (is (= :frozen (:phase frozen)))
        (is (false? (gate/acquire-read! g)))
        (is (false? (gate/acquire-write! g))))
      (gate/resume! g)
      (is (= :open (:phase (gate/status g)))))))

(deftest scheduler-tick-paths-are-mutations
  (is (true? (gate/scheduler-tick-path? "/v1/raids/auto/tick")))
  (is (true? (gate/scheduler-tick-path? "/v1/thematic-events/tick")))
  (is (true? (gate/mutation-request? "POST" "/v1/raids/auto/tick")))
  (is (false? (gate/mutation-request? "GET" "/v1/trainer")))
  (is (true? (gate/mutation-request? "POST" "/v1/hunt/start")))
  (is (true? (gate/mutation-request? "GET" "/v1/unknown-future-route"))))

(deftest freeze-during-concurrent-writes
  (let [dir (tmp-dir)
        g (gate/create (str dir))
        n 8
        start (CyclicBarrier. (inc n))
        pool (Executors/newFixedThreadPool n)]
    (try
      (dotimes [_ n]
        (.submit pool
                 ^Callable
                 (fn []
                   (.await start)
                   (when (gate/acquire-write! g)
                     (Thread/sleep 30)
                     (gate/release-write! g))
                   nil)))
      (.await start)
      (let [result (gate/enter! g 5000)]
        (is (true? (:quiescent? result)))
        (is (zero? (:active-writes (gate/status g))))
        (is (pos? (:admitted-writes (gate/status g))))
        (is (= (:admitted-writes (gate/status g))
               (+ (:completed-writes (gate/status g))
                  (:failed-writes (gate/status g)))))
        (gate/freeze! g)
        (is (= :frozen (:phase (gate/status g))))
        (is (false? (gate/acquire-for! g "POST" "/v1/raids/auto/tick")))
        (is (false? (gate/acquire-for! g "POST" "/v1/battle/turn")))
        (is (false? (gate/acquire-for! g "POST" "/v1/shop/buy"))))
      (finally
        (.shutdownNow pool)
        (.awaitTermination pool 2 TimeUnit/SECONDS)))))

(deftest crash-restart-preserves-fail-closed-marker
  (let [dir (tmp-dir)
        path (str dir)
        g (gate/create path)]
    (gate/acquire-write! g)
    (let [timed (gate/enter! g 0)]
      (is (false? (:quiescent? timed)))
      (is (= :draining (:phase (gate/status g)))))
    (gate/release-write! g)
    (let [restarted (gate/create path)]
      (is (= :draining (:phase (gate/status restarted))))
      (is (false? (gate/acquire-write! restarted)))
      (is (true? (:quiescent? (gate/enter! restarted 200))))
      (gate/freeze! restarted)
      (let [again (gate/create path)]
        (is (= :frozen (:phase (gate/status again))))
        (is (false? (gate/acquire-read! again)))
        ;; After crash, counters restart at zero — must not imply consistency.
        (is (zero? (:active-writes (gate/status again))))
        (gate/resume! again)
        (is (= :open (:phase (gate/status again))))))))

(deftest resume-rejects-draining-and-active-writes
  (let [dir (tmp-dir)
        g (gate/create (str dir))]
    (gate/acquire-write! g)
    (gate/enter! g 0)
    (is (thrown? Exception (gate/resume! g)))
    (gate/release-write! g)
    (is (true? (:quiescent? (gate/enter! g 200))))
    (gate/acquire-read! g)
    ;; Reads must not block resume from quiescent.
    (gate/resume! g)
    (gate/release-read! g)
    (is (= :open (:phase (gate/status g))))))

(deftest invalid-marker-fails-closed
  (let [dir (tmp-dir)
        f (io/file dir "bridge-maintenance.edn")]
    (spit f "{:phase :totally-unknown}")
    (let [g (gate/create (str dir))]
      (is (= :quiescent (:phase (gate/status g))))
      (is (false? (gate/acquire-write! g))))))

(deftest frozen-marker-survives-restart-and-blocks-until-resume
  "Rollback note: JVM antiga sem :frozen trata fase desconhecida como :quiescent
   (ainda fail-closed). Esta JVM preserva :frozen explicitamente."
  (let [dir (tmp-dir)
        path (str dir)
        g (gate/create path)]
    (is (true? (:quiescent? (gate/enter! g 100))))
    (gate/freeze! g)
    (let [restarted (gate/create path)]
      (is (= :frozen (:phase (gate/status restarted))))
      (is (false? (gate/acquire-write! restarted)))
      (is (false? (gate/acquire-read! restarted)))
      (gate/resume! restarted)
      (is (= :open (:phase (gate/status restarted)))))))
