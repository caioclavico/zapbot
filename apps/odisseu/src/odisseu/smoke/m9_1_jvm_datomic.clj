(ns odisseu.smoke.m9-1-jvm-datomic
  "M9.1 — gates e checagens estáticas (ensaio de processo fica no script shell)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [odisseu.cutover.durability :as dur]))

(deftest compose-jvm-only-default
  (let [txt (slurp "docker-compose.odisseu-core.yml")]
    (is (re-find #"profiles:\s*\[\"with-node\"\]" txt))
    (is (re-find #"127\.0\.0\.1:18090" txt))
    (is (re-find #"GET /health NÃO exige Bearer|não exige Bearer" txt))))

(deftest dockerfile-jvm-health-and-curl
  (let [txt (slurp "Dockerfile.jvm")]
    (is (re-find #"curl ca-certificates" txt))
    (is (re-find #"HEALTHCHECK" txt))
    (is (re-find #"ODISSEU_BRIDGE_SEED=false" txt))
    (is (re-find #"ODISSEU_BRIDGE_RESET=false" txt))
    (is (re-find #"ODISSEU_BRIDGE_SYSTEM=odisseu-m90-core" txt))
    (is (re-find #"ODISSEU_BRIDGE_DB=core" txt))))

(deftest health-route-unauthenticated-in-source
  (let [txt (slurp "modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj")
        ;; Trecho do handler: /health responde com ok! sem passar por require-auth!
        chunk (subs txt
                    (or (str/index-of txt "(= path \"/health\")") 0)
                    (or (str/index-of txt "(or (str/starts-with? path \"/admin\")")
                        (count txt)))]
    (is (re-find #"\(=\s*path\s*\"/health\"\)" txt))
    (is (re-find #"ok!\s+ex" chunk))
    (is (not (re-find #"require-auth!" chunk)))))

(deftest gates-m91-cutover-false
  (let [g (dur/evaluate-m91-gates
           {:health-unauthenticated-ok? true
            :curl-in-jvm-image? true
            :jvm-only-default? true
            :node-profile-gated? true
            :seed-reset-default-false? true
            :compose-config-ok? true
            :local-persistence-ok? true
            :journal-same-disk-honest? true
            :remote-jvm-deployed? false
            :whatsapp-started? false
            :production-vm-touched? false})]
    (is (false? (:cutover-ready? g)))
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:independent-failure-domain? g)))
    (is (false? (:remote-jvm-deployed? g)))
    (is (false? (:whatsapp-started? g)))))

(defn -main [& _]
  (let [g (dur/evaluate-m91-gates
           {:health-unauthenticated-ok? true
            :curl-in-jvm-image? (.exists (io/file "Dockerfile.jvm"))
            :jvm-only-default? true
            :node-profile-gated? (boolean (re-find #"with-node"
                                                   (slurp "docker-compose.odisseu-core.yml")))
            :seed-reset-default-false? true
            :compose-config-ok? true
            :local-persistence-ok? false ;; preenchido pelo shell após lab
            :journal-same-disk-honest? true
            :remote-jvm-deployed? false
            :whatsapp-started? false
            :production-vm-touched? false})]
    (println (pr-str {:milestone "M9.1" :gates g}))
    (when-not (false? (:cutover-ready? g))
      (System/exit 1))))
