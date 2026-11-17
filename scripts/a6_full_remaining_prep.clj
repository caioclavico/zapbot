(ns a6-full-remaining-prep
  "Preparação completa: inventário V1 restante + ensaio descartável.

  - Exclui os 13 jogadores já recuperados (não os modifica).
  - Fonte histórica: Cassandra V1.
  - Política IDs: keep-native para native-collision com IDs distintos;
    sintéticos só para núcleos únicos sem id; ambíguos → hold humano.
  - Economia: omitir loja/moedas/inventário (hold).
  - Sem Cassandra env, sem produção, sem aliases."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.recovery-catalog :as catalog]
            [pokemon.application.recovery-economy :as eco]
            [pokemon.application.recovery-reconcile :as rr]
            [pokemon.application.recovery-stable-id :as sid]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.account-shape :as shape]
            [pokemon.recovery :as recovery]))

(def ^:private import-token "a64-full-remaining-rehearsal-token-not-for-prod")
(def ^:private system "odisseu-a64-full-remaining-rehearsal")
(def ^:private batch-prefix "a64-full-remaining|")

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
    {:ok true :storage abs :cassandra-env-cleared true :shares-live-volume false}))

(defn- strip-economy [account]
  (-> (into {} account)
      (dissoc :loja "loja" :moedas "moedas" :inventario "inventario")))

