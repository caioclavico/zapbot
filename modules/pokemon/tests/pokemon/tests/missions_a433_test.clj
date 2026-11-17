(ns pokemon.tests.missions-a433-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.mission-economy :as economy]
            [pokemon.application.mission-queries :as missions]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.mission-rewards :as rewards]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.mission-economy :as storage]
            [pokemon.infrastructure.datomic.mission-events :as events]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.migration.receipt-a433 :as receipt-audit]
            [pokemon.tests.missions-a431-test :as foundation]
            [pokemon.tests.missions-a432-test :as progress])
  (:import [java.nio.file Files]))

(def day "2026-10-20")
(def week "2026-10-19")
(def daily-complete {"dia" day "nivel" 1 "progresso"
                     {"selvagens" 3 "capturas" 2 "vitorias" 1 "presentes" 2} "resgatadas" []})
(def weekly-complete {"semana" week "ginasios" ["pedra" "agua"]
                      "tipos" ["water" "fire" "grass" "normal" "flying"]
                      "pvp" 3 "presentes" 10 "resgatadas" []})
(defn w [s & [player]] (writer/freeze-writer s "chat" (or player "ash")))
(defn seed [s player item qty]
  (reward/admin-seed-reward! s {:operation-id (str "seed-" player "-" item) :chat-id "chat"
                               :player-id player :save-id "a" :item {:item-id item :qty qty}}))
