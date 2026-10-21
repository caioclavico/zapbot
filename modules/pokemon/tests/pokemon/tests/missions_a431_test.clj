(ns pokemon.tests.missions-a431-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.mission-queries :as missions]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.nio.file Files]
           [java.time Instant]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def now (Instant/parse "2026-10-20T14:00:00Z"))
(def opts {:instant now :timezone "America/Sao_Paulo"})

(defn fresh []
  (let [dir (str (Files/createTempDirectory "odisseu-a431-" (make-array java.nio.file.attribute.FileAttribute 0)))
        client (persistence/client {:system "a431" :storage-dir dir})
        conn (persistence/ensure-db! client "missions")
        s (trainer/bootstrap! (store/make-store conn))]
    (doseq [player ["ash" "misty"]]
      (trainer/create-trainer! s {:chat-id "chat" :player-id player}))
    {:client client :conn conn :store s :dir dir}))

(defn read! [s player slot] (missions/read-missions s "chat" player slot opts))
(defn prepare! [s] (missions/prepare-missions! s (writer/freeze-writer s "chat" "ash") opts))
(defn seed! [conn attr value]
  ;; Apenas fixture: não existe endpoint público para escrever progresso/prêmios.
  (d/transact conn {:tx-data [[:db/add [:save/key "chat|ash|a"] attr (pr-str value)]]}))