(defn- load-recovered-player-ids
  "Union do roster dos 12 + player-id do usuário (119)."
  [roster-path user-player]
  (let [edn (when roster-path (read-edn roster-path))
        from-roster (->> (or (:trainers edn) [])
                         (map #(or (g % :player-id) (g % :playerId)))
                         (remove nil?)
                         set)]
    (cond-> from-roster
      user-player (conj user-player))))

(defn resolve-ids
  "Keep-native para native-collision com IDs distintos; synth para núcleos únicos.
   Ambiguous / same-native-id-repeated → bloqueia."
  [{:keys [chat-id player-id account]}]
  (let [c (sid/classify-account {:chat-id chat-id :player-id player-id :account account})
        rows (:rows c)
        by-d (group-by :digest rows)
        groups
        (->> by-d
             (keep (fn [[d xs]]
                     (when (> (count xs) 1)
                       (let [ids (mapv :native-id xs)
                             uniq (set (remove nil? ids))]
                         {:digest12 (subs (str d) 0 12)
                          :peer-count (count xs)
                          :distinct-native-ids (count uniq)
                          :all-have-native? (every? some? ids)
                          :class (cond
                                   (some nil? ids) :blocking-mixed-or-ambiguous
                                   (= 1 (count uniq)) :same-native-id-repeated
                                   :else :native-collision-distinct-ids)}))))
             vec)
        proposed (mapv :proposed-id rows)
        unique-ids? (and (every? some? proposed)
                         (= (count proposed) (count (set proposed))))
        blocking (filterv #(contains? #{:blocking-mixed-or-ambiguous :same-native-id-repeated}
                                      (:class %))
                          groups)
        amb (get (:status-counts c) :ambiguous 0)
        ready? (and (pos? (count rows))
                    (zero? (count blocking))
                    (zero? amb)
                    unique-ids?)]
    {:pokemon-count (:pokemon-count c)
     :status-counts (:status-counts c)
     :collision-groups groups
     :native-collision-distinct-groups
     (count (filter #(= :native-collision-distinct-ids (:class %)) groups))
     :blocking-groups (count blocking)
     :ambiguous-pokemon amb
     :proposed-ids-unique? unique-ids?
     :import-ready-after-keep-native? ready?
     :policy "keep-distinct-native-ids; synthesize unique blanks; never auto-merge ambiguous"
     :rows rows}))

(defn apply-ids
  "Reescreve id-pokemon com proposed-id (nativo preservado ou sintético).
   Exige ready após keep-native."
  [{:keys [chat-id player-id account]}]
  (let [res (resolve-ids {:chat-id chat-id :player-id player-id :account account})]
    (when-not (:import-ready-after-keep-native? res)
      (throw (ex-info "apply_ids_not_ready"
                      {:status-counts (:status-counts res)
                       :blocking-groups (:blocking-groups res)
                       :ambiguous (:ambiguous-pokemon res)})))
    (let [proposed (mapv :proposed-id (:rows res))
          located (shape/pokemon-in-location account)
          _ (when-not (= (count located) (count proposed))
              (throw (ex-info "apply_ids_count_mismatch" {})))
          state (atom (vec proposed))
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
                 :enfermaria enf "enfermaria" enf)))))

(defn- inventory [s chat player]
  (let [active (ports/active-save-id s chat player)
        saves (ports/list-saves s chat player)]
    {:active-save-id active
     :save-ids (sort (mapv :slot-id saves))
     :counts (into {}
                   (for [slot saves
                         :let [id (:slot-id slot)]]
                     [id (count (ports/list-collection s chat player id))]))
     :coins-by-slot
     (into {}
           (for [slot saves
                 :let [id (:slot-id slot)
                       save (ports/get-save s chat player id)]]
             [id (:coins save)]))}))

(defn- seed-onboarding!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (let [p (ports/get-player s chat player)
        ctx {:chat-id chat :player-id player :save-id "a"
             :fence-generation (:fence-generation p)}]
    (ports/add-to-collection!
     s ctx
     {:id-pokemon (str "rehearsal-onboard-" (rr/anonymize-id player))
      :species "bulbasaur"
      :nome "Bulbasaur"
      :nivel 5
      :xp 0
      :xp-desde-nivel 0
      :hp 45
      :hp-atual 45
      :ataque 49 :defesa 49 :atq-esp 65 :def-esp 65 :veloc 45
      :shiny false
      :tipos ["grass" "poison"]})
    {:seeded-slot "a"
     :active (ports/active-save-id s chat player)
     :count-a (count (ports/list-collection s chat player "a"))}))

(defn- loja-appearances-for
  [accounts player-id]
  (->> accounts
       (filter #(= player-id (g % :playerId)))
       (keep (fn [a]
               (when-let [loja (or (g (g a :account) :loja)
                                   (g a :loja))]
                 {:source (keyword (str (g a :source) "-loja"))
                  :loja loja})))
       vec))

(defn inventory-remaining!
  "Classifica todas as identidades V1; separa recuperados vs restantes."
  [{:keys [private-accounts recovered-players]}]
  (let [accounts (:accounts private-accounts)
        v1 (filterv #(= "cassandra-v1" (str (g % :source))) accounts)
        by-player (group-by #(g % :playerId) v1)
        rows
        (mapv
         (fn [a]
           (let [chat (g a :chatId)
                 player (g a :playerId)
                 account (g a :account)
                 res (resolve-ids {:chat-id chat :player-id player :account account})
                 eco (eco/classify-player-economy (loja-appearances-for accounts player))
                 multi? (> (count (get by-player player)) 1)
                 recovered? (contains? recovered-players player)
                 ready? (:import-ready-after-keep-native? res)]
             {:player-hash (rr/anonymize-id player)
              :chat-hash (rr/anonymize-id chat)
              :player-kind (rr/identity-kind player)
              :recovered-player? recovered?
              :multi-chat? multi?
              :player-chat-count (count (get by-player player))
              :pokemon-count (:pokemon-count res)
              :status-counts (:status-counts res)
              :native-collision-distinct-groups (:native-collision-distinct-groups res)
              :blocking-groups (:blocking-groups res)
              :ambiguous-pokemon (:ambiguous-pokemon res)
              :proposed-ids-unique? (:proposed-ids-unique? res)
              :import-ready-after-keep-native? ready?
              :economy-status (:status eco)
              :economy-do-not-default-zero? (:do-not-default-zero eco true)
              :bucket (cond
                        recovered? :already-recovered
                        (not ready?) :ambiguous-hold
                        :else :import-candidate)
              :_chat chat
              :_player player
              :_account account}))
         v1)
        remaining (filterv (complement :recovered-player?) rows)
        candidates (filterv #(= :import-candidate (:bucket %)) remaining)
        ambiguous (filterv #(= :ambiguous-hold (:bucket %)) remaining)
        recovered-rows (filterv :recovered-player? rows)]
    {:ok true
     :at (str (java.time.Instant/now))
     :totals
     {:v1-identities (count rows)
      :v1-unique-players (count by-player)
      :v1-pokemon (reduce + 0 (map :pokemon-count rows))
      :recovered-players (count recovered-players)
      :recovered-identities (count recovered-rows)
      :recovered-pokemon (reduce + 0 (map :pokemon-count recovered-rows))
      :remaining-identities (count remaining)
      :remaining-unique-players (count (set (map :_player remaining)))
      :remaining-pokemon (reduce + 0 (map :pokemon-count remaining))
      :import-candidates (count candidates)
      :import-candidate-pokemon (reduce + 0 (map :pokemon-count candidates))
      :ambiguous-hold (count ambiguous)
      :ambiguous-hold-pokemon (reduce + 0 (map :pokemon-count ambiguous))}
     :recovered-anonymized
     (mapv #(select-keys % [:player-hash :chat-hash :pokemon-count :multi-chat?
                            :player-kind :status-counts])
           recovered-rows)
     :candidates-anonymized
     (mapv #(select-keys % [:player-hash :chat-hash :pokemon-count :player-kind
                            :multi-chat? :status-counts :native-collision-distinct-groups
                            :economy-status :import-ready-after-keep-native?])
           (sort-by (comp - :pokemon-count) candidates))
     :ambiguous-anonymized
     (mapv #(select-keys % [:player-hash :chat-hash :pokemon-count :status-counts
                            :blocking-groups :ambiguous-pokemon :economy-status])
           ambiguous)
     :_candidates candidates
     :_ambiguous ambiguous
     :_all rows}))

