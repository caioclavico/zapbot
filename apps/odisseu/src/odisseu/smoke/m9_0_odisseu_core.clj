(ns odisseu.smoke.m9-0-odisseu-core
  "M9.0 — validação local dos gates de preparação odisseu-core.
   Sem SSH, sem Docker obrigatório, sem cutover."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.cutover.durability :as dur]))

(defn- exists? [p]
  (.exists (io/file p)))

(deftest artefatos-m90-presentes
  (doseq [p ["docker-compose.odisseu-core.yml"
             ".env.odisseu-core.example"
             "scripts/odisseu-core-up.sh"
             "scripts/odisseu-core-backup.sh"
             "scripts/odisseu-core-restore.sh"
             "docs/m9-0-odisseu-core-deploy.md"]]
    (is (exists? p) p)))

(deftest gates-m90-cutover-false
  (let [g (dur/evaluate-m90-gates
           {:compose-ready? true
            :volumes-isolated? true
            :bridge-loopback-only? true
            :session-path-isolated? true
            :journal-volume-planned? true
            :backup-scripts-ready? true
            :whatsapp-test-isolation-documented? true
            :memory-budget-documented? true
            :remote-deploy-executed? false
            :production-vm-touched? false})]
    (is (false? (:cutover-ready? g)))
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:global-protection-complete? g)))
    (is (false? (:remote-deploy-executed? g)))
    (is (false? (:production-vm-touched? g)))
    (is (true? (:compose-ready? g)))))

(deftest compose-nao-expoe-bridge-publica
  (let [txt (slurp "docker-compose.odisseu-core.yml")]
    (is (re-find #"127\.0\.0\.1:18090" txt))
    (is (re-find #"127\.0\.0\.1:3001" txt))
    (is (re-find #"WWEBJS_DATA_PATH:\s*/app/\.wwebjs_auth_core" txt))
    (is (re-find #"\./\.wwebjs_auth_core:/app/\.wwebjs_auth_core" txt))
    (is (re-find #"odisseu-core-journal" txt))
    (is (not (re-find #"0\.0\.0\.0:18090" txt)))
    (is (not (re-find #"with-cassandra" txt)))))

(defn -main [& _]
  (let [report {:milestone "M9.0"
                :cutover-ready? false
                :rollback-recon-available? false
                :remote-deploy-executed? false
                :artefacts-ok? (every? exists?
                                       ["docker-compose.odisseu-core.yml"
                                        "docs/m9-0-odisseu-core-deploy.md"])
                :gates (dur/evaluate-m90-gates
                        {:compose-ready? true
                         :volumes-isolated? true
                         :bridge-loopback-only? true
                         :session-path-isolated? true
                         :journal-volume-planned? true
                         :backup-scripts-ready? true
                         :whatsapp-test-isolation-documented? true
                         :memory-budget-documented? true
                         :remote-deploy-executed? false
                         :production-vm-touched? false})}]
    (println (pr-str report))
    (when-not (and (false? (:cutover-ready? (:gates report)))
                   (:artefacts-ok? report))
      (System/exit 1))))
