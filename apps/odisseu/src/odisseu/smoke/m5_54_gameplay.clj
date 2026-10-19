(ns odisseu.smoke.m5-54-gameplay
  "M5.4 — valida gameplay JVM+bridge sobre saves reais importados (M5.3B).
   Referência m53-real-offline permanece imutável; mutações só na cópia descartável.
   Sem ensure-trainer na identidade importada; sem Oracle/GCP/Cassandra remoto."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.joy :as joy]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.migration.backup :as backup]
            [pokemon.migration.export :as export]
            [pokemon.migration.transform :as transform])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files Paths]
           [java.security MessageDigest]
           [java.time Duration Instant]))

(def ^:private default-dump
  "/Users/caioclavico/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn")

(def ^:private default-report-dir
  "/Users/caioclavico/odisseu-recovery/m5-4-private")

(def ^:private ref-storage ".datomic-local/m53-real-offline")
(def ^:private copy-storage ".datomic-local/m54-gameplay")
(def ^:private system-name "odisseu-m53-real-offline")
(def ^:private db-name "m53-import")

(defn- load-edn [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- redact [s]
  (when s
    (let [h (Integer/toHexString (hash s))]
      (str "redacted:" (subs h 0 (min 8 (count h)))))))

(defn- sha256-file
  [path]
  (let [p (Paths/get (str path) (into-array String []))
        digest (MessageDigest/getInstance "SHA-256")
        bytes (Files/readAllBytes p)]
    (.update digest bytes)
    (str/join (map #(format "%02x" %) (.digest digest)))))

(defn- storage-fingerprint
  "Hash de db.log + log.idx (ignora .lock)."
  [storage-rel]
  (let [root (persistence/resolve-storage-dir storage-rel)
        base (io/file root system-name db-name)
        log (io/file base "db.log")
        idx (io/file base "log.idx")]
    (when-not (.isFile log)
      (throw (ex-info "fingerprint_missing_db_log" {:storage storage-rel :path (str log)})))
    {:storage storage-rel
     :db-log-sha256 (sha256-file log)
     :log-idx-sha256 (sha256-file idx)
     :db-log-bytes (.length log)
     :log-idx-bytes (.length idx)}))

(defn- bucket-counts [col]
  {"equipe" (count (filter #(= "equipe" (:location %)) col))
   "pc" (count (filter #(= "pc" (:location %)) col))
   "enfermaria" (count (filter #(= "enfermaria" (:location %)) col))
   "total" (count col)})

(defn- open-store!
  [storage]
  (let [client (persistence/client {:system system-name :storage-dir storage})
        conn (persistence/ensure-db! client db-name)]
    (doto (store/make-store conn) trainer/bootstrap!)))

(defn- http
  [client base token method path body]
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
     :body (json/read-str (.body res))
     :raw (.body res)}))

(defn- q [chat player & [extra]]
  (str "?chatId=" (java.net.URLEncoder/encode (str chat) "UTF-8")
       "&playerId=" (java.net.URLEncoder/encode (str player) "UTF-8")
       (when extra (str "&" extra))))

(defn- assert! [ok msg data]
  (when-not ok
    (throw (ex-info msg (or data {})))))

(defn- inspect-save
  [store chat player slot]
  (let [save (ports/get-save store chat player slot)
        col (ports/list-collection store chat player slot)
        party (ports/list-party store chat player slot)
        beds (ports/list-enfermaria store chat player slot)
        ids (mapv :id-pokemon col)
        joy-loc (filterv #(= "enfermaria" (:location %)) col)
        shiny (filterv :shiny col)
        with-legado (count (filter :legado col))
        with-origin (count (filter :origin-edn col))
        with-golpes (count (filter #(seq (:golpes %)) col))
        order-ok? (= ids (map :id-pokemon (sort-by :collection-index col)))]
    {:slot slot
     :coins (:coins save)
     :trainer-xp (:trainer-xp save)
     :trainer-level (:trainer-level save)
     :xp-components {:vitorias (:vitorias-treinador save 0)
                     :pe-ginasios (:pe-ginasios save 0)
                     :pe-raids (:pe-raids save 0)
                     :xp-insignias (:xp-insignias save 0)
                     :xp-missoes (:xp-missoes save 0)}
     :collection-count (count col)
     :party-count (count party)
     :buckets (bucket-counts col)
     :ids-unique? (= (count ids) (count (set ids)))
     :order-preserved? order-ok?
     :joy-location {:n (count joy-loc)
                    :all-have-pronto-em (every? some? (map :pronto-em joy-loc))
                    :species (mapv :species joy-loc)
                    :ids-redacted (mapv #(redact (:id-pokemon %)) joy-loc)
                    :pronto-em-ms (mapv :pronto-em joy-loc)}
     :joy-beds-n (count beds)
     :shiny-n (count shiny)
     :shiny-species (mapv :species shiny)
     :legado-n with-legado
     :origin-edn-n with-origin
     :golpes-n with-golpes
     :first-id-redacted (redact (first ids))
     :last-id-redacted (redact (last ids))}))

(defn- validate-read-jvm!
  "Somente leitura por slot — não alterna save (sem mutação nesta fase)."
  [store chat player expected]
  (let [a (inspect-save store chat player "a")
        b (inspect-save store chat player "b")
        active (ports/active-save-id store chat player)
        ids-a (set (map :id-pokemon (ports/list-collection store chat player "a")))
        ids-b (set (map :id-pokemon (ports/list-collection store chat player "b")))
        profile-a (reads/trainer store chat player)
        coll-b-dto (reads/collection store chat player "b")
        ok? (and (= 26 (:collection-count a))
                 (= 119 (:collection-count b))
                 (= 19 (get (:buckets a) "equipe"))
                 (= 7 (get (:buckets a) "enfermaria"))
                 (= 119 (get (:buckets b) "equipe"))
                 (= 0 (get (:buckets b) "enfermaria"))
                 (= 198 (:coins a))
                 (= 437 (:coins b))
                 (= 29 (long (:trainer-xp a 0)))
                 (= 127 (long (:trainer-xp b 0)))
                 (:ids-unique? a) (:ids-unique? b)
                 (:order-preserved? a) (:order-preserved? b)
                 (zero? (count (set/intersection ids-a ids-b)))
                 (= 7 (get-in a [:joy-location :n]))
                 (true? (get-in a [:joy-location :all-have-pronto-em]))
                 (= 1 (:shiny-n b))
                 (= ["Voltorb"] (:shiny-species b))
                 (= 26 (:legado-n a) (:origin-edn-n a))
                 (= 119 (:legado-n b) (:origin-edn-n b))
                 (= "a" active)
                 (= 26 (:collection-count profile-a))
                 (= 119 (:count coll-b-dto)))]
    {:ok? ok?
     :active active
     :a (update a :joy-location #(dissoc % :pronto-em-ms))
     :b (-> b (dissoc :joy-location) (update :shiny-species identity))
     :intersection (count (set/intersection ids-a ids-b))
     :profile-a-redacted {:xp (get-in profile-a [:profile :xp])
                          :nivel (get-in profile-a [:profile :nivel])
                          :coins (get-in profile-a [:profile :coins])
                          :collection-count (:collection-count profile-a)
                          :active-save-id (:active-save-id profile-a)}
     :profile-b-redacted {:xp (:trainer-xp b)
                          :nivel (:trainer-level b)
                          :coins (:coins b)
                          :collection-count (:collection-count b)
                          :save-id "b"}
     :joy-beds-vs-location {:a-beds (:joy-beds-n a)
                            :a-location (get-in a [:joy-location :n])
                            :structural-gap? (and (zero? (:joy-beds-n a))
                                                  (= 7 (get-in a [:joy-location :n])))}
     :expected expected}))

(defn- validate-bridge-reads!
  [client base token chat player]
  (let [trainer-a (http client base token "GET" (str "/v1/trainer" (q chat player)) nil)
        coll-a (http client base token "GET"
                     (str "/v1/collection" (q chat player "saveId=a")) nil)
        coll-b (http client base token "GET"
                     (str "/v1/collection" (q chat player "saveId=b")) nil)
        saves (http client base token "GET" (str "/v1/saves" (q chat player)) nil)
        joy (http client base token "GET" (str "/v1/joy" (q chat player)) nil)
        active (http client base token "GET" (str "/v1/active-save" (q chat player)) nil)
        col-a-items (get-in coll-a [:body "data" "collection"])
        col-b-items (get-in coll-b [:body "data" "collection"])
        ;; DTO não expõe location/pronto-em/legado/origin
        dto-keys (set (keys (or (first col-a-items) {})))
        leaked? (boolean (some dto-keys #{"legado" "originEdn" "origin" "prontoEm" "location"}))
        envelope-leak?
        (boolean (some (fn [p]
                         (or (map? (get p "pokemon"))
                             (contains? p "pronto-em")
                             (contains? p "prontoEm")))
                       col-a-items))]
    (assert! (= 200 (:status trainer-a)) "bridge trainer" trainer-a)
    (assert! (= 200 (:status coll-a)) "bridge coll a" coll-a)
    (assert! (= 200 (:status coll-b)) "bridge coll b" coll-b)
    (assert! (= 200 (:status saves)) "bridge saves" saves)
    (assert! (= 200 (:status joy)) "bridge joy" joy)
    (assert! (= 26 (get-in coll-a [:body "data" "count"])) "coll a count" coll-a)
    (assert! (= 119 (get-in coll-b [:body "data" "count"])) "coll b count" coll-b)
    (assert! (= 26 (count col-a-items)) "coll a len" {})
    (assert! (= 119 (count col-b-items)) "coll b len" {})
    ;; ordem por collectionIndex 0..n-1
    (assert! (= (range 119)
                (map #(get % "collectionIndex") col-b-items))
             "coll b order" {})
    {:ok? (and (= 200 (:status trainer-a))
               (= 26 (count col-a-items))
               (= 119 (count col-b-items))
               (not leaked?)
               (not envelope-leak?))
     :trainer-xp (get-in trainer-a [:body "data" "profile" "xp"])
     :trainer-coins (get-in trainer-a [:body "data" "profile" "coins"])
     :trainer-collection (get-in trainer-a [:body "data" "collectionCount"])
     :saves-count (count (get-in saves [:body "data" "saves"]))
     :active-save (get-in saves [:body "data" "activeSaveId"])
     :joy-beds (count (get-in joy [:body "data" "enfermaria"]))
     :joy-feridos (count (get-in joy [:body "data" "feridos"]))
     :dto-leaked-legado-or-location? leaked?
     :envelope-in-collection? envelope-leak?
     :party-count (count (get-in active [:body "data" "party"]))
     :payloads {:trainer (get-in trainer-a [:body "data"])
                :collection-a (get-in coll-a [:body "data"])
                :collection-b (get-in coll-b [:body "data"])
                :saves (get-in saves [:body "data"])
                :joy (get-in joy [:body "data"])}}))

(defn- mutate-gameplay!
  "Mutações só na cópia: switch, moedas, HP, Joy (send novo + collect importado),
   idempotência e fence obsoleto."
  [store client base token chat player]
  (let [passed (atom [])
        divergences (atom [])
        before-a-ids (set (map :id-pokemon (ports/list-collection store chat player "a")))
        before-b-ids (set (map :id-pokemon (ports/list-collection store chat player "b")))
        fence0 (:fence-generation (ports/get-player store chat player) 0)

        ;; A → B → A via bridge
        act-b (http client base token "POST" "/v1/saves/activate"
                    {"chatId" chat "playerId" player "saveId" "b"
                     "expectedFenceGeneration" fence0})
        _ (assert! (= 200 (:status act-b)) "activate b" act-b)
        fence-b (get-in act-b [:body "data" "fenceGeneration"])
        mid-active (get-in act-b [:body "data" "activeSaveId"])
        mid-b-count (count (ports/list-collection store chat player "b"))
        act-a (http client base token "POST" "/v1/saves/activate"
                    {"chatId" chat "playerId" player "saveId" "a"
                     "expectedFenceGeneration" fence-b})
        _ (assert! (= 200 (:status act-a)) "activate a" act-a)
        fence-a2 (get-in act-a [:body "data" "fenceGeneration"])
        after-a-ids (set (map :id-pokemon (ports/list-collection store chat player "a")))
        after-b-ids (set (map :id-pokemon (ports/list-collection store chat player "b")))
        _ (swap! passed conj :switch-a-b-a)
        _ (assert! (and (= "b" mid-active) (= 119 mid-b-count)
                        (= before-a-ids after-a-ids)
                        (= before-b-ids after-b-ids))
                   "switch preserved ids" {})

        ;; moedas + idempotência
        coins-before (:coins (ports/get-save store chat player "a"))
        r1 (http client base token "POST" "/v1/rewards/coins"
                 {"chatId" chat "playerId" player "saveId" "a"
                  "fenceGeneration" fence-a2 "operationId" "m54-coins-1"
                  "coinsDelta" 5})
        r2 (http client base token "POST" "/v1/rewards/coins"
                 {"chatId" chat "playerId" player "saveId" "a"
                  "fenceGeneration" fence-a2 "operationId" "m54-coins-1"
                  "coinsDelta" 5})
        _ (assert! (= 200 (:status r1)) "coins1" r1)
        _ (assert! (false? (get-in r1 [:body "data" "duplicate"])) "not dup" r1)
        _ (assert! (= 200 (:status r2)) "coins2" r2)
        _ (assert! (true? (get-in r2 [:body "data" "duplicate"])) "dup" r2)
        coins-after (:coins (ports/get-save store chat player "a"))
        _ (assert! (= (+ coins-before 5) coins-after) "coins +5 once" {})
        _ (swap! passed conj :coins-idempotent)

        ;; fence obsoleto (usar fence0 após switch)
        stale (http client base token "POST" "/v1/rewards/coins"
                    {"chatId" chat "playerId" player "saveId" "a"
                     "fenceGeneration" fence0 "operationId" "m54-stale"
                     "coinsDelta" 1})
        _ (assert! (= 409 (:status stale)) "stale status" stale)
        _ (assert! (= "stale_writer_fence"
                      (get-in stale [:body "error" "code"]))
                   "stale code" stale)
        _ (swap! passed conj :stale-fence)

        ;; HP / estado de batalha leve: ferir um da equipe
        col (ports/list-collection store chat player "a")
        equipe-idx (->> col
                        (map-indexed vector)
                        (filter (fn [[_ p]] (= "equipe" (:location p))))
                        (map first)
                        first)
        poke (nth col equipe-idx)
        wctx (writer/freeze-writer store chat player)
        _ (ports/update-hp-status! store wctx equipe-idx
                                   (max 1 (dec (long (:hp-atual poke (:hp poke)))))
                                   "envenenado")
        col2 (ports/list-collection store chat player "a")
        poke2 (nth col2 equipe-idx)
        _ (assert! (joy/ferido? poke2) "ferido apos hp" poke2)
        _ (swap! passed conj :hp-status)

        ;; Joy collect sobre importados (espera falha estrutural: beds vazios)
        joy-collect (http client base token "POST" "/v1/joy/collect"
                          {"chatId" chat "playerId" player "saveId" "a"
                           "fenceGeneration" (:fence-generation
                                              (ports/get-player store chat player) 0)
                           "operationId" "m54-joy-collect-imported"
                           "agoraMs" (+ (System/currentTimeMillis)
                                        (* 365 24 60 60 1000))})
        joy-status (http client base token "GET" (str "/v1/joy" (q chat player)) nil)
        beds-n (count (get-in joy-status [:body "data" "enfermaria"]))
        _ (when (zero? beds-n)
            (swap! divergences conj
                   {:kind :joy-imported-not-in-beds
                    :detail "Pokémon com location=enfermaria não materializados como enfermaria-bed; joy/collect não os vê."
                    :collect-status (:status joy-collect)
                    :collect-code (get-in joy-collect [:body "error" "code"])}))
        _ (swap! passed conj :joy-collect-imported-probed)

        ;; Joy send de ferido novo (caminho JVM nativo) — deve funcionar
        fence-now (:fence-generation (ports/get-player store chat player) 0)
        send (http client base token "POST" "/v1/joy/send"
                   {"chatId" chat "playerId" player "saveId" "a"
                    "fenceGeneration" fence-now
                    "operationId" "m54-joy-send-new"
                    "collectionIndex" equipe-idx
                    "agoraMs" (System/currentTimeMillis)})
        _ (if (= 200 (:status send))
            (swap! passed conj :joy-send-new)
            (swap! divergences conj
                   {:kind :joy-send-failed
                    :status (:status send)
                    :body (:body send)}))

        inv (ports/list-inventory store chat player "a")]
    {:passed @passed
     :divergences @divergences
     :switch {:mid-active mid-active :preserved-ids? true}
     :coins {:before coins-before :after coins-after}
     :joy-imported {:beds beds-n
                    :collect-status (:status joy-collect)
                    :collect-code (get-in joy-collect [:body "error" "code"])}
     :joy-send-new {:status (:status send)
                    :ok? (= 200 (:status send))}
     :inventory-n (count inv)
     :fence-final (:fence-generation (ports/get-player store chat player) 0)}))

(defn- run-node-present!
  [payload-path]
  (let [pb (doto (ProcessBuilder.
                  ["node" "scripts/test-m5-4-present.cjs" (str payload-path)])
             (.directory (io/file "."))
             (.redirectErrorStream true))
        proc (.start pb)
        out (slurp (.getInputStream proc))
        code (.waitFor proc)]
    {:exit code
     :out out
     :ok? (zero? code)}))

(defn -main
  [& _]
  (let [dump-path (or (System/getenv "ODISSEU_M53_DUMP") default-dump)
        report-dir (or (System/getenv "ODISSEU_M54_REPORT_DIR") default-report-dir)
        t0 (System/currentTimeMillis)]
    (println "=== M5.4 gameplay validation ===")
    (println "ref:" ref-storage "copy:" copy-storage)
    (.mkdirs (io/file report-dir))
    (let [fp-before (storage-fingerprint ref-storage)
          _ (println "ref fingerprint before:" (:db-log-sha256 fp-before))
          _ (backup/snapshot-storage! ref-storage copy-storage)
          dump (load-edn dump-path)
          _ (when-not (get-in dump [:meta :authorized-offline-copy])
              (throw (ex-info "missing_authorized_offline_meta" {})))
          export-data (export/from-authorized-offline-dump dump)
          ir (transform/transform-export export-data)
          chat (:chat-id ir)
          player (:player-id ir)
          expected {:a {:count 26 :xp 29 :coins 198 :equipe 19 :enfermaria 7}
                    :b {:count 119 :xp 127 :coins 437 :equipe 119 :shiny 1}}
          store (open-store! copy-storage)
          ;; NUNCA ensure-trainer na identidade importada
          _ (when-not (ports/get-player store chat player)
              (throw (ex-info "imported_player_missing" {})))
          jvm-read (validate-read-jvm! store chat player expected)
          _ (assert! (:ok? jvm-read) "jvm read failed" jvm-read)
          token "m54-gameplay-token"
          http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store store})
          base (:base-url http-srv)
          client (HttpClient/newHttpClient)]
      (try
        (let [bridge-read (validate-bridge-reads! client base token chat player)
              payload-path (str report-dir "/present-payload.json")
              ;; payloads camelCase já vêm do HTTP; grava sem chat/player reais
              present-payload {:trainer (dissoc (:trainer (:payloads bridge-read))
                                                "chatId" "playerId")
                               :collectionA (dissoc (:collection-a (:payloads bridge-read))
                                                    "chatId" "playerId")
                               :collectionB (dissoc (:collection-b (:payloads bridge-read))
                                                    "chatId" "playerId")
                               :saves (dissoc (:saves (:payloads bridge-read))
                                              "chatId" "playerId")
                               :joy (dissoc (:joy (:payloads bridge-read))
                                            "chatId" "playerId")}
              _ (spit payload-path (json/write-str present-payload))
              present (run-node-present! payload-path)
              mut (mutate-gameplay! store client base token chat player)
              _ (bridge/stop! http-srv)
              ;; reinício JVM: reabre store na cópia
              store2 (open-store! copy-storage)
              after-reopen {:a-count (count (ports/list-collection store2 chat player "a"))
                            :b-count (count (ports/list-collection store2 chat player "b"))
                            :coins-a (:coins (ports/get-save store2 chat player "a"))
                            :active (ports/active-save-id store2 chat player)
                            :joy-beds (count (ports/list-enfermaria store2 chat player "a"))}
              fp-after (storage-fingerprint ref-storage)
              ref-intact? (= fp-before fp-after)
              structural-joy? (get-in jvm-read [:joy-beds-vs-location :structural-gap?])
              expected-a-after (if (:ok? (:joy-send-new mut)) 25 26)
              ok? (and (:ok? jvm-read)
                       (:ok? bridge-read)
                       (:ok? present)
                       ref-intact?
                       (contains? (set (:passed mut)) :switch-a-b-a)
                       (contains? (set (:passed mut)) :coins-idempotent)
                       (contains? (set (:passed mut)) :stale-fence)
                       (contains? (set (:passed mut)) :hp-status)
                       (= expected-a-after (:a-count after-reopen))
                       (= 119 (:b-count after-reopen))
                       ;; moedas A: 198 + 5 na cópia
                       (= 203 (:coins-a after-reopen)))
              report {:milestone "M5.4"
                      :at (str (Instant/now))
                      :elapsed-ms (- (System/currentTimeMillis) t0)
                      :ok? ok?
                      :ref-intact? ref-intact?
                      :fingerprint-before fp-before
                      :fingerprint-after fp-after
                      :chat-redacted (redact chat)
                      :player-redacted (redact player)
                      :jvm-read (dissoc jvm-read :expected)
                      :bridge-read (dissoc bridge-read :payloads)
                      :present present
                      :mutations (dissoc mut :inventory-n)
                      :after-jvm-reopen after-reopen
                      :passed-ops (vec (concat
                                        (when (:ok? jvm-read) [:jvm-read-a-b])
                                        (when (:ok? bridge-read) [:bridge-read])
                                        (when (:ok? present) [:node-present])
                                        (:passed mut)
                                        (when ref-intact? [:ref-intact])
                                        [:jvm-reopen]))
                      :divergences (vec (concat
                                         (:divergences mut)
                                         (when structural-joy?
                                           [{:kind :joy-beds-missing
                                             :detail "Import preservou location/pronto-em no pokemon; gameplay Joy usa enfermaria-bed."}])
                                         (when-not (:ok? present)
                                           [{:kind :present-failed :out (:out present)}])))
                      :risks-blocking-cutover
                      (cond-> []
                        structural-joy?
                        (conj "Joy importada inacessível via /v1/joy e joy/collect — exige adaptador ou transform auditável pokemon→bed.")
                        (not ref-intact?)
                        (conj "Referência M5.3B foi alterada durante o teste — abortar.")
                        (not (:ok? present))
                        (conj "Apresentação Node falhou em paginação/envelopes."))
                      :next-smallest-change
                      (if structural-joy?
                        "Adaptador de leitura Joy: sintetizar beds a partir de pokemon com location=enfermaria (+ pronto-em), sem reescrever o import; ou transform auditável pós-import gerando enfermaria-bed e removendo esses índices da coleção ativa — preservar origin-edn."
                        "Nenhuma estrutural; seguir para staging controlado com flags off.")}]
          (spit (str report-dir "/REPORT-M54.md")
                (str "# M5.4 — Gameplay com saves reais (privado)\n\n"
                     "**Status: " (if ok? "ok com ressalvas conscientes" "FALHOU") ".**\n\n"
                     "## Operações\n"
                     (str/join "\n" (map #(str "- `" % "`") (:passed-ops report)))
                     "\n\n## Divergências\n"
                     (if (seq (:divergences report))
                       (str/join "\n" (map #(str "- **" (name (:kind %)) "**: " (:detail % %))
                                           (:divergences report)))
                       "- nenhuma crítica além das listadas em riscos")
                     "\n\n## Integridade\n"
                     "- Save A: 26 (19 equipe + 7 enfermaria location), XP 29, moedas base 198\n"
                     "- Save B: 119 equipe, XP 127, moedas base 437, Voltorb shiny\n"
                     "- Referência M5.3B intacta: `" ref-intact? "`\n"
                     "- Joy beds vs location: `" (pr-str (:joy-beds-vs-location jvm-read)) "`\n"
                     "\n## Riscos cutover\n"
                     (str/join "\n" (map #(str "- " %) (:risks-blocking-cutover report)))
                     "\n\n## Próxima menor mudança\n"
                     (:next-smallest-change report)
                     "\n"))
          (spit (str report-dir "/m54-report.edn") (pr-str report))
          (spit (str report-dir "/m54-report.txt")
                (with-out-str (pprint/pprint report)))
          (println "=== RESULT ok? ===" ok? "ref-intact?" ref-intact?
                   "elapsed-ms" (:elapsed-ms report))
          (pprint/pprint (select-keys report [:ok? :ref-intact? :passed-ops
                                              :divergences :risks-blocking-cutover
                                              :next-smallest-change :after-jvm-reopen]))
          (shutdown-agents)
          (System/exit (if ok? 0 2)))
        (catch Throwable t
          (try (bridge/stop! http-srv) (catch Exception _))
          (let [fp-after (try (storage-fingerprint ref-storage)
                              (catch Exception _ {:error "fingerprint-failed"}))]
            (println "=== M5.4 FAILED ===" (ex-message t))
            (pprint/pprint {:error (ex-message t) :data (ex-data t)
                            :fp-before fp-before :fp-after fp-after})
            (spit (str report-dir "/m54-failure.edn")
                  (pr-str {:at (str (Instant/now))
                           :error (ex-message t)
                           :data (ex-data t)
                           :fp-before fp-before
                           :fp-after fp-after}))
            (shutdown-agents)
            (System/exit 1)))))))
