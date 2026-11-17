(ns odisseu.smoke.m9-4-bootstrap-discovery
  "M9.4 — regressão: path sessão core + descoberta de grupos pós-READY.
   Sem SSH, sem deploy, sem cutover."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.cutover.durability :as dur]))

(defn- exists? [p]
  (.exists (io/file p)))

(defn- compose-core []
  (slurp "docker-compose.odisseu-core.yml"))

(defn- core-cljs []
  (slurp "src/zapbot/core.cljs"))

(deftest production-session-path-gate
  (testing "rejeita paths de produção"
    (is (true? (dur/production-session-path? ".wwebjs_auth")))
    (is (true? (dur/production-session-path? "/app/.wwebjs_auth")))
    (is (true? (dur/production-session-path? "  /app/.wwebjs_auth  "))))
  (testing "aceita sufixos isolados"
    (is (false? (dur/production-session-path? ".wwebjs_auth_core")))
    (is (false? (dur/production-session-path? "/app/.wwebjs_auth_core")))
    (is (false? (dur/production-session-path? ".wwebjs_auth_staging")))
    (is (false? (dur/production-session-path? "/app/.wwebjs_auth_staging")))))

(deftest compose-core-usa-path-auth-core
  (let [txt (compose-core)]
    (is (re-find #"WWEBJS_DATA_PATH:\s*/app/\.wwebjs_auth_core" txt)
        "Compose deve definir /app/.wwebjs_auth_core (staging rejeita /.wwebjs_auth)")
    (is (re-find #"\./\.wwebjs_auth_core:/app/\.wwebjs_auth_core" txt)
        "Bind host↔container deve usar sufixo _core em ambos os lados")
    ;; Regressão M9.1: path/bind sem sufixo quebrava o gate de staging.
    (is (not (re-find #"WWEBJS_DATA_PATH:\s*/app/\.wwebjs_auth(?!_)" txt)))
    (is (not (re-find #":/app/\.wwebjs_auth(?!_)" txt)))))

(deftest compose-staging-usa-path-auth-staging
  (let [txt (slurp "docker-compose.staging.yml")]
    (is (re-find #"WWEBJS_DATA_PATH:\s*/app/\.wwebjs_auth_staging" txt))
    (is (re-find #"\./\.wwebjs_auth_staging:/app/\.wwebjs_auth_staging" txt))))

(deftest bootstrap-discovery-retries-e-fallback
  (let [src (core-cljs)]
    (is (str/includes? src "bootstrap-tentativas")
        "retries limitados presentes")
    (is (str/includes? src "bootstrap-backoff-ms")
        "backoff presente")
    ;; M9.5: caminho leve (WAWebCollections) substitui Store/getChatModel.
    (is (or (str/includes? src "bootstrap-group-discovery")
            (str/includes? src "js-listar-grupos-store"))
        "descoberta via evaluate presente")
    (is (str/includes? src "connectedUser\" \"redacted\"")
        "não grava número no JSON de descoberta")
    (is (str/includes? src "número omitido")
        "não loga telefone no READY")))

(deftest artefatos-m94-presentes
  (doseq [p ["docs/m9-4-bootstrap-group-discovery.md"
             "docker-compose.odisseu-core.yml"
             "src/zapbot/core.cljs"
             "src/zapbot/config.cljs"]]
    (is (exists? p) p)))

(deftest gates-m94-cutover-false
  (let [g (dur/evaluate-m94-gates
           {:compose-session-path-ok? true
            :getchats-retry-implemented? true
            :store-fallback-implemented? true
            :phone-redacted? true
            :bootstrap-no-commands? true
            :session-preserved? true
            :remote-deploy-executed? false
            :production-vm-touched? false})]
    (is (false? (:cutover-ready? g)))
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:global-protection-complete? g)))
    (is (true? (:compose-session-path-ok? g)))))

(defn -main [& _]
  (let [compose-ok?
        (let [txt (compose-core)]
          (and (re-find #"WWEBJS_DATA_PATH:\s*/app/\.wwebjs_auth_core" txt)
               (re-find #"\./\.wwebjs_auth_core:/app/\.wwebjs_auth_core" txt)
               (not (dur/production-session-path? "/app/.wwebjs_auth_core"))
               (dur/production-session-path? "/app/.wwebjs_auth")))
        src (core-cljs)
        g (dur/evaluate-m94-gates
           {:compose-session-path-ok? compose-ok?
            :getchats-retry-implemented? (str/includes? src "bootstrap-tentativas")
            :store-fallback-implemented? (or (str/includes? src "bootstrap-group-discovery")
                                             (str/includes? src "js-listar-grupos-store"))
            :phone-redacted? (str/includes? src "redacted")
            :bootstrap-no-commands? true
            :session-preserved? true
            :remote-deploy-executed? false
            :production-vm-touched? false})
        report {:milestone "M9.4"
                :cutover-ready? false
                :compose-session-path-ok? compose-ok?
                :gates g}]
    (println (pr-str report))
    (when-not (and compose-ok?
                   (false? (:cutover-ready? g))
                   (:getchats-retry-implemented? g)
                   (:store-fallback-implemented? g))
      (System/exit 1))))
