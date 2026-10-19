(ns odisseu.smoke.m5-55-joy
  "M5.5 — reimport real descartável com Joy materializada em enfermaria-bed.
   Não toca .datomic-local/m53-real-offline. Sem ensure-trainer. Sem produção."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.migration.equivalence :as eq]
            [pokemon.migration.export :as export]
            [pokemon.migration.import :as mig]
            [pokemon.migration.transform :as transform]
            [pokemon.tests.adventure-m37-test]
            [pokemon.tests.joy-eggs-m410-test]
            [pokemon.tests.migration-m55-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private default-dump
  "/Users/caioclavico/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn")

(def ^:private default-report-dir
  "/Users/caioclavico/odisseu-recovery/m5-5-private")

(def ^:private storage ".datomic-local/m55-real-offline")
(def ^:private system-name "odisseu-m55-real-offline")
(def ^:private db-name "m55-import")
(def ^:private batch-id "m55-real-joy-20261014")

(defn- load-edn [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- redact [s]
  (when s
    (let [h (Integer/toHexString (hash s))]
      (str "redacted:" (subs h 0 (min 8 (count h)))))))

(defn- assert! [ok msg data]
  (when-not ok (throw (ex-info msg (or data {})))))

(defn- fence [store chat player]
  (long (:fence-generation (ports/get-player store chat player) 0)))

(defn- http [client base token method path body]
  (let [bldr (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base path)))
                 (.timeout (Duration/ofSeconds 60))
                 (.header "Authorization" (str "Bearer " token)))
        bldr (if body
               (-> bldr
                   (.header "Content-Type" "application/json")
                   (.method method (HttpRequest$BodyPublishers/ofString
                                   (json/write-str body))))
               (.method bldr method (HttpRequest$BodyPublishers/noBody)))
        res (.send client (.build bldr) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn- total-ids [store chat player slot]
  (set (concat (map :id-pokemon (ports/list-collection store chat player slot))
               (map #(get-in % [:pokemon :id-pokemon])
                    (ports/list-enfermaria store chat player slot)))))

(defn- run-real-import!
  [dump-path]
  (let [dump (load-edn dump-path)
        _ (when-not (get-in dump [:meta :authorized-offline-copy])
            (throw (ex-info "missing_authorized_offline_meta" {})))
        ir (transform/transform-export (export/from-authorized-offline-dump dump))
        chat (:chat-id ir)
        player (:player-id ir)
        client (persistence/client {:system system-name :storage-dir storage})
        _ (persistence/delete-db! client db-name)
        conn (persistence/ensure-db! client db-name)
        store (doto (store/make-store conn) trainer/bootstrap!)
        _ (when (ports/get-player store chat player)
            (throw (ex-info "player_exists_before_import" {})))
        import-result (mig/import-ir! store ir {:import-batch-id batch-id
                                                :require-match-resolved? false})
        reimport (mig/reimport-same-batch! store ir batch-id)
        beds (ports/list-enfermaria store chat player "a")
        col-a (ports/list-collection store chat player "a")
        col-b (ports/list-collection store chat player "b")
        eqv (eq/compare-ir store ir)
        ids-a (total-ids store chat player "a")
        ids-b (total-ids store chat player "b")
        ir-joy (->> (:pokemon (first (filter #(= "a" (:slot-id %)) (:saves ir))))
                    (filter (fn [p]
                              (let [loc (or (:location p) :equipe)]
                                (= "enfermaria"
                                   (name (if (keyword? loc) loc (keyword (str loc)))))))))
        bed-ids (set (map #(get-in % [:pokemon :id-pokemon]) beds))
        ir-ids (set (map :id-pokemon ir-joy))
        bed-prontos (set (map long (map :pronto-em beds)))
        ir-prontos (set (map long (map :pronto-em ir-joy)))]
    (assert! (= 7 (count beds)) "beds" {:n (count beds)})
    (assert! (= 19 (count col-a)) "col a" {:n (count col-a)})
    (assert! (= 119 (count col-b)) "col b" {})
    (assert! (= ir-ids bed-ids) "uuid joy" {})
    (assert! (= ir-prontos bed-prontos) "pronto-em" {})
    (assert! (= 26 (count ids-a)) "ids a" {})
    (assert! (= 119 (count ids-b)) "ids b" {})
    (assert! (zero? (count (set/intersection ids-a ids-b))) "intersection" {})
    (assert! (:ok? eqv) "equivalence" (:saves eqv))
    (assert! (every? #(= :duplicate (:status %)) reimport) "reimport" reimport)
    {:store store :ir ir :chat chat :player player
     :import import-result :reimport reimport :eqv eqv
     :beds beds :col-a-n (count col-a) :col-b-n (count col-b)
     :joy-species (mapv #(get-in % [:pokemon :species]) beds)
     :joy-ids-redacted (mapv #(redact (get-in % [:pokemon :id-pokemon])) beds)}))

(defn- run-gameplay!
  [{:keys [store chat player beds]}]
  (let [passed (atom [])
        min-pronto (apply min (map :pronto-em beds))
        max-pronto (apply max (map :pronto-em beds))
        ;; bridge read
        token "m55-real-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store store})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        q (str "?chatId=" (java.net.URLEncoder/encode (str chat) "UTF-8")
              "&playerId=" (java.net.URLEncoder/encode (str player) "UTF-8"))]
    (try
      (let [joy (http client base token "GET" (str "/v1/joy" q) nil)]
        (assert! (= 200 (:status joy)) "joy http" joy)
        (assert! (= 7 (count (get-in joy [:body "data" "enfermaria"]))) "joy 7" {})
        (swap! passed conj :joy-http-7))

      ;; early collect
      (let [code (try
                   (tx/joy-collect!
                    store {:chat-id chat :player-id player :save-id "a"
                           :fence-generation (fence store chat player)
                           :operation-id "m55-real-early"
                           :agora-ms (dec min-pronto)})
                   (catch Exception ex (err/error-code ex)))]
        (assert! (= :joy_nothing_ready code) "early" {:code code})
        (swap! passed conj :collect-before-deadline))

      ;; A→B→A isolation before collect
      (let [ids0 (total-ids store chat player "a")
            beds0 (set (map :bed-id (ports/list-enfermaria store chat player "a")))
            _ (trainer/switch-save! store chat player "b")
            beds-b (count (ports/list-enfermaria store chat player "b"))
            _ (trainer/switch-save! store chat player "a")
            ids1 (total-ids store chat player "a")
            beds1 (set (map :bed-id (ports/list-enfermaria store chat player "a")))]
        (assert! (zero? beds-b) "beds on b" {})
        (assert! (= ids0 ids1) "ids aba" {})
        (assert! (= beds0 beds1) "beds aba" {})
        (swap! passed conj :switch-a-b-a))

      ;; collect once + duplicate
      (let [r1 (tx/joy-collect!
                store {:chat-id chat :player-id player :save-id "a"
                       :fence-generation (fence store chat player)
                       :operation-id "m55-real-collect"
                       :agora-ms (inc max-pronto)})
            r2 (tx/joy-collect!
                store {:chat-id chat :player-id player :save-id "a"
                       :fence-generation (fence store chat player)
                       :operation-id "m55-real-collect"
                       :agora-ms (inc max-pronto)})]
        (assert! (= 7 (:n r1)) "collect n" r1)
        (assert! (true? (:duplicate r2)) "collect dup" r2)
        (assert! (zero? (count (ports/list-enfermaria store chat player "a"))) "beds empty" {})
        (assert! (= 26 (count (ports/list-collection store chat player "a"))) "col 26" {})
        (swap! passed conj :collect-once))

      ;; reopen JVM
      (let [store2 (doto (store/make-store
                          (persistence/ensure-db!
                           (persistence/client {:system system-name :storage-dir storage})
                           db-name))
                     trainer/bootstrap!)
            beds2 (ports/list-enfermaria store2 chat player "a")
            col2 (ports/list-collection store2 chat player "a")]
        (assert! (zero? (count beds2)) "reopen beds" {})
        (assert! (= 26 (count col2)) "reopen col" {})
        (assert! (= 119 (count (ports/list-collection store2 chat player "b"))) "reopen b" {})
        (swap! passed conj :jvm-reopen))

      {:passed @passed}
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (let [dump-path (or (System/getenv "ODISSEU_M53_DUMP") default-dump)
        report-dir (or (System/getenv "ODISSEU_M55_REPORT_DIR") default-report-dir)
        t0 (System/currentTimeMillis)]
    (println "=== M5.5 Joy compatibility ===")
    (.mkdirs (io/file report-dir))
    (println "==> Unit M5.5 fixtures")
    (let [s55 (t/run-tests 'pokemon.tests.migration-m55-test)]
      (when (or (pos? (:fail s55 0)) (pos? (:error s55 0)))
        (System/exit 1)))
    (println "==> Regressão M3.7")
    (let [s37 (t/run-tests 'pokemon.tests.adventure-m37-test)]
      (when (or (pos? (:fail s37 0)) (pos? (:error s37 0)))
        (System/exit 1)))
    (println "==> Regressão M4.10")
    (let [s410 (t/run-tests 'pokemon.tests.joy-eggs-m410-test)]
      (when (or (pos? (:fail s410 0)) (pos? (:error s410 0)))
        (System/exit 1)))
    (println "==> Import real descartável +" storage)
    (let [imp (run-real-import! dump-path)
          gp (run-gameplay! imp)
          ok? (and (= 7 (count (:beds imp)))
                   (:ok? (:eqv imp))
                   (= #{:joy-http-7 :collect-before-deadline :switch-a-b-a
                        :collect-once :jvm-reopen}
                      (set (:passed gp))))
          report {:milestone "M5.5"
                  :at (str (Instant/now))
                  :elapsed-ms (- (System/currentTimeMillis) t0)
                  :ok? ok?
                  :implementation
                  "SoT operacional = enfermaria-bed; import materializa leitos a partir de location=enfermaria; coleção ativa só equipe/pc; pronto-em/UUID preservados; receipt idempotente; conflito → import_joy_bed_conflict."
                  :datomic {:storage storage :system system-name :db db-name :batch batch-id}
                  :ref-m53-untouched? true
                  :joy (:joy-species imp)
                  :joy-ids-redacted (:joy-ids-redacted imp)
                  :counts {:collection-a (:col-a-n imp)
                           :beds-a (count (:beds imp))
                           :collection-b (:col-b-n imp)}
                  :equivalence-ok? (:ok? (:eqv imp))
                  :reimport (mapv :status (:reimport imp))
                  :passed (:passed gp)
                  :divergences []
                  :next "M5.4 gameplay sobre base m55 (opcional); staging ainda bloqueado por matching unmatched / adventure ausente."}]
      (spit (str report-dir "/REPORT-M55.md")
            (str "# M5.5 — Compatibilidade Joy importada (privado)\n\n"
                 "**Status: " (if ok? "ok" "FALHOU") ".**\n\n"
                 "## Implementação\n"
                 (:implementation report) "\n\n"
                 "## Contagens\n"
                 (pr-str (:counts report)) "\n\n"
                 "## Joy species\n"
                 (pr-str (:joy report)) "\n\n"
                 "## Passed\n"
                 (pr-str (:passed report)) "\n\n"
                 "## Equivalência\n"
                 (str (:equivalence-ok? report)) "\n\n"
                 "Referência m53-real-offline **não** foi modificada.\n"))
      (spit (str report-dir "/m55-report.edn") (pr-str report))
      (spit (str report-dir "/m55-report.txt")
            (with-out-str (pprint/pprint report)))
      (println "=== RESULT ok? ===" ok?)
      (pprint/pprint (select-keys report [:ok? :counts :passed :equivalence-ok?
                                          :reimport :implementation]))
      (shutdown-agents)
      (System/exit (if ok? 0 2)))))
