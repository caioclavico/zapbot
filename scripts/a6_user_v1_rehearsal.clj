(ns a6-user-v1-rehearsal
  "Ensaio descartável: Cassandra V1 (boot jogos) → recovery-v1 para 1 treinador.

  Preserva slot a (onboarding), não importa outro chat, não escreve loja histórica,
  não cria aliases, não toca os 12 já recuperados (base limpa)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.recovery-catalog :as catalog]
            [pokemon.application.recovery-stable-id :as sid]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.account-shape :as shape]))

(def ^:private import-token "a64-user-v1-rehearsal-token-not-for-prod")
(def ^:private system "odisseu-a64-user-v1-rehearsal")

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

(defn- sha12 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        b (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) (take 6 b)))))

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

(defn resolve-native-collisions
  "Política: native-collision com IDs nativos distintos → preservar cada id.
   Sem merge, sem síntese substituta, sem associação automática."
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
                                   (some nil? ids) :blocking-mixed
                                   (= 1 (count uniq)) :same-native-id-repeated
                                   :else :native-collision-distinct-ids)
                          :species (mapv #(get-in % [:core :nome]) xs)
                          :locations (mapv :location xs)
                          :peer-id-sha12 (mapv #(some-> % :native-id sha12) xs)
                          :peer-levels (mapv #(g (:pokemon %) :nivel) xs)}))))
             vec)
        proposed (mapv :proposed-id rows)
        unique-ids? (and (every? some? proposed)
                         (= (count proposed) (count (set proposed))))
        blocking (filterv #(contains? #{:blocking-mixed :same-native-id-repeated} (:class %))
                          groups)
        nc (filterv #(= :native-collision-distinct-ids (:class %)) groups)
        promoted
        (mapv (fn [row]
                (if (= :native-collision (:status row))
                  (assoc row :status :native-distinct-preserved
                             :resolution :keep-native-id-no-merge)
                  row))
              rows)
        ready? (and (pos? (count rows))
                    (zero? (count blocking))
                    (zero? (get (:status-counts c) :ambiguous 0))
                    unique-ids?)]
    {:scheme (:scheme c)
     :pokemon-count (:pokemon-count c)
     :status-counts-raw (:status-counts c)
     :status-counts-after (frequencies (map :status promoted))
     :collision-groups groups
     :native-collision-distinct-groups (count nc)
     :native-collision-pokemon (reduce + 0 (map :peer-count nc))
     :blocking-groups (count blocking)
     :proposed-ids-unique? unique-ids?
     :import-ready-after-resolution? ready?
     :policy "keep-distinct-native-ids; never auto-associate shared-core peers"
     :note "classify-account marca :native-collision; import usa id-pokemon nativo sem reescrever"}))

(defn- strip-economy [account]
  (-> (into {} account)
      (dissoc :loja "loja" :moedas "moedas" :inventario "inventario")))

(defn- extract-v1-boot [private-accounts chat player]
  (->> (:accounts private-accounts)
       (filter #(and (= chat (g % :chatId))
                     (= player (g % :playerId))
                     (= "cassandra-v1" (str (g % :source)))))
       first))

(defn- build-dump [entry chat player]
  (let [account (strip-economy (g entry :account))
        snap {:snapshot-id (str "v1-user-boot-" (sha12 (str chat "|" player)))
              :legacy-version "v1"
              :chat-id chat
              :player-id player
              :treinador account
              ;; loja omitida de propósito (economy hold)
              :source {:kind "cassandra-v1"
                       :legacy-version "v1"
                       :module "user-v1-rehearsal"
                       :sourceVersion "1"}
              :created-at (str (java.time.Instant/now))}]
    {:meta {:authorized-offline-copy true
            :disposable-rehearsal true
            :economy-hold true
            :economy-policy "omit-loja-coins-zero-placeholder"
            :other-chat-excluded true
            :aliases-accepted []
            :purpose "a64-user-v1-boot-jogos-rehearsal"
            :native-collision-policy "keep-distinct-native-ids"}
     :historical_snapshots [snap]}))

