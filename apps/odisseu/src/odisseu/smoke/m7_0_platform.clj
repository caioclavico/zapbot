(ns odisseu.smoke.m7-0-platform
  "Runner M7.0: harness sintético bloqueio/admins + inventário."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [odisseu.platform.tests.m70-bloqueio-admins-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (println "=== M7.0 platform harness (sintético; sem produção) ===")
  (let [summary (test/run-tests 'odisseu.platform.tests.m70-bloqueio-admins-test)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (pprint/pprint {:milestone "M7.0"
                    :at (str (Instant/now))
                    :ok? ok?
                    :summary summary
                    :cassandra-cutover? false
                    :nota "Somente harness bloqueio/admins. Runtime Node continua Cassandra."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
