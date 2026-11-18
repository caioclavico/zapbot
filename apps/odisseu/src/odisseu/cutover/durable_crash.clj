(ns odisseu.cutover.durable-crash
  "M8.4 — falhas em cada fronteira do protocolo (sintético, storages descartáveis)."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.durable-op :as dop]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.cutover.recovery :as recovery]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.time Instant]))

(defn- assert-disposable! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m84_path_blocked" {:path p}))))

(defn- wipe! [p]
  (let [f (io/file (str p))]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))))

(defn- ctx-like [id]
  (let [root (str ".datomic-local/m84-" id)
        jdir (str root "-journal")
        mdir (str root "-maint")]
    (assert-disposable! root)
    (wipe! jdir)
    (wipe! mdir)
    (.mkdirs (io/file jdir))
    {:jdir jdir :mdir mdir :root root}))

(defn- fake-datomic
  "SoT em memória + receipts por operation-id (simula Datomic Local)."
  []
  (let [state (atom {:receipts {} :coins 0 :applied []})]
    {:state state
     :apply!
     (fn [{:keys [operation-id coins-delta]}]
       (let [ph (journal/payload-hash {:coins-delta coins-delta})
             prev (get-in @state [:receipts operation-id])]
         (cond
           (and prev (= ph (:payload-hash prev)))
           {:duplicate true :coins (:coins @state)}

           prev
           (throw (ex-info "receipt_payload_mismatch"
                           {:operation-id operation-id}))

           :else
           (do
             (swap! state
                    (fn [s]
                      (-> s
                          (update :coins + (long coins-delta))
                          (update :applied conj operation-id)
                          (assoc-in [:receipts operation-id]
                                    {:payload-hash ph :coins-delta coins-delta}))))
             {:duplicate false :coins (:coins @state)}))))}))

(defn- base-op [id delta]
  {:operation-id id
   :domain :pokemon
   :kind :coins-delta
   :scope {:chat "c@g.us" :player "p@c.us" :save "a"}
   :payload {:coins-delta delta}
   :fence 0
   :replayable? true})

(defn- summarize [scenario-id result coins applied journal-phases prepared-orphans maintenance]
  {:scenario scenario-id
   :result result
   :status (:status result)
   :http-success? (:http-success? result)
   :coins coins
   :applied applied
   :journal-phases journal-phases
   :prepared-orphans prepared-orphans
   :maintenance maintenance})

(defn- run-one
  [scenario-id opts]
  (let [{:keys [jdir mdir]} (ctx-like scenario-id)
        dt (fake-datomic)
        op (base-op (str "m84-" scenario-id) 10)
        result
        (try
          (dop/execute!
           (merge {:policy :strict
                   :journal-dir jdir
                   :maint-dir mdir
                   :datomic-fn #((:apply! dt) {:operation-id (:operation-id op)
                                               :coins-delta 10})
                   :op op}
                  opts))
          (catch Exception ex
            {:status :exception
             :http-success? false
             :error (.getMessage ex)
             :ex-data (ex-data ex)}))]
    (-> (summarize scenario-id result
                   (:coins @(:state dt))
                   (:applied @(:state dt))
                   (mapv :phase (try (journal/list-entries jdir) (catch Exception _ [])))
                   (try (journal/prepared-without-durable jdir) (catch Exception _ []))
                   (recovery/maintenance-status mdir))
        (assoc :jdir jdir :mdir mdir :dt dt))))