(defn qty [s player slot item]
  (or (some #(when (= item (:item-id %)) (:qty %)) (ports/list-inventory s "chat" player slot)) 0))
(defn claim [s kind op & [period writer]]
  (economy/claim! s (or writer (w s)) kind
                  {:operation-id op :period (or period (if (= kind :daily) day week))}))
(defn gift [s target op & [player]]
  (economy/send-gift! s (w s player) {:operation-id op :target-player-id target}))
(defn pending [s op] (economy/collect-pending! s (w s) {:operation-id op}))
(defn error-code [f] (try (f) nil (catch Exception ex (or (err/error-code ex) :exception))))
(defmacro deterministic [& body]
  `(binding [events/*clock* (constantly progress/instant) storage/*rand-int* (fn [n#] (dec n#))]
     ~@body))

(deftest master-reward-pool-and-probabilities
  (is (= ["revestimento-metalico" "escama-dragao" "upgrade" "protetor" "pedra-rei"
          "eletrizador" "magmarizador" "tecido-ceifador" "escama-prisma" "chicote-doce" "sache-perfumado"]
         (:trade-evolution-pool catalog/reward-rules)))
  (doseq [item (:trade-evolution-pool catalog/reward-rules)]
    (is (= :missoes (:nao-venda (shop/item item)))))
  (doseq [[roll item qty] [[0 "ultra-bola" 1] [9 "ultra-bola" 1]
                          [10 "grande-bola" 2] [34 "grande-bola" 2]
                          [35 "pokebola" 3] [99 "pokebola" 3]]]
    (is (= {item qty} (rewards/gift roll))))
  (doseq [[roll bonus] [[0 "ultra-bola"] [9 "ultra-bola"] [10 "grande-bola"]
                        [34 "grande-bola"] [35 nil] [99 nil]]]
    (let [state (assoc daily-complete "progresso" {"selvagens" 3})
          result (rewards/daily state (fn [[_ type] n] (if (= type :ball) roll (dec n))))]
      (is (= (cond-> {"pokebola" 3} bonus (assoc bonus 1)) (:items result)))))
  (doseq [index (range 11)]
    (let [draw (fn [key n] (if (or (= key :weekly-evolution-index)
                                  (= (last key) :evolution-index)) index 0))
          result (rewards/daily daily-complete draw)
          item (nth (:trade-evolution-pool catalog/reward-rules) index)]
      (is (= 4 (get-in result [:items item])))
      (is (= 4 (get-in result [:items "reviver"])))
      (is (= 5 (get-in result [:items "ultra-bola"])))
      (is (= 1 (get-in (rewards/weekly weekly-complete draw) [:items item]))))))

(deftest operation-receipt-is-a-global-non-upserting-key
  (let [{:keys [conn]} (progress/fresh)
        db (d/db conn)]
    (is (= :db.unique/value
           (:db/ident (:db/unique (d/pull db '[{:db/unique [:db/ident]}]
                                           :operation-receipt/id)))))
    (d/transact conn {:tx-data [{:operation-receipt/id "receipt-global-key"
                                 :operation-receipt/payload-hash "first"}]})
    (is (thrown? Exception
                 (d/transact conn {:tx-data [{:operation-receipt/id "receipt-global-key"
                                              :operation-receipt/payload-hash "second"}]})))
    (is (= "first" (:operation-receipt/payload-hash
                     (d/pull (d/db conn) '[*]
                             [:operation-receipt/id "receipt-global-key"]))))))

(deftest receipt-migration-audit-is-read-only-and-reports-safe-state
  (let [{:keys [conn]} (progress/fresh)
        before (:t (d/db conn))
        audit (receipt-audit/report conn)]
    (is (= :db.unique/value (:receipt-uniqueness audit)))
    (is (empty? (:duplicates audit)))
    (is (:migration-applied audit))
    (is (:safe-state? audit))
    (is (= before (:t (d/db conn))))))

(deftest gift-atomic-existing-recipient-and-retry
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)]
     (seed s "ash" "cartao-presente" 2)
     (let [first (gift s "misty" "gift-1")
           again (gift s "misty" "gift-1")]
       (is (= first (assoc again :duplicate false)))
       (is (true? (:sent first)))
       (is (= {"pokebola" 3} (:items first)))
       (is (= 1 (qty s "ash" "a" "cartao-presente")))
       (is (= 23 (qty s "misty" "a" "pokebola")))
       (is (= 1 (progress/daily s "ash" 3)))
       (is (= 1 (progress/weekly s "ash" 3)))
       (is (= 0 (progress/daily s "misty" 3)))
       (is (= 1 (progress/event-count conn)))
       (is (= first (get-in (tx/get-operation s "gift-1" "chat" "ash") [:summary :result])))))))

(deftest invalid-gifts-never-consume-or-progress
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)]
     (doseq [target [nil "" "ash" 123 "bad|identity"]]
       (is (= :invalid_gift_target (error-code #(gift s target "bad")))))
     (is (= :insufficient_item (error-code #(gift s "gary" "no-card"))))
     (is (nil? (ports/get-player s "chat" "gary")))
     (is (= 0 (progress/event-count conn)))
     (is (= 0 (progress/daily s "ash" 3))))))

(deftest new-recipient-and-concurrent-creation-preserve-onboarding
  (deterministic
   (let [{s :store} (progress/fresh)
         gate (promise)]
     (doseq [player ["ash" "misty"]] (seed s player "cartao-presente" 1))
     (let [a (future @gate (gift s "gary" "gift-a"))
           b (future @gate (gift s "gary" "gift-b" "misty"))]
       (deliver gate true)
       (is (every? :sent [@a @b]))
       (is (= 6 (qty s "gary" "a" "pokebola")))
       (is (= 0 (qty s "gary" "b" "pokebola")))
       (is (= 1 (progress/daily s "ash" 3)))
       (is (= 1 (progress/daily s "misty" 3)))
       (is (empty? (ports/list-collection s "chat" "gary" "a"))))
     (tx/start-journey! s {:chat-id "chat" :player-id "gary" :operation-id "start-gary"})
     (tx/start-journey! s {:chat-id "chat" :player-id "gary" :operation-id "start-gary"})
     (is (= 16 (qty s "gary" "a" "pokebola")))
     (is (empty? (ports/list-collection s "chat" "gary" "a"))))))

(deftest concurrent-gifts-same-and-different-operation
  (deterministic
   (doseq [same? [true false]]
     (let [{s :store :keys [conn]} (progress/fresh)
           gate (promise)]
       (seed s "ash" "cartao-presente" 2)
       (let [tasks (mapv (fn [i] (future @gate (gift s "misty" (if same? "same" (str "gift-" i))))) (range 2))]
         (deliver gate true)
         (let [results (mapv deref tasks)]
           (is (= (if same? 1 2) (count (remove :duplicate results)))))
         (is (= (if same? 1 2) (progress/event-count conn)))
         (is (= (if same? 1 0) (qty s "ash" "a" "cartao-presente")))
         (is (= (if same? 23 26) (qty s "misty" "a" "pokebola"))))))))

(deftest simultaneous-operation-id-collision-is-owner-protected
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)
         gate (promise)]
     (doseq [player ["ash" "misty"]] (seed s player "cartao-presente" 1))
     (let [tasks (mapv (fn [player] (future @gate
                                          (try (gift s "gary" "collision" player)
                                               (catch Exception ex {:error (err/error-code ex)})))) ["ash" "misty"])]
       (deliver gate true)
       (let [results (mapv deref tasks)]
         (is (= 1 (count (filter :sent results))))
         (is (= 1 (count (filter :error results)))))
       (is (= 3 (qty s "gary" "a" "pokebola")))
       (is (= 1 (+ (qty s "ash" "a" "cartao-presente") (qty s "misty" "a" "cartao-presente"))))
       (is (= 1 (progress/event-count conn)))))))

(deftest daily-claim-exact-once-and-empty-result-is-persisted
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)]
     (is (true? (:empty (claim s :daily "incomplete"))))
     (foundation/seed! conn :save/missoes-diarias-edn daily-complete)
     (is (true? (:empty (claim s :daily "incomplete"))))
     (let [result (claim s :daily "daily")]
       (is (= ["selvagens" "capturas" "vitorias" "presentes"] (:ids result)))
       (is (= 10 (:xp result)))
       (is (= {"pokebola" 13 "grande-bola" 2 "ultra-bola" 1 "catalisador-evolutivo" 1} (:items result)))
       (is (= 33 (qty s "ash" "a" "pokebola")))
       (is (= 10 (:xp-missoes (ports/get-save s "chat" "ash" "a"))))
       (is (= result (assoc (claim s :daily "daily") :duplicate false)))
       (is (true? (:empty (claim s :daily "already-claimed"))))
       (is (every? #(= "rewarded" (:estado %)) (get-in (progress/snapshot s "ash" "a") [:daily :missions])))))))

(deftest weekly-claim-values-and-one-evolution-item-per-operation
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)]
     (foundation/seed! conn :save/missoes-semanais-edn weekly-complete)
     (let [result (claim s :weekly "weekly")]
       (is (= 220 (:coins result)))
       (is (= {"pokebola" 28 "grande-bola" 14 "ultra-bola" 6
               "catalisador-evolutivo" 4 "sache-perfumado" 1} (:items result)))
       (is (= 220 (:coins (ports/get-save s "chat" "ash" "a"))))
       (is (= 0 (:xp-missoes (ports/get-save s "chat" "ash" "a"))))
       (is (= result (assoc (claim s :weekly "weekly") :duplicate false)))
       (is (true? (:empty (claim s :weekly "weekly-empty"))))
       (is (= 220 (:coins (ports/get-save s "chat" "ash" "a"))))))))

(deftest concurrent-claims-and-progress-do-not-lose-or-double-pay
  (deterministic
   (doseq [same? [true false]]
     (let [{s :store :keys [conn]} (progress/fresh)
           gate (promise)]
       (foundation/seed! conn :save/missoes-diarias-edn
                         (assoc daily-complete "progresso" {"capturas" 2}))
       (let [claims (mapv (fn [i] (future @gate (claim s :daily (if same? "same" (str "claim-" i))))) (range 2))
             capture (future @gate (progress/caught! s "capture-concurrent"))]
         (deliver gate true)
         (let [results (mapv deref claims)]
           (is (= 3 (reduce + (map :xp (remove :duplicate results))))))
         (is (true? (:capturou? @capture)))
         (is (= 3 (:xp-missoes (ports/get-save s "chat" "ash" "a"))))
         (is (= 2 (progress/daily s "ash" 1)))
         (is (= "rewarded" (get-in (progress/snapshot s "ash" "a") [:daily :missions 1 :estado]))))))))

(deftest full-bag-pending-recovery-and-unknown-pending-preserved
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)]
     (seed s "ash" "pocao" 30)
     (foundation/seed! conn :save/missoes-diarias-edn (assoc daily-complete "progresso" {"capturas" 2}))
     (let [result (claim s :daily "bag-full")]
       (is (empty? (:added result)))
       (is (= {"pokebola" 4 "grande-bola" 1} (:pending result))))
     (foundation/seed! conn :save/recompensas-pendentes-edn {"pokebola" 4 "grande-bola" 1 "future-item" 2})
     (is (true? (:empty (pending s "no-space"))))
     (d/transact conn {:tx-data [[:db/add [:save/key "chat|ash|a"] :save/expansoes-mochila-50 1]]})
     (let [result (pending s "space")]
       (is (= {"pokebola" 4 "grande-bola" 1} (:added result)))
       (is (= {"future-item" 2} (:pending result)))
       (is (= result (assoc (pending s "space") :duplicate false)))
       (is (= 24 (qty s "ash" "a" "pokebola")))))))

(deftest gift-target-full-bag-and-active-save-selection
  (deterministic
   (let [{s :store} (progress/fresh)]
     (seed s "ash" "cartao-presente" 2)
     (seed s "misty" "pocao" 30)
     (let [result (gift s "misty" "target-full")]
       (is (= {"pokebola" 3} (:pending result)))
       (is (= 20 (qty s "misty" "a" "pokebola"))))
     (trainer/switch-save! s "chat" "misty" "b")
     (is (= "b" (:target-save-id (gift s "misty" "target-b"))))
     (is (= 3 (qty s "misty" "b" "pokebola")))
     (is (= "a" (:target-save-id (gift s "misty" "target-full"))))
     (is (= 3 (qty s "misty" "b" "pokebola"))))))

(deftest owner-save-period-and-historical-isolation
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)]
     (foundation/seed! conn :save/missoes-diarias-edn (assoc daily-complete "progresso" {"capturas" 2}))
     (let [result (claim s :daily "owner") frozen (w s)]
       (is (= :operation_forbidden (error-code #(claim s :daily "owner" day (w s "misty")))))
       (is (= :receipt_payload_mismatch (error-code #(claim s :weekly "owner" week))))
       (is (= :mission_period_conflict (error-code #(claim s :daily "old" "2026-10-19"))))
       (is (= :mission_period_conflict (error-code #(claim s :weekly "old-week" "2026-10-12"))))
       (trainer/switch-save! s "chat" "ash" "b")
       (is (= :stale_writer_fence (error-code #(claim s :daily "stale" day frozen))))
       (is (= result (assoc (claim s :daily "owner" day frozen) :duplicate false)))
       (is (true? (:empty (claim s :daily "new-save"))))
       (is (= 0 (:xp-missoes (ports/get-save s "chat" "ash" "b"))))
       (is (= "ended" (get-in (progress/snapshot s "ash" "a") [:events 0 :status])))))))

(deftest failure-before-and-after-commit-recovers-result
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh) transact d/transact]
     (seed s "ash" "cartao-presente" 1)
     (foundation/seed! conn :save/missoes-diarias-edn (assoc daily-complete "progresso" {"capturas" 2}))
     (with-redefs [d/transact (fn [& _] (throw (ex-info "before" {})))]
       (is (some? (error-code #(gift s "gary" "before")))))
     (is (nil? (ports/get-player s "chat" "gary")))
     (is (= 1 (qty s "ash" "a" "cartao-presente")))
     (with-redefs [d/transact (fn [conn request]
                               (let [result (transact conn request)]
                                 (when (some #(and (map? %) (:operation-receipt/id %)) (:tx-data request))
                                   (throw (ex-info "after-economic-commit" {})))
                                 result))]
       (is (true? (:duplicate (gift s "gary" "after-gift"))))
       (is (true? (:duplicate (claim s :daily "after-claim")))))
     (is (= 3 (qty s "gary" "a" "pokebola")))
     (is (= 3 (:xp-missoes (ports/get-save s "chat" "ash" "a"))))
     (is (true? (:duplicate (claim s :daily "after-claim")))))))

(deftest http-contract-and-receipt-reconciliation
  (let [{s :store :keys [conn]} (progress/fresh)
        server (bridge/start! {:store s :port 0 :token "test"}) base (:base-url server)
        body {:chatId "chat" :playerId "ash" :saveId "a" :fenceGeneration (:fence-generation (w s))
              :operationId "http-claim" :period (get-in (missions/read-missions s "chat" "ash" nil {}) [:daily :period])}]
    (try
      (foundation/seed! conn :save/missoes-diarias-edn (assoc daily-complete "dia" (:period body)))
      (let [[status response] (foundation/http base "/v1/missions/claim/daily" "POST" body "test")
            [again retry] (foundation/http base "/v1/missions/claim/daily" "POST" body "test")
            [_ receipt] (foundation/http base "/v1/operations/http-claim?chatId=chat&playerId=ash" "GET" nil "test")]
        (is (= 200 status again))
        (is (= (get response "data") (assoc (get retry "data") "duplicate" false)))
        (is (= (get response "data") (get-in receipt ["data" "summary" "result"])))
        (is (some #(= "grande-bola" (get % "itemId")) (get-in response ["data" "items"]))))
      (doseq [field ["items" "coins" "roll" "timezone" "targetSaveId"]]
        (is (= 400 (first (foundation/http base "/v1/missions/claim/daily" "POST" (assoc body field 999) "test")))))
      (is (= 401 (first (foundation/http base "/v1/missions/claim/daily" "POST" body nil))))
      (is (= 409 (first (foundation/http base "/v1/operations/http-claim?chatId=chat&playerId=misty" "GET" nil "test"))))
      (is (= 200 (first (foundation/http base "/v1/missions/rewards?chatId=chat&playerId=ash" "GET" nil "test"))))
      (finally (bridge/stop! server)))))

(deftest recipient-initialization-failure-is-recoverable-without-payment
  (deterministic
   (let [{s :store} (progress/fresh) transact d/transact]
     (seed s "ash" "cartao-presente" 1)
     (with-redefs [d/transact (fn [conn request]
                               (transact conn request)
                               (throw (ex-info "after-empty-identity" {})))]
       (is (some? (error-code #(gift s "gary" "provision-retry")))))
     (is (= 1 (qty s "ash" "a" "cartao-presente")))
     (is (= 0 (progress/daily s "ash" 3)))
     (is (true? (:sent (gift s "gary" "provision-retry"))))
     (is (= 3 (qty s "gary" "a" "pokebola")))
     (is (= 0 (qty s "ash" "a" "cartao-presente"))))))

(deftest concurrent-claim-id-collision-cannot-pay-two-owners
  (deterministic
   (let [{s :store :keys [conn]} (progress/fresh)
         transact d/transact barrier (java.util.concurrent.CyclicBarrier. 2)]
     (doseq [player ["ash" "misty"]]
       (d/transact conn {:tx-data [[:db/add [:save/key (str "chat|" player "|a")]
                                   :save/missoes-diarias-edn (pr-str daily-complete)]]}))
     (with-redefs [d/transact (fn [conn request]
                               (when (some #(= "collision-claim" (:operation-receipt/id %))
                                           (filter map? (:tx-data request)))
                                 (.await barrier 10 java.util.concurrent.TimeUnit/SECONDS))
                               (transact conn request))]
       (let [tasks (mapv (fn [player]
                           (future (try (claim s :daily "collision-claim" day (w s player))
                                        (catch Exception ex {:error (err/error-code ex)})))) ["ash" "misty"])
             results (mapv deref tasks)]
         (is (= 1 (count (filter :error results))))
         (is (= 10 (reduce + 0 (keep :xp results))))))
     (is (= 10 (+ (:xp-missoes (ports/get-save s "chat" "ash" "a"))
                  (:xp-missoes (ports/get-save s "chat" "misty" "a"))))))))

(deftest http-gift-weekly-and-pending-contracts
  (let [{s :store :keys [conn]} (progress/fresh)
        server (bridge/start! {:store s :port 0 :token "test"}) base (:base-url server)
        body {:chatId "chat" :playerId "ash" :saveId "a" :fenceGeneration (:fence-generation (w s))}
        period (get-in (missions/read-missions s "chat" "ash" nil {}) [:weekly :period])]
    (try
      (seed s "ash" "cartao-presente" 1)
      (let [request (assoc body :operationId "http-gift" :targetPlayerId "gary")
            [status response] (foundation/http base "/v1/gifts/send" "POST" request "test")
            [_ retry] (foundation/http base "/v1/gifts/send" "POST" request "test")]
        (is (= 200 status))
        (is (true? (get-in response ["data" "sent"])))
        (is (= (get response "data") (assoc (get retry "data") "duplicate" false))))
      (foundation/seed! conn :save/missoes-semanais-edn (assoc weekly-complete "semana" period))
      (let [[status response] (foundation/http base "/v1/missions/claim/weekly" "POST"
                                              (assoc body :operationId "http-weekly" :period period) "test")]
        (is (= 200 status))
        (is (= 220 (get-in response ["data" "coins"])))
        (is (vector? (get-in response ["data" "pending"]))))
      (is (= 200 (first (foundation/http base "/v1/missions/pending/collect" "POST"
                                         (assoc body :operationId "http-pending") "test"))))
      (finally (bridge/stop! server)))))

(deftest recipient-switch-during-transfer-rejects-entire-gift
  (deterministic
   (let [{s :store} (progress/fresh) transact d/transact intercepted (atom false)]
     (seed s "ash" "cartao-presente" 1)
     (with-redefs [d/transact (fn [conn request]
                               (when (and (some #(= "gift-send" (:operation-receipt/kind %))
                                                (filter map? (:tx-data request)))
                                          (compare-and-set! intercepted false true))
                                 (trainer/switch-save! s "chat" "misty" "b"))
                               (transact conn request))]
       (is (= :gift_target_changed (error-code #(gift s "misty" "switch")))))
     (is (= 1 (qty s "ash" "a" "cartao-presente")))
     (is (= 20 (qty s "misty" "a" "pokebola")))
     (is (= 0 (qty s "misty" "b" "pokebola")))
     (is (= 0 (progress/daily s "ash" 3))))))

(defn restart-phase! [phase dir]
  (deterministic
   (let [client (persistence/client {:system "a433-restart" :storage-dir dir})
         conn (persistence/ensure-db! client "economy")
         s (trainer/bootstrap! (store/make-store conn))]
     (when (= phase "write")
       (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
       (seed s "ash" "cartao-presente" 1)
       (foundation/seed! conn :save/missoes-diarias-edn
                         (assoc daily-complete "progresso" {"capturas" 2}))
       (gift s "gary" "restart-gift")
       (let [transact d/transact]
         (with-redefs [d/transact (fn [conn request]
                                   (transact conn request)
                                   (when (some #(= "restart-claim" (:operation-receipt/id %))
                                               (filter map? (:tx-data request)))
                                     (System/exit 0)))]
           (claim s :daily "restart-claim"))))
     (let [result (claim s :daily "restart-claim")
           gift-result (gift s "gary" "restart-gift")]
       (when-not (and (:duplicate result) (:duplicate gift-result) (= 3 (:xp result))
                      (= 3 (qty s "gary" "a" "pokebola")))
         (throw (ex-info "restart-economy-mismatch" {:result result})))))))

(deftest payment-and-gift-survive-independent-jvms
  (let [dir (str (Files/createTempDirectory "odisseu-a433-restart-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [phase ["write" "read"]]
      (let [command [(str (System/getProperty "java.home") "/bin/java") "-Xmx256m"
                     "-cp" (System/getProperty "java.class.path") "clojure.main"
                     "-m" "pokemon.tests.missions-a433-test" phase dir]
            process (.start (.redirectErrorStream (ProcessBuilder. ^java.util.List command) true))
            output (slurp (.getInputStream process))]
        (is (= 0 (.waitFor process)) output)))))

(defn -main [& [phase dir]]
  (if phase
    (do (restart-phase! phase dir) (shutdown-agents) (System/exit 0))
    (let [result (clojure.test/run-tests 'pokemon.tests.missions-a433-test)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