(defn- seed-onboarding!
  "Espelha live: trainer + 1 Pokémon no slot a; active=a."
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (let [p (ports/get-player s chat player)
        ctx {:chat-id chat :player-id player :save-id "a"
             :fence-generation (:fence-generation p)}]
    (ports/add-to-collection!
     s ctx
     {:id-pokemon "rehearsal-onboard-starter-001"
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
     :seeded-id "rehearsal-onboard-starter-001"
     :active (ports/active-save-id s chat player)
     :count-a (count (ports/list-collection s chat player "a"))}))

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

(defn- sample-preserved-attrs [s chat player]
  (let [col (ports/list-collection s chat player "recovery-v1")
        p (first (sort-by :id-pokemon col))]
    (when p
      (select-keys p [:id-pokemon :nome :species :nivel :xp-desde-nivel :xp
                      :ataque :defesa :atq-esp :def-esp :veloc :hp :shiny
                      :ivs :item]))))

(defn run-rehearsal!
  [{:keys [private-accounts storage out-dir chat player]}]
  (let [iso (assert-isolation! storage)
        priv (read-edn private-accounts)
        entry (extract-v1-boot priv chat player)]
    (when-not entry
      (throw (ex-info "v1_boot_account_not_found" {:chat-sha12 (sha12 chat)
                                                   :player-sha12 (sha12 player)})))
    (let [account (g entry :account)
          other-chat-count
          (->> (:accounts priv)
               (filter #(and (= player (g % :playerId))
                             (not= chat (g % :chatId))
                             (= "cassandra-v1" (str (g % :source)))))
               (map #(count (shape/pokemon-in-location (g % :account))))
               (reduce + 0))
          resolution (resolve-native-collisions {:chat-id chat :player-id player :account account})
          dump (build-dump entry chat player)
          dump-path (str out-dir "/authorized-dump-user-v1-boot.edn")
          _ (write-edn! dump-path dump)
          _ (write-edn! (str out-dir "/collision-resolution.edn") resolution)
          client (persistence/client {:system system :storage-dir storage})
          _ (persistence/delete-db! client "core")
          s (store/make-store (persistence/ensure-db! client "core"))
          _ (trainer/bootstrap! s)
          seed (seed-onboarding! s chat player)
          before (inventory s chat player)
          cat (catalog/load-catalog {:dump-path dump-path :import-token import-token})
          disc (catalog/discover cat chat player)
          req (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
          conf (catalog/player-confirm!
                cat {:chat-id chat :player-id player :request-id (:request-id req)})
          imported (catalog/admin-import!
                    cat s {:request-id (:request-id req)
                           :import-token import-token
                           :import-batch-id "a64-user-v1-boot-rehearsal"})
          after (inventory s chat player)
          ;; idempotência: novo request + mesmo batch-id → :duplicate
          idem
          (let [req2 (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
                _ (catalog/player-confirm!
                   cat {:chat-id chat :player-id player :request-id (:request-id req2)})]
            (try
              (catalog/admin-import!
               cat s {:request-id (:request-id req2)
                      :import-token import-token
                      :import-batch-id "a64-user-v1-boot-rehearsal"})
              (catch Exception ex
                {:error (ex-message ex) :data (ex-data ex)})))
          ;; retomada segura: batch alternativo em slot ocupado deve bloquear
          resume-block
          (let [req3 (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
                _ (catalog/player-confirm!
                   cat {:chat-id chat :player-id player :request-id (:request-id req3)})]
            (try
              (catalog/admin-import!
               cat s {:request-id (:request-id req3)
                      :import-token import-token
                      :import-batch-id "a64-user-v1-boot-rehearsal-resume-alt"})
              (catch Exception ex
                {:blocked true
                 :message (ex-message ex)
                 :code (or (:code (ex-data ex))
                           (:pokemon.domain.errors/code (ex-data ex)))})))
          sample (sample-preserved-attrs s chat player)
          checks
          {:recovery-v1-count (get-in after [:counts "recovery-v1"])
           :expected-119? (= 119 (get-in after [:counts "recovery-v1"]))
           :active-still-a? (= "a" (:active-save-id after))
           :slot-a-count (get-in after [:counts "a"])
           :slot-a-preserved? (= 1 (get-in after [:counts "a"]))
           :onboard-id-intact?
           (= "rehearsal-onboard-starter-001"
              (:id-pokemon (first (ports/list-collection s chat player "a"))))
           :other-chat-excluded? (= 4 other-chat-count)
           :other-chat-v1-pokemon-excluded other-chat-count
           :alias-applied? (boolean (:alias-applied? disc))
           :economy-hold-recovery-coins-zero?
           (= 0 (long (or (get-in after [:coins-by-slot "recovery-v1"]) -1)))
           :idempotent-duplicate?
           (= :duplicate (get-in idem [:results 0 :status]))
           :resume-alt-batch-blocked? (boolean (:blocked resume-block))
           :collision-ready? (:import-ready-after-resolution? resolution)}
          ok? (and (:expected-119? checks)
                   (:active-still-a? checks)
                   (:slot-a-preserved? checks)
                   (:onboard-id-intact? checks)
                   (:collision-ready? checks)
                   (:economy-hold-recovery-coins-zero? checks)
                   (:idempotent-duplicate? checks)
                   (:resume-alt-batch-blocked? checks)
                   (not (:alias-applied? checks)))
          report
          {:ok ok?
           :at (str (java.time.Instant/now))
           :isolation iso
           :identity {:chat-sha12 (sha12 chat)
                      :player-sha12 (sha12 player)
                      :player-kind :lid
                      :boot-jogos true
                      :other-chat-v1-excluded-count other-chat-count
                      :aliases-none true}
           :collision-resolution
           (select-keys resolution
                        [:pokemon-count :status-counts-raw :status-counts-after
                         :native-collision-distinct-groups :native-collision-pokemon
                         :blocking-groups :proposed-ids-unique?
                         :import-ready-after-resolution? :policy :collision-groups :note])
           :seed seed
           :before before
           :discover {:catalog-ready (:catalog-ready disc)
                      :versions (:versions disc)
                      :alias-applied? (:alias-applied? disc)
                      :available (:available disc)}
           :confirm (select-keys conf [:request-id :status :choice])
           :import {:status (:status imported)
                    :import-batch-id (:import-batch-id imported)
                    :active-save-id (:active-save-id imported)
                    :results (:results imported)}
           :after after
           :sample-pokemon-attrs sample
           :checks checks
           :idempotency idem
           :resume-after-occupied resume-block}]
      (write-edn! (str out-dir "/rehearsal-report.edn") report)
      (write-edn! (str out-dir "/execution-plan-draft.edn")
                  {:trainer {:player-sha12 (sha12 player)
                             :chat "boot jogos"
                             :chat-sha12 (sha12 chat)}
                   :source "cassandra-v1"
                   :slot "recovery-v1"
                   :preserve-slot "a"
                   :pokemon-count 119
                   :exclude-other-chat-pokemon 4
                   :economy "hold — omitir loja; não escrever 437/435/248"
                   :collision-policy (:policy resolution)
                   :touch-12 false
                   :aliases false
                   :prod-import false
                   :awaiting-approval true
                   :rehearsal-ok ok?
                   :checks checks})
      report)))

(defn -main [& args]
  (let [opts (apply hash-map args)
        private (or (get opts "--private-accounts") (get opts "--private-json"))
        storage (get opts "--storage")
        out (get opts "--out")
        chat (get opts "--chat")
        player (get opts "--player")]
    (when-not (and private storage out chat player)
      (println "Uso: --private-accounts --storage --out --chat --player")
      (System/exit 2))
    (let [r (run-rehearsal! {:private-accounts private :storage storage :out-dir out
                             :chat chat :player player})]
      (pp/pprint (dissoc r :idempotency))
      (println "OK" (:ok r)
               "RECOVERY_V1" (get-in r [:checks :recovery-v1-count])
               "ACTIVE" (get-in r [:after :active-save-id])
               "A" (get-in r [:checks :slot-a-count])
               "COLLISION_READY" (get-in r [:collision-resolution :import-ready-after-resolution?]))
      (System/exit (if (:ok r) 0 1)))))