(ns a6-batch-rehearsal
  "Ensaio controlado A6.4 em Datomic descartável.

  Fluxo: auditar colisões → gates por candidato → backup/restore check →
  discover/confirm! só nos aprovados → idempotência → falha parcial simulada.
  Sem Cassandra, sem produção, sem decisões nos 9 pending."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.recovery-catalog :as catalog]
            [pokemon.application.recovery-economy :as eco]
            [pokemon.application.recovery-manifest :as man]
            [pokemon.application.recovery-reconcile :as rr]
            [pokemon.application.recovery-stable-id :as sid]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.account-shape :as shape]
            [pokemon.migration.export :as export]
            [pokemon.recovery :as recovery]))

(def ^:private import-token "a64-batch-rehearsal-token-not-for-prod")

(defn- read-edn [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- write-edn! [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data)))
  path)

(defn- g [m k]
  (when (map? m)
    (or (get m k) (get m (name k)) (get m (keyword (name k))))))

(defn assert-isolation!
  [storage-dir]
  (let [abs (.getCanonicalPath (io/file storage-dir))
        home (.getCanonicalPath (io/file (System/getProperty "user.home")))
        recovery (.getCanonicalPath (io/file home "odisseu-recovery"))]
    (when-not (str/starts-with? abs recovery)
      (throw (ex-info "rehearsal_storage_outside_odisseu_recovery" {:storage abs})))
    (when-not (str/includes? abs "rehearsal")
      (throw (ex-info "rehearsal_storage_must_include_rehearsal" {:storage abs})))
    (when (re-find #"(?i)odisseu-core-a56-datomic$|/data/datomic-local|production|prod-live" abs)
      (throw (ex-info "rehearsal_storage_looks_like_live" {:storage abs})))
    (doseq [k ["CASSANDRA_CONTACT_POINTS" "CASSANDRA_HOST" "RECOVERY_CASSANDRA_WRITE"
               "CASSANDRA_USERNAME" "CASSANDRA_PASSWORD"]]
      (when (System/getenv k)
        (throw (ex-info "rehearsal_cassandra_env_present" {:env k}))))
    {:ok true
     :storage abs
     :cassandra-env-cleared true
     :shares-live-volume false}))

(defn audit-v1-collisions
  "Audita native-collision e ambiguous em contas V1.
   Classifica grupos de núcleo:
   - :blocking-duplicate — ≥2 sem id distinto utilizável / ambiguous
   - :native-collision-distinct-ids — mesmo núcleo, ids nativos diferentes (impede síntese; nativos preservados se únicos por id)
   - :cross-location-same-id — mesmo id nativo repetido (raro)
   Contagens anonimizadas."
  [v1-accounts]
  (let [per-account
        (mapv (fn [{:keys [chatId playerId account]}]
                (let [c (sid/classify-account {:chat-id chatId :player-id playerId :account account})
                      rows (:rows c)
                      by-d (group-by :digest rows)
                      collision-groups
                      (->> by-d
                           (keep (fn [[d xs]]
                                   (when (> (count xs) 1)
                                     (let [ids (mapv :native-id xs)
                                           statuses (set (map :status xs))]
                                       {:digest (subs (str d) 0 12)
                                        :count (count xs)
                                        :distinct-native-ids (count (set (remove nil? ids)))
                                        :all-native? (every? some? ids)
                                        :statuses statuses
                                        :class
                                        (cond
                                          (and (every? some? ids)
                                               (= 1 (count (set ids))))
                                          :same-native-id-repeated

                                          (and (every? some? ids)
                                               (> (count (set ids)) 1))
                                          :native-collision-distinct-ids

                                          (some nil? ids)
                                          :blocking-ambiguous-or-mixed

                                          :else :other)}))))
                           vec)]
                  {:player-hash (rr/anonymize-id playerId)
                   :chat-hash (rr/anonymize-id chatId)
                   :import-ready? (:import-ready? c)
                   :status-counts (:status-counts c)
                   :collision-groups collision-groups
                   :blocks-recovery? (boolean (seq (filter #(contains?
                                                             #{:blocking-ambiguous-or-mixed
                                                               :native-collision-distinct-ids
                                                               :same-native-id-repeated}
                                                             (:class %))
                                                           collision-groups)))}))
              v1-accounts)
        all-groups (mapcat :collision-groups per-account)
        by-class (frequencies (map :class all-groups))
        pokemon-nc (reduce + 0 (map #(get-in % [:status-counts :native-collision] 0) per-account))
        pokemon-amb (reduce + 0 (map #(get-in % [:status-counts :ambiguous] 0) per-account))]
    {:pokemon-native-collision pokemon-nc
     :pokemon-ambiguous pokemon-amb
     :collision-groups-by-class by-class
     :accounts-blocking (count (filter :blocks-recovery? per-account))
     :accounts-import-ready (count (filter :import-ready? per-account))
     :accounts (mapv #(dissoc % :collision-groups) per-account)
     :group-sample (take 15 all-groups)}))

(defn apply-stable-ids
  "Retorna conta com id-pokemon preenchido só para :synthesizable/:native.
   Ambiguous → exceção (candidato deve ter sido excluído)."
  [{:keys [chat-id player-id account]}]
  (let [c (sid/classify-account {:chat-id chat-id :player-id player-id :account account})]
    (when-not (:import-ready? c)
      (throw (ex-info "apply_stable_ids_not_ready" {:status-counts (:status-counts c)})))
    (let [located (shape/pokemon-in-location account)
          proposed (mapv :proposed-id (:rows c))]
      (when-not (= (count located) (count proposed))
        (throw (ex-info "apply_stable_ids_count_mismatch" {})))
      (when (some nil? proposed)
        (throw (ex-info "apply_stable_ids_nil_proposed" {})))
      (when-not (= (count proposed) (count (set proposed)))
        (throw (ex-info "apply_stable_ids_not_unique" {})))
      (let [state (atom (vec proposed))
            next-id! (fn []
                       (let [id (first @state)]
                         (swap! state rest)
                         id))
            rewrite-entry (fn [entry]
                            (let [u (shape/unwrap-entry entry)
                                  id (next-id!)
                                  poke (assoc (:pokemon u) "id-pokemon" id :id-pokemon id)]
                              (if (:enveloped? u)
                                (cond-> {:pokemon poke}
                                  (some? (:pronto-em u)) (assoc :pronto-em (:pronto-em u)))
                                poke)))
            getv (fn [k]
                   (or (get account k) (get account (name k)) (get account (keyword k)) []))
            equipe (mapv rewrite-entry (or (getv :equipe) []))
            pc (mapv rewrite-entry (or (getv :pc) []))
            enf (mapv rewrite-entry (or (getv :enfermaria) []))]
        (-> (into {} account)
            (assoc :equipe equipe "equipe" equipe
                   :pc pc "pc" pc
                   :enfermaria enf "enfermaria" enf))))))

(defn- inventory [s chat player]
  (let [active (ports/active-save-id s chat player)
        saves (ports/list-saves s chat player)
        coll (try (ports/list-collection s chat player active) (catch Exception _ []))
        recovery-slots (filter #(str/starts-with? (str (:slot-id %)) "recovery") saves)
        recovery-colls
        (into {}
              (for [slot recovery-slots
                    :let [id (:slot-id slot)]]
                [id (try (count (ports/list-collection s chat player id))
                         (catch Exception _ -1))]))]
    {:active-save-id active
     :save-ids (sort (mapv :slot-id saves))
     :active-collection-count (count coll)
     :recovery-collection-counts recovery-colls}))

(defn- gate-candidate
  "Gates individuais. Exclui se pending, ids não ready, multi-chat merge, etc."
  [{:keys [chat-id player-id account source trainer-status loja-appearances all-chat-appearances]}]
  (let [ids (sid/classify-account {:chat-id chat-id :player-id player-id :account account})
        eco (eco/classify-player-economy (or loja-appearances []))
        scope (man/identity-scope player-id
                                  (map #(hash-map :chat-id (:chat-id %)
                                                  :fingerprint-hash
                                                  (or (:fingerprint-hash %)
                                                      (:fingerprint (rr/account-fingerprint (:account %)))))
                                       (or all-chat-appearances
                                           [{:chat-id chat-id :account account}])))
        stability-a (sid/classify-account {:chat-id chat-id :player-id player-id :account account})
        ;; reorder equipe to prove stability
        eq (or (get account :equipe) (get account "equipe") [])
        reordered (assoc account :equipe (vec (reverse eq)) "equipe" (vec (reverse eq)))
        stability-b (sid/classify-account {:chat-id chat-id :player-id player-id :account reordered})
        ids-stable? (sid/reorder-invariant? stability-a stability-b)
        proposed (mapv :proposed-id (:rows ids))
        unique-ids? (= (count proposed) (count (set (remove nil? proposed))))
        reasons (cond-> []
                  (not= :resolved trainer-status) (conj :trainer-not-resolved)
                  (not (:import-ready? ids)) (conj :ids-not-ready)
                  (pos? (get-in ids [:status-counts :ambiguous] 0)) (conj :ambiguous-pokemon)
                  (pos? (get-in ids [:status-counts :native-collision] 0)) (conj :native-collision)
                  (= :divergent (:status eco)) (conj :economy-divergent-hold-ok)
                  (not ids-stable?) (conj :ids-not-stable-under-reorder)
                  (not unique-ids?) (conj :proposed-ids-not-unique)
                  false (conj :never))
        ;; divergent economy does NOT exclude trainer recovery (hold economy)
        hard (remove #{:economy-divergent-hold-ok} reasons)
        pass? (empty? hard)]
    {:player-hash (rr/anonymize-id player-id)
     :chat-hash (rr/anonymize-id chat-id)
     :player-kind (rr/identity-kind player-id)
     :pass? pass?
     :exclude-reasons (vec hard)
     :warnings (vec (filter #{:economy-divergent-hold-ok} reasons))
     :economy-status (:status eco)
     :id-status-counts (:status-counts ids)
     :synthesizable (get-in ids [:status-counts :synthesizable] 0)
     :native (get-in ids [:status-counts :native] 0)
     :pokemon-count (:pokemon-count ids)
     :ids-stable-reorder? ids-stable?
     :proposed-ids-unique? unique-ids?
     :multi-chat? (:multi-chat? scope)
     :scope-model (:canonical-model scope)
     :_chat chat-id
     :_player player-id
     :_account account
     :_source source}))

(defn- patch-dump-snapshots
  "Gera dump descartável em memória com treinador reescrito (ids estáveis)."
  [dump patches]
  ;; patches: { [chat player] account }
  (let [snaps (mapv (fn [snap]
                      (let [chat (g snap :chat-id)
                            player (g snap :player-id)
                            key [chat player]
                            account (get patches key)]
                        (if account
                          (assoc snap :treinador account "treinador" account)
                          snap)))
                    (or (:historical_snapshots dump)
                        (g dump :historical_snapshots)
                        []))]
    (assoc dump :historical_snapshots snaps
           :meta (assoc (or (:meta dump) {})
                        :authorized-offline-copy true
                        :a64-stable-id-applied true
                        :disposable-rehearsal true))))

(defn -main [& args]
  (let [opts (apply hash-map args)
        private-path (get opts "--private-accounts")
        matrix-path (get opts "--matrix")
        dump-path (get opts "--dump")
        storage (get opts "--storage")
        out-dir (get opts "--out")
        skip-write? (= "true" (get opts "--audit-only"))]
    (when-not (and private-path matrix-path dump-path out-dir)
      (println "Uso: --private-accounts --matrix --dump --out [--storage] [--audit-only true]")
      (System/exit 2))
    (let [iso (when storage (assert-isolation! storage))
          private (read-edn private-path)
          matrix (read-edn matrix-path)
          dump (export/load-edn-file dump-path)
          accounts (:accounts private)
          v1 (filterv #(= "cassandra-v1" (name (:source %))) accounts)
          audit (audit-v1-collisions v1)
          by-player (group-by :playerId accounts)
          matrix-rows (:rows (:matrix matrix))
          status-by-ph (into {} (map (juxt :player-hash :status) matrix-rows))
          ;; candidatos: resolved + V1 present
          candidates
          (->> by-player
               (mapcat
                (fn [[pid xs]]
                  (for [[chat ys] (group-by :chatId xs)
                        :let [v1a (first (filter #(= "cassandra-v1" (name (:source %))) ys))
                              ph (rr/anonymize-id pid)
                              st (get status-by-ph ph)]
                        :when (and v1a (= :resolved st))]
                    (gate-candidate
                     {:chat-id chat
                      :player-id pid
                      :account (:account v1a)
                      :source :cassandra-v1
                      :trainer-status st
                      :loja-appearances []
                      :all-chat-appearances
                      (mapv #(hash-map :chat-id (:chatId %) :account (:account %)) xs)}))))
               vec)
          passed (filterv :pass? candidates)
          excluded (filterv (complement :pass?) candidates)
          ;; Determinism check: re-gate twice
          passed2 (mapv #(gate-candidate
                          {:chat-id (:_chat %)
                           :player-id (:_player %)
                           :account (:_account %)
                           :source :cassandra-v1
                           :trainer-status :resolved
                           :loja-appearances []
                           :all-chat-appearances
                           [{:chat-id (:_chat %) :account (:_account %)}]})
                        passed)
          deterministic?
          (= (mapv #(select-keys % [:player-hash :chat-hash :pokemon-count :synthesizable :native]) passed)
             (mapv #(select-keys % [:player-hash :chat-hash :pokemon-count :synthesizable :native]) passed2))
          audit-report {:ok true
                        :at (str (java.time.Instant/now))
                        :isolation (or iso {:deferred true})
                        :collision-audit audit
                        :candidates-total (count candidates)
                        :passed (count passed)
                        :excluded (count excluded)
                        :excluded-reasons (frequencies (mapcat :exclude-reasons excluded))
                        :passed-anonymized
                        (mapv #(select-keys % [:player-hash :chat-hash :player-kind
                                               :pokemon-count :native :synthesizable
                                               :economy-status :warnings
                                               :ids-stable-reorder? :proposed-ids-unique?
                                               :multi-chat?])
                              passed)
                        :excluded-anonymized
                        (mapv #(select-keys % [:player-hash :chat-hash :exclude-reasons
                                               :id-status-counts])
                              excluded)
                        :deterministic-regate? deterministic?
                        :gates-ok? (and deterministic?
                                        (pos? (count passed))
                                        (every? :ids-stable-reorder? passed)
                                        (every? :proposed-ids-unique? passed))}]
      (.mkdirs (io/file out-dir))
      (write-edn! (str out-dir "/batch-audit-gates.edn") audit-report)
      (pp/pprint (dissoc audit-report :passed-anonymized :excluded-anonymized :collision-audit))
      (pp/pprint {:collision-summary (select-keys audit [:pokemon-native-collision
                                                         :pokemon-ambiguous
                                                         :collision-groups-by-class
                                                         :accounts-blocking
                                                         :accounts-import-ready])
                  :passed-count (count passed)
                  :gates-ok? (:gates-ok? audit-report)})
      (when skip-write?
        (println "AUDIT_ONLY_DONE")
        (System/exit (if (:gates-ok? audit-report) 0 1)))
      (when-not (:gates-ok? audit-report)
        (println "GATES_FAILED — abortando antes de escrita")
        (System/exit 1))
      (when-not storage
        (println "missing --storage for write phase")
        (System/exit 2))
      ;; Build patched dump for passed candidates only
      (let [patches
            (into {}
                  (map (fn [c]
                         [[(:_chat c) (:_player c)]
                          (apply-stable-ids {:chat-id (:_chat c)
                                             :player-id (:_player c)
                                             :account (:_account c)})])
                       passed))
            patched (patch-dump-snapshots dump patches)
            dump-out (str out-dir "/authorized-dump-stable-ids-disposable.edn")
            _ (write-edn! dump-out patched)
            client (persistence/client {:system "odisseu-m90-core" :storage-dir storage})
            conn (persistence/ensure-db! client "core")
            s (doto (store/make-store conn) trainer/bootstrap!)
            cat (catalog/load-catalog {:dump-path dump-out :import-token import-token})
            results
            (mapv
             (fn [c]
               (try
                 (let [chat (:_chat c)
                       player (:_player c)
                       _ (when-not (ports/get-player s chat player)
                           (trainer/create-trainer! s {:chat-id chat :player-id player}))
                       before (inventory s chat player)
                       disc (catalog/discover cat chat player)
                       _ (when (empty? (:versions disc))
                           (throw (ex-info "discover_empty" {})))
                       choice (if (some #{"v1"} (:versions disc)) :v1 :v1)
                       req (catalog/register-request! cat {:chat-id chat :player-id player :choice choice})
                       conf (catalog/player-confirm!
                             cat {:chat-id chat :player-id player
                                  :request-id (:request-id req)
                                  :confirmation-token (:confirmation-token req)})
                       imported (catalog/admin-import!
                                 cat s {:request-id (:request-id req)
                                        :import-token import-token
                                        :import-batch-id (str "a64-batch|" (:player-hash c))})
                       after (inventory s chat player)
                       ;; idempotency
                       entry (get @(:requests cat) (:request-id req))
                       retry (recovery/confirm!
                              s (catalog/snapshots-for-request cat entry)
                              {:chat-id chat :player-id player
                               :selected (:selected-full entry)
                               :token (:confirmation-token entry)
                               :import-batch-id (str "a64-batch|" (:player-hash c))})
                       after-retry (inventory s chat player)
                       ok? (and (= (:active-save-id before) (:active-save-id after)
                                   (:active-save-id after-retry))
                                (every? #(= :duplicate (:status %)) (:results retry))
                                (= (get-in after [:recovery-collection-counts "recovery-v1"])
                                   (:pokemon-count c)))]
                   {:player-hash (:player-hash c)
                    :chat-hash (:chat-hash c)
                    :ok? ok?
                    :before before
                    :after after
                    :after-retry after-retry
                    :import-status (:status imported)
                    :pokemon-expected (:pokemon-count c)
                    :pokemon-imported (get-in after [:recovery-collection-counts "recovery-v1"])
                    :retry-duplicate? (every? #(= :duplicate (:status %)) (:results retry))
                    :active-preserved? (= "a" (:active-save-id after))
                    :economy-status (:economy-status c)
                    :error nil})
                 (catch Exception e
                   {:player-hash (:player-hash c)
                    :chat-hash (:chat-hash c)
                    :ok? false
                    :error (ex-message e)
                    :ex-data (ex-data e)})))
             passed)
            ;; Partial failure simulation: tamper token on first passed → expect mismatch; state unchanged
            partial
            (when-let [c (first passed)]
              (let [chat (:_chat c)
                    player (:_player c)
                    before (inventory s chat player)
                    disc (catalog/discover cat chat player)
                    req (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
                    conf (catalog/player-confirm!
                          cat {:chat-id chat :player-id player
                               :request-id (:request-id req)
                               :confirmation-token (:confirmation-token req)})
                    entry (get @(:requests cat) (:request-id req))
                    failed?
                    (try
                      (recovery/confirm!
                       s (catalog/snapshots-for-request cat entry)
                       {:chat-id chat :player-id player
                        :selected (:selected-full entry)
                        :token "tampered-token"
                        :import-batch-id (str "a64-batch-fail|" (:player-hash c))})
                      false
                      (catch Exception _
                        true))
                    after (inventory s chat player)
                    ;; resume with correct token + new batch id should still be duplicate of existing slot
                    resume
                    (recovery/confirm!
                     s (catalog/snapshots-for-request cat entry)
                     {:chat-id chat :player-id player
                      :selected (:selected-full entry)
                      :token (:confirmation-token entry)
                      :import-batch-id (str "a64-batch|" (:player-hash c))})
                    after-resume (inventory s chat player)]
                {:tampered-rejected? failed?
                 :state-unchanged-after-tamper? (= before after)
                 :resume-duplicate? (every? #(= :duplicate (:status %)) (:results resume))
                 :active-after (:active-save-id after-resume)
                 :player-hash (:player-hash c)}))
            final {:ok (every? :ok? results)
                   :at (str (java.time.Instant/now))
                   :isolation iso
                   :passed-count (count passed)
                   :success-count (count (filter :ok? results))
                   :fail-count (count (filter (complement :ok?) results))
                   :results results
                   :partial-failure-sim partial
                   :confirm-invoked-on-disposable true
                   :cassandra-writes []
                   :live-datomic-writes []
                   :pending-untouched 9}]
        (write-edn! (str out-dir "/batch-rehearsal-report-anonymized.edn") final)
        (pp/pprint (dissoc final :results))
        (pp/pprint {:results-summary
                    (mapv #(select-keys % [:player-hash :ok? :pokemon-expected
                                           :pokemon-imported :retry-duplicate?
                                           :active-preserved? :economy-status :error])
                          results)
                    :partial partial})
        (System/exit (if (:ok final) 0 1))))))