(defn build-dump
  [candidates]
  (let [snaps
        (mapv
         (fn [c]
           (let [chat (:_chat c)
                 player (:_player c)
                 account (-> (apply-ids {:chat-id chat :player-id player :account (:_account c)})
                             strip-economy)]
             {:snapshot-id (str "v1-full-" (:player-hash c) "-" (:chat-hash c))
              :legacy-version "v1"
              :chat-id chat
              :player-id player
              :treinador account
              :source {:kind "cassandra-v1"
                       :legacy-version "v1"
                       :module "full-remaining-prep"
                       :sourceVersion "1"}
              :created-at (str (java.time.Instant/now))}))
         candidates)]
    {:meta {:authorized-offline-copy true
            :disposable-rehearsal true
            :economy-hold true
            :economy-policy "omit-loja-coins-inventory; do-not-default-zero"
            :native-collision-policy "keep-distinct-native-ids"
            :aliases-accepted []
            :purpose "a64-full-remaining-v1-rehearsal"
            :exclude-already-recovered true
            :ambiguous-excluded true}
     :historical_snapshots snaps}))

(defn run-rehearsal!
  [{:keys [candidates storage out-dir]}]
  (let [iso (assert-isolation! storage)
        dump (build-dump candidates)
        dump-path (str out-dir "/authorized-dump-full-remaining.edn")
        _ (write-edn! dump-path dump)
        client (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client "core")
        s (store/make-store (persistence/ensure-db! client "core"))
        _ (trainer/bootstrap! s)
        cat (catalog/load-catalog {:dump-path dump-path :import-token import-token})
        results
        (mapv
         (fn [c]
           (try
             (let [chat (:_chat c)
                   player (:_player c)
                   batch (str batch-prefix (:player-hash c) "|" (:chat-hash c))
                   seed (seed-onboarding! s chat player)
                   before (inventory s chat player)
                   disc (catalog/discover cat chat player)
                   req (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
                   conf (catalog/player-confirm!
                         cat {:chat-id chat :player-id player
                              :request-id (:request-id req)
                              :confirmation-token (:confirmation-token req)})
                   imported (catalog/admin-import!
                             cat s {:request-id (:request-id req)
                                    :import-token import-token
                                    :import-batch-id batch})
                   after (inventory s chat player)
                   ;; idempotency same batch
                   entry (get @(:requests cat) (:request-id req))
                   retry (recovery/confirm!
                          s (catalog/snapshots-for-request cat entry)
                          {:chat-id chat :player-id player
                           :selected (:selected-full entry)
                           :token (:confirmation-token entry)
                           :import-batch-id batch})
                   after-retry (inventory s chat player)
                   ok? (and (= "a" (:active-save-id after) (:active-save-id after-retry))
                            (= 1 (get-in after [:counts "a"]))
                            (= (:pokemon-count c) (get-in after [:counts "recovery-v1"]))
                            (= (get-in after [:counts "recovery-v1"])
                               (get-in after-retry [:counts "recovery-v1"]))
                            (every? #(= :duplicate (:status %)) (:results retry))
                            (= 0 (long (or (get-in after [:coins-by-slot "recovery-v1"]) -1))))]
               {:player-hash (:player-hash c)
                :chat-hash (:chat-hash c)
                :ok? ok?
                :pokemon-expected (:pokemon-count c)
                :pokemon-imported (get-in after [:counts "recovery-v1"])
                :active-preserved? (= "a" (:active-save-id after))
                :slot-a-preserved? (= 1 (get-in after [:counts "a"]))
                :retry-duplicate? (every? #(= :duplicate (:status %)) (:results retry))
                :economy-hold-zero-coins? (= 0 (long (or (get-in after [:coins-by-slot "recovery-v1"]) -1)))
                :import-status (:status imported)
                :seed seed
                :before before
                :after after
                :error nil})
             (catch Exception e
               {:player-hash (:player-hash c)
                :chat-hash (:chat-hash c)
                :ok? false
                :error (ex-message e)
                :ex-data (ex-data e)})))
         candidates)
        ;; Partial failure + resume on first candidate
        partial
        (when-let [c (first candidates)]
          (let [chat (:_chat c)
                player (:_player c)
                batch (str batch-prefix (:player-hash c) "|" (:chat-hash c))
                before (inventory s chat player)
                disc (catalog/discover cat chat player)
                req (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
                _ (catalog/player-confirm!
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
                    :import-batch-id (str batch "|tamper")})
                  false
                  (catch Exception _ true))
                after-tamper (inventory s chat player)
                resume (recovery/confirm!
                        s (catalog/snapshots-for-request cat entry)
                        {:chat-id chat :player-id player
                         :selected (:selected-full entry)
                         :token (:confirmation-token entry)
                         :import-batch-id batch})
                after-resume (inventory s chat player)]
            {:tampered-rejected? failed?
             :state-unchanged-after-tamper? (= before after-tamper)
             :resume-duplicate? (every? #(= :duplicate (:status %)) (:results resume))
             :active-after (:active-save-id after-resume)
             :counts-after (:counts after-resume)
             :player-hash (:player-hash c)}))
        final {:ok (every? :ok? results)
               :at (str (java.time.Instant/now))
               :isolation iso
               :passed-count (count candidates)
               :success-count (count (filter :ok? results))
               :fail-count (count (filter (complement :ok?) results))
               :results (mapv #(dissoc % :before :after :seed) results)
               :partial-failure-sim partial
               :confirm-invoked-on-disposable true
               :cassandra-writes []
               :live-datomic-writes []
               :prod-import false}]
    (write-edn! (str out-dir "/full-remaining-rehearsal-report.edn") final)
    final))

(defn -main [& args]
  (let [opts (apply hash-map args)
        private-path (get opts "--private-accounts")
        roster-path (get opts "--roster-12")
        user-player (get opts "--user-player")
        storage (get opts "--storage")
        out-dir (get opts "--out")
        audit-only? (= "true" (get opts "--audit-only"))]
    (when-not (and private-path out-dir)
      (println "Uso: --private-accounts --out [--roster-12] [--user-player] [--storage] [--audit-only true]")
      (System/exit 2))
    (let [priv (read-edn private-path)
          recovered (load-recovered-player-ids roster-path user-player)
          inv (inventory-remaining! {:private-accounts priv :recovered-players recovered})
          _ (write-edn! (str out-dir "/full-v1-inventory-anonymized.edn")
                        (dissoc inv :_candidates :_ambiguous :_all))
          _ (pp/pprint (:totals inv))
          _ (println "CANDIDATES" (count (:_candidates inv))
                     "AMBIGUOUS" (count (:_ambiguous inv))
                     "RECOVERED_IDS" (count recovered))]
      (when audit-only?
        (println "AUDIT_ONLY_DONE")
        (System/exit 0))
      (when-not storage
        (println "missing --storage for rehearsal")
        (System/exit 2))
      (when (empty? (:_candidates inv))
        (println "NO_CANDIDATES")
        (System/exit 1))
      (let [r (run-rehearsal! {:candidates (:_candidates inv)
                              :storage storage
                              :out-dir out-dir})]
        (pp/pprint (dissoc r :results))
        (pp/pprint {:results-summary
                    (mapv #(select-keys % [:player-hash :ok? :pokemon-expected
                                           :pokemon-imported :retry-duplicate?
                                           :active-preserved? :slot-a-preserved?
                                           :economy-hold-zero-coins? :error])
                          (:results r))})
        (println "REHEARSAL_OK" (:ok r)
                 "SUCCESS" (:success-count r)
                 "FAIL" (:fail-count r))
        (System/exit (if (:ok r) 0 1))))))
