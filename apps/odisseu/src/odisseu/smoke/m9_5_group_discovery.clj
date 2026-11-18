(ns odisseu.smoke.m9-5-group-discovery
  "M9.5 — descoberta leve de grupos (WAWebCollections) + re-descoberta.
   Sem SSH, sem deploy, sem cutover."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [odisseu.cutover.durability :as dur]))

(defn- exists? [p]
  (.exists (io/file p)))

(defn- slurp-if [p]
  (when (exists? p) (slurp p)))

(deftest artefatos-m95
  (doseq [p ["scripts/lib/bootstrap-group-discovery.cjs"
             "test/bootstrap-group-discovery.test.cjs"
             "test/bootstrap-discover-http.test.cjs"
             "docs/m9-5-group-discovery.md"
             "docs/m9-5-1-group-discovery-evaluate.md"
             "src/zapbot/core.cljs"
             "src/zapbot/whatsapp_saude.cljs"]]
    (is (exists? p) p)))

(deftest light-discovery-evita-getchatmodel
  (let [js (slurp "scripts/lib/bootstrap-group-discovery.cjs")
        core (slurp "src/zapbot/core.cljs")
        saude (slurp "src/zapbot/whatsapp_saude.cljs")]
    (is (str/includes? js "WAWebCollections")
        "coleção moderna do WhatsApp Web")
    (is (str/includes? js "@g.us")
        "detecção por sufixo de grupo")
    (is (str/includes? js "autossuficiente")
        "documenta autossuficiência no evaluate")
    (is (str/includes? js "function helpersDisponiveis")
        "helpers aninhados dentro de discoverGroupsInPage")
    (is (str/includes? js "runDiscoverInIsolatedContext")
        "harness de teste estilo page.evaluate")
    ;; getChatModel só em comentários / flags de diagnóstico, não como chamada.
    (is (not (re-find #"\.getChatModel\s*\(" js))
        "implementação leve não chama getChatModel")
    (is (str/includes? core "bootstrap-group-discovery")
        "core usa o módulo leve")
    (is (str/includes? core "agendar-descoberta-http!")
        "HTTP agenda após resposta")
    (is (str/includes? core "staleGroupsPreserved")
        "não apaga groups.json bom em silêncio")
    (is (str/includes? saude "/bootstrap/discover-groups")
        "rota HTTP de re-descoberta")
    (is (str/includes? saude "json-res!")
        "resposta JSON segura")
    (is (str/includes? core "devGroupIdInvented\" false")
        "não inventa DEV_GROUP_ID")
    (is (str/includes? core "stagingGroupConfirmed\" false")
        "não confirma grupo automaticamente")))

(deftest gates-m95-cutover-false
  (let [g (dur/evaluate-m95-gates
           {:light-discovery-implemented? true
            :avoids-getchatmodel? true
            :rediscover-endpoint? true
            :diagnostics-safe? true
            :no-dev-group-invented? true
            :no-auto-group-confirmed? true
            :session-preserved? true
            :remote-deploy-executed? false
            :production-vm-touched? false})]
    (is (false? (:cutover-ready? g)))
    (is (false? (:rollback-recon-available? g)))
    (is (true? (:light-discovery-implemented? g)))))

(defn -main [& _]
  (let [js (slurp-if "scripts/lib/bootstrap-group-discovery.cjs")
        core (slurp-if "src/zapbot/core.cljs")
        saude (slurp-if "src/zapbot/whatsapp_saude.cljs")
        light? (and js (str/includes? js "WAWebCollections")
                    (str/includes? js "function helpersDisponiveis")
                    (not (re-find #"\.getChatModel\s*\(" js)))
        g (dur/evaluate-m95-gates
           {:light-discovery-implemented? light?
            :avoids-getchatmodel? light?
            :rediscover-endpoint? (and saude (str/includes? saude "/bootstrap/discover-groups"))
            :diagnostics-safe? (and core (str/includes? core "redacted"))
            :no-dev-group-invented? (and core (str/includes? core "devGroupIdInvented"))
            :no-auto-group-confirmed? (and core (str/includes? core "stagingGroupConfirmed"))
            :session-preserved? true
            :remote-deploy-executed? false
            :production-vm-touched? false})
        report {:milestone "M9.5"
                :cutover-ready? false
                :gates g}]
    (println (pr-str report))
    (when-not (and light?
                   (false? (:cutover-ready? g))
                   (:rediscover-endpoint? g))
      (System/exit 1))))