(deftest exact-master-catalog
  (is (= [["selvagens" 3 2 3 nil nil nil]
          ["capturas" 2 3 4 1 nil nil]
          ["vitorias" 1 3 3 1 1 1]
          ["presentes" 2 2 3 nil nil nil]]
         (mapv #(mapv % [:id :meta :xp :pokebolas :grandes :ultras :catalisadores]) catalog/daily)))
  (is (= [["ginasios" 2 60 8 4 2 1] ["tipos" 5 50 6 3 1 1]
          ["pvp" 3 60 8 4 2 1] ["presentes" 10 50 6 3 1 1]]
         (mapv #(mapv % [:id :meta :moedas :pokebolas :grandes :ultras :catalisadores]) catalog/weekly)))
  (is (= [6 4 2 4] (mapv :meta (catalog/scaled-daily 6))))
  (is (= [4 6 6 4] (mapv :xp (catalog/scaled-daily 6))))
  (is (= [6 8 6 6] (mapv :pokebolas (catalog/scaled-daily 6))))
  (is (= {:ultra-bola 10 :grande-bola 25 :none 65} (get-in catalog/reward-rules [:daily :ball-bonus])))
  (is (= 20 (get-in catalog/reward-rules [:daily :reviver-percent])))
  (is (= 20 (get-in catalog/reward-rules [:daily :trade-evolution-percent])))
  (is (= 1 (get-in catalog/reward-rules [:weekly :trade-evolution-items])))
  (is (= 1 (:catalisadores (nth (catalog/scaled-daily 11) 2))))
  (is (= (:meta (first (catalog/scaled-daily 1))) (:meta (first (catalog/scaled-daily 5))))))

(deftest read-is-pure-and-prepare-is-idempotent
  (let [{s :store :keys [conn]} (fresh)
        before (:t (d/db conn))
        r (read! s "ash" nil)]
    (is (= "a" (:save-id r)))
    (is (false? (get-in r [:daily :initialized])))
    (is (= 4 (count (get-in r [:daily :missions]))))
    (is (= 4 (count (get-in r [:weekly :missions]))))
    (is (every? #(= "available" (:estado %)) (get-in r [:daily :missions])))
    (is (= before (:t (d/db conn))))
    (is (true? (:claims-enabled r)))
    (let [prepared (prepare! s)
          after (:t (d/db conn))
          save-before (ports/get-save s "chat" "ash" "a")]
      (is (true? (get-in prepared [:daily :initialized])))
      (is (= prepared (prepare! s)))
      (is (= after (:t (d/db conn))))
      (is (= save-before (ports/get-save s "chat" "ash" "a"))))))

(deftest states-isolation-and-reopen
  (let [{s :store :keys [conn client]} (fresh)]
    (seed! conn :save/missoes-diarias-edn
           {"dia" "2026-10-20" "nivel" 1
            "progresso" {"selvagens" 1 "capturas" 2 "vitorias" 1}
            "resgatadas" ["vitorias"]})
    (seed! conn :save/missoes-semanais-edn
           {"semana" "2026-10-19" "ginasios" ["pedra" "agua"]
            "tipos" ["water" "water"] "pvp" 3 "presentes" 0 "resgatadas" ["pvp"]})
    (seed! conn :save/missoes-eventos-edn
           {"novo-recomeco-2026-09" {"progresso" {"selvagens" 3 "capturas" 1} "premiados" ["selvagens"]}})
    (let [r (read! s "ash" "a")
          reopened (trainer/bootstrap! (store/make-store (persistence/ensure-db! client "missions")))]
      (is (= ["in_progress" "completed" "rewarded" "available"]
             (mapv :estado (get-in r [:daily :missions]))))
      (is (= ["completed" "in_progress" "rewarded" "available"]
             (mapv :estado (get-in r [:weekly :missions]))))
      (is (= "ended" (get-in r [:events 0 :status])))
      (is (false? (get-in r [:events 0 :available])))
      (is (= "rewarded" (get-in r [:events 0 :missions 0 :estado])))
      (is (= r (read! reopened "ash" "a")))
      (doseq [[player slot] [["misty" "a"] ["ash" "b"]]]
        (is (every? #(zero? (:progresso %)) (get-in (read! s player slot) [:daily :missions]))))
      (is (= r (prepare! s)))
      (trainer/switch-save! s "chat" "ash" "b")
      (is (= "b" (:save-id (read! s "ash" nil))))
      (is (= r (read! s "ash" "a"))))))

(deftest renewal-and-frozen-level
  (let [{s :store :keys [conn]} (fresh)]
    (d/transact conn {:tx-data [[:db/add [:save/key "chat|ash|a"] :save/trainer-xp 50]]})
    (let [first-read (prepare! s)]
      (is (> (get-in first-read [:daily :level]) 1))
      (d/transact conn {:tx-data [[:db/add [:save/key "chat|ash|a"] :save/trainer-xp 150]]})
      (is (= (:daily first-read) (:daily (prepare! s))))
      (let [next (missions/prepare-missions! s (writer/freeze-writer s "chat" "ash")
                                             (assoc opts :instant (Instant/parse "2026-10-21T03:00:00Z")))]
        (is (> (get-in next [:daily :level]) (get-in first-read [:daily :level])))
        (is (= "2026-10-21" (get-in next [:daily :period])))))
    (is (= {:day "2026-10-18" :week "2026-10-12"}
           (catalog/periods (Instant/parse "2026-10-19T02:59:59Z") "America/Sao_Paulo")))
    (is (= {:day "2026-10-19" :week "2026-10-19"}
           (catalog/periods (Instant/parse "2026-10-19T03:00:00Z") "America/Sao_Paulo")))))

(deftest concurrent-preparation-and-stale-fence
  (let [{s :store :keys [conn]} (fresh)
        frozen (writer/freeze-writer s "chat" "ash")
        gate (promise)
        tasks (mapv (fn [_] (future @gate (missions/prepare-missions! s frozen opts))) (range 2))]
    (deliver gate true)
    (is (apply = (mapv deref tasks)))
    (is (= 1 (:revision (ports/get-save s "chat" "ash" "a"))))
    (trainer/switch-save! s "chat" "ash" "b")
    (is (= :stale_writer_fence
           (try (missions/prepare-missions! s frozen opts) nil
                (catch Exception ex (err/error-code ex)))))
    (is (false? (get-in (read! s "ash" "b") [:daily :initialized])))
    (is (= :player_not_found (try (read! s "nobody" "a") nil
                                  (catch Exception ex (err/error-code ex)))))))

(deftest migrations-are-idempotent
  (let [{:keys [conn]} (fresh)
        before (:t (d/db conn))]
    (schema/install! conn)
    (schema/install! conn)
    (is (= before (:t (d/db conn))))
    (doseq [attr [:save/missoes-diarias-edn :save/missoes-semanais-edn :save/missoes-eventos-edn]]
      (is (= :db.cardinality/one
             (:db/ident (:db/cardinality (d/pull (d/db conn) '[{:db/cardinality [:db/ident]}] attr))))))))

(deftest event-boundaries-never-renew
  (doseq [[time status] [["2026-09-27T02:59:59Z" "upcoming"]
                         ["2026-09-27T03:00:00Z" "active"]
                         ["2026-10-04T02:59:59Z" "active"]
                         ["2026-10-04T03:00:00Z" "ended"]
                         ["2027-09-27T03:00:00Z" "ended"]]]
    (let [r (catalog/snapshot {} {} {} 1 (Instant/parse time) "America/Sao_Paulo")]
      (is (= status (get-in r [:events 0 :status])))
      (is (= [3 3] (mapv :meta (get-in r [:events 0 :missions]))))
      (is (= [{"pokebola" 10} {"grande-bola" 5 "reviver" 2}]
             (mapv :recompensas (get-in r [:events 0 :missions])))))))

(defn http [base path method body token]
  (let [builder (HttpRequest/newBuilder (URI/create (str base path)))
        _ (when token (.header builder "Authorization" (str "Bearer " token)))
        _ (if (= method "GET") (.GET builder)
              (.POST builder (HttpRequest$BodyPublishers/ofString (json/write-str body))))
        response (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (json/read-str (.body response))]))

(deftest http-query-and-explicit-prepare
  (let [{s :store :keys [conn]} (fresh)
        server (bridge/start! {:store s :port 0 :token "test"})
        base (:base-url server)
        path "/v1/missions?chatId=chat&playerId=ash"
        frozen (writer/freeze-writer s "chat" "ash")]
    (try
      (let [before (:t (d/db conn))
            [status body] (http base path "GET" nil "test")]
        (is (= 200 status))
        (is (= "ash" (get-in body ["data" "playerId"])))
        (is (= 4 (count (get-in body ["data" "daily" "missions"]))))
        (is (= before (:t (d/db conn)))))
      (is (= 401 (first (http base path "GET" nil nil))))
      (is (= 400 (first (http base "/v1/missions" "GET" nil "test"))))
      (is (= 404 (first (http base "/v1/missions?chatId=chat&playerId=nobody" "GET" nil "test"))))
      (is (= 405 (first (http base "/v1/missions" "POST" {} "test"))))
      (let [[status body] (http base "/v1/missions/prepare" "POST"
                                {:chatId "chat" :playerId "ash" :saveId "a"
                                 :fenceGeneration (:fence-generation frozen)} "test")]
        (is (= 200 status))
        (is (true? (get-in body ["data" "daily" "initialized"])))
        (is (true? (get-in body ["data" "claimsEnabled"]))))
      (is (= 404 (first (http base "/v1/missions/claim" "POST" {} "test"))))
      (finally (bridge/stop! server)))))

(defn- restart-phase! [phase dir]
  (let [client (persistence/client {:system "a431-restart" :storage-dir dir})
        conn (persistence/ensure-db! client "restart")
        s (trainer/bootstrap! (store/make-store conn))]
    (when (= phase "write")
      (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
      (prepare! s)
      (seed! conn :save/missoes-diarias-edn
             {"dia" "2026-10-20" "nivel" 6 "progresso" {"capturas" 2} "resgatadas" []}))
    (let [r (read! s "ash" "a")]
      (when-not (and (= 6 (get-in r [:daily :level]))
                     (= 2 (get-in r [:daily :missions 1 :progresso]))
                     (= "ended" (get-in r [:events 0 :status])))
        (throw (ex-info "restart-state-mismatch" {:snapshot r}))))))

(deftest persists-across-independent-jvms
  (let [dir (str (Files/createTempDirectory "odisseu-a431-restart-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [phase ["write" "read"]]
      (let [command [(str (System/getProperty "java.home") "/bin/java")
                     "-Xmx256m" "-cp" (System/getProperty "java.class.path")
                     "clojure.main" "-m" "pokemon.tests.missions-a431-test" phase dir]
            process (.start (.redirectErrorStream (ProcessBuilder. ^java.util.List command) true))
            output (slurp (.getInputStream process))]
        (is (= 0 (.waitFor process)) output)))))

(defn -main [& [phase dir]]
  (if phase
    (do (restart-phase! phase dir) (shutdown-agents) (System/exit 0))
    (let [result (clojure.test/run-tests 'pokemon.tests.missions-a431-test)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