(defn scenario-before-journal-write!
  "Crash antes de escrever prepare."
  []
  (let [r (run-one "before-prep"
                   {:hooks {:before-prepare #(throw (ex-info "crash_before_prepare" {}))}})]
    (assoc r :ok? (and (= :exception (:status r))
                       (zero? (:coins r))
                       (empty? (:journal-phases r))))))

(defn scenario-during-journal-write!
  []
  (let [r (run-one "during-prep"
                   {:hooks {:during-write #(throw (ex-info "crash_during_write" {}))}})]
    (assoc r :ok? (and (= :exception (:status r))
                       (zero? (:coins r))))))

(defn scenario-after-prepare-before-datomic!
  []
  (let [r (run-one "prep-no-datomic"
                   {:hooks {:before-datomic #(throw (ex-info "crash_before_datomic" {}))}})]
    (assoc r :ok? (boolean
                   (and (= :exception (:status r))
                        (zero? (:coins r))
                        (some #{:prepared} (:journal-phases r))
                        (seq (:prepared-orphans r)))))))

(defn scenario-during-datomic!
  []
  (let [r (run-one "during-datomic"
                   {:hooks {:during-datomic #(throw (ex-info "crash_during_datomic" {}))}})]
    (assoc r :ok? (boolean
                   (and (= :exception (:status r))
                        (zero? (:coins r))
                        (some #{:aborted} (:journal-phases r)))))))

(defn scenario-after-datomic-before-journal-confirm!
  "Datomic OK; crash antes do append durable → strict → sem HTTP success."
  []
  (let [r (run-one "after-datomic"
                   {:hooks {:before-journal #(throw (ex-info "crash_before_journal_confirm" {}))}})]
    (assoc r :ok? (boolean
                   (and (= :durability_compromised (:status r))
                        (false? (:http-success? r))
                        (= 10 (:coins r))
                        (true? (get-in r [:maintenance :maintenance?]))
                        (some #{:uncertain} (:journal-phases r)))))))

(defn scenario-during-journal-confirm!
  []
  (let [n (atom 0)
        r (run-one "during-jconfirm"
                   {:hooks {:during-write
                            (fn []
                              ;; 1º = prepare; 2º = confirm durable
                              (when (= 1 (swap! n inc))
                                nil)
                              (when (>= @n 2)
                                (throw (ex-info "crash_during_journal_confirm" {}))))}})]
    (assoc r :ok? (and (= :durability_compromised (:status r))
                       (false? (:http-success? r))
                       (= 10 (:coins r))
                       (true? (get-in r [:maintenance :maintenance?]))))))

(defn scenario-both-ok-before-http!
  "Journal+Datomic OK; crash na resposta → retry idempotente."
  []
  (let [r (run-one "before-http"
                   {:hooks {:before-http #(throw (ex-info "crash_before_http" {}))}})
        ;; SoT já tem a op; retry com receipt
        retry
        (dop/execute!
         {:policy :strict
          :journal-dir (:jdir r)
          :maint-dir (str (:mdir r) "-retry")
          :datomic-fn (fn [] {:duplicate true :coins 10})
          :op (base-op "m84-before-http" 10)})]
    (assoc r :ok? (boolean
                   (and (= :exception (:status r))
                        (= 10 (:coins r))
                        (some #{:journal-durable} (:journal-phases r))
                        (true? (:http-success? retry))
                        (true? (:duplicate? retry)))))))

(defn scenario-after-http-ack!
  []
  (let [r (run-one "after-http" {})]
    (assoc r :ok? (boolean
                   (and (= :ok (:status r))
                        (true? (:http-success? r))
                        (= 10 (:coins r))
                        (some #{:journal-durable} (:journal-phases r)))))))

(defn scenario-replay-no-duplicate-apply!
  []
  (let [{:keys [jdir mdir]} (ctx-like "replay")
        dt (fake-datomic)
        op (base-op "m84-replay" 7)
        r1 (dop/execute!
            {:policy :strict :journal-dir jdir :maint-dir mdir
             :datomic-fn #((:apply! dt) {:operation-id "m84-replay" :coins-delta 7})
             :op op})
        r2 ((:apply! dt) {:operation-id "m84-replay" :coins-delta 7})
        r3 (dop/execute!
            {:policy :strict :journal-dir jdir :maint-dir mdir
             :datomic-fn #((:apply! dt) {:operation-id "m84-replay" :coins-delta 7})
             :op op})]
    {:scenario :replay
     :status (:status r1)
     :http-success? (:http-success? r1)
     :coins (:coins @(:state dt))
     :ok? (and (true? (:http-success? r1))
               (true? (:duplicate r2))
               (true? (:duplicate? r3))
               (= 7 (:coins @(:state dt))))}))

(defn scenario-compact-crash!
  []
  (let [{:keys [jdir]} (ctx-like "compact")
        _ (doseq [i (range 3)]
            (journal/append-phase!
             jdir
             {:operation-id (str "m84-c-" i)
              :domain :pokemon :kind :coins-delta
              :scope {:chat "c@g.us"}
              :payload {:coins-delta i}
              :phase :journal-durable
              :datomic-confirmed? true}))
        threw?
        (try
          (journal/compact! jdir 1
                            {:during-compact #(throw (ex-info "crash_compact" {}))})
          false
          (catch Exception _ true))
        remaining (count (journal/list-entries jdir))
        archive-n (count (.listFiles (io/file jdir "archive")))]
    {:scenario :compact-crash
     :threw? threw?
     :remaining remaining
     :archived archive-n
     :readable? (try (do (journal/list-entries jdir) true) (catch Exception _ false))
     :ok? (and threw? (pos? remaining) (try (do (journal/list-entries jdir) true)
                                            (catch Exception _ false)))}))

(defn scenario-partial-corruption!
  []
  (let [{:keys [jdir mdir]} (ctx-like "corrupt")
        _ (journal/append-phase!
           jdir
           {:operation-id "m84-good"
            :domain :pokemon :kind :coins-delta
            :scope {:chat "c@g.us"}
            :payload {:n 1}
            :phase :journal-durable
            :datomic-confirmed? true})
        _ (journal/corrupt-last! jdir)
        read-fail? (try (journal/list-entries jdir) false (catch Exception _ true))
        maint (journal/fail-closed! mdir "journal-corrupt")]
    {:scenario :partial-corruption
     :status :corrupt
     :http-success? false
     :ok? (and read-fail? (:maintenance? maint))}))

(defn scenario-payload-mismatch!
  []
  (let [{:keys [jdir mdir]} (ctx-like "mismatch")
        dt (fake-datomic)
        _ (dop/execute!
           {:policy :strict :journal-dir jdir :maint-dir mdir
            :datomic-fn #((:apply! dt) {:operation-id "m84-same" :coins-delta 5})
            :op (base-op "m84-same" 5)})
        threw?
        (try
          (dop/execute!
           {:policy :strict :journal-dir jdir :maint-dir mdir
            :datomic-fn #((:apply! dt) {:operation-id "m84-same" :coins-delta 9})
            :op (base-op "m84-same" 9)})
          false
          (catch Exception ex
            (boolean (or (= "journal_operation_id_payload_mismatch" (.getMessage ex))
                         (= :journal_operation_id_payload_mismatch
                            (:type (ex-data ex)))
                         (re-find #"payload_mismatch|journal_operation_id"
                                  (str (.getMessage ex) " " (ex-data ex)))))))]
    {:scenario :payload-mismatch
     :coins (:coins @(:state dt))
     :ok? (and threw? (= 5 (:coins @(:state dt))))}))

(defn run-all!
  [{:keys [out-dir] :or {out-dir "target/m8-4"}}]
  (let [root (io/file (persistence/project-root) out-dir)
        _ (.mkdirs root)
        results
        [(scenario-before-journal-write!)
         (scenario-during-journal-write!)
         (scenario-after-prepare-before-datomic!)
         (scenario-during-datomic!)
         (scenario-after-datomic-before-journal-confirm!)
         (scenario-during-journal-confirm!)
         (scenario-both-ok-before-http!)
         (scenario-after-http-ack!)
         (scenario-replay-no-duplicate-apply!)
         (scenario-compact-crash!)
         (scenario-partial-corruption!)
         (scenario-payload-mismatch!)]
        ok? (every? :ok? results)
        report
        {:milestone "M8.4"
         :at (str (Instant/now))
         :protocol dop/state-machine
         :coverage dop/domain-coverage
         :results (mapv #(select-keys % [:scenario :ok? :coins :journal-phases
                                         :status :http-success?])
                        results)
         :all-ok? ok?
         :stores-integrated? false
         :exactly-once-claimed? false
         :rpo-zero-claimed? false
         :rollback-recon-available? false
         :cutover-ready? false
         :ok? (and ok?
                   (false? (:wired-in-stores? dop/domain-coverage)))}]
    (spit (str (io/file root "report.edn")) (with-out-str (pprint/pprint report)))
    report))
