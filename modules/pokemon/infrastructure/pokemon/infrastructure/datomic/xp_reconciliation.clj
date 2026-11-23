(ns pokemon.infrastructure.datomic.xp-reconciliation
  "ONE-SHOT administrative reconciliation. Only callable through the already
   running JVM bridge's Datomic connection while the gameplay gate is frozen.
   No second Datomic client. Same TX CAS: level, both XP fields, version, XP
   total and immutable metadata + unique receipt. Fails closed."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.domain.growth :as growth])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private protected-attrs
  [:pokemon/id-pokemon :pokemon/species :pokemon/nome
   :pokemon/nickname :pokemon/level
   :pokemon/hp :pokemon/hp-atual :pokemon/ataque :pokemon/defesa
   :pokemon/atq-esp :pokemon/def-esp :pokemon/veloc :pokemon/shiny
   :pokemon/ivs-edn :pokemon/golpes-edn :pokemon/tipos
   :pokemon/status :pokemon/location :pokemon/in-party
   :pokemon/collection-index :pokemon/save :pokemon/key])

(def ^:private fixed-species "charmander")
(def ^:private fixed-level 6)
(def ^:private fixed-raw 21)
(def ^:private fixed-target 8)
(def ^:private fixed-origin "owner-approved-compensation-not-historical")

(defn- sha256 [x]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (pr-str x) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff (int %))) bytes))))

(defn- snapshot [p]
  ;; Stable ordered representation (no private player/save identifiers exposed).
  (into (sorted-map) (map (fn [[k v]] [(name k) v])
                          (select-keys p (concat protected-attrs
                                                 [:pokemon/xp :pokemon/xp-desde-nivel
                                                  :pokemon/xp-total
                                                  :pokemon/xp-progress-version
                                                  :pokemon/xp-legacy-residual
                                                  :pokemon/xp-legacy-nivel])))))

(defn- read-one [db pokemon-id]
  (d/pull db '[*] [:pokemon/id-pokemon pokemon-id]))

(defn- blocked! [code details]
  (throw (ex-info (name code) (assoc details :code code))))

(defn- ensure-id! [pokemon-id allowed-id]
  (when-not (and (string? pokemon-id) (string? allowed-id)
                 (re-matches #"[0-9a-fA-F-]{36}" pokemon-id)
                 (= pokemon-id allowed-id))
    (blocked! :reconciliation-id-not-authorized {})))

(defn- receipt [db operation-id]
  (d/pull db '[*] [:operation-receipt/id operation-id]))

(defn- op-id [pokemon-id]
  (str "a6-v3-charmander-compensation:" pokemon-id ":v1"))

(defn- payload-hash [pokemon-id]
  (sha256 [pokemon-id fixed-level fixed-raw fixed-target fixed-origin]))

(defn- duplicate! [db pokemon-id]
  (let [prior (receipt db (op-id pokemon-id))
        r (read-one db pokemon-id)
        expected-hash (payload-hash pokemon-id)]
    (when (:db/id prior)
      (when-not (and (= expected-hash (:operation-receipt/payload-hash prior))
                     (= fixed-level (:pokemon/level r))
                     (= fixed-target (:pokemon/xp-desde-nivel r))
                     (= fixed-target (:pokemon/xp r))
                     (= "flat-9-v2" (:pokemon/xp-progress-version r))
                     (= fixed-raw (:pokemon/xp-legacy-residual r))
                     (= fixed-level (:pokemon/xp-legacy-nivel r)))
        (blocked! :reconciliation-receipt-conflict {}))
      {:status :duplicate :pokemon-id pokemon-id
       :level fixed-level :residual fixed-target
       :receipt-id (op-id pokemon-id)})))

(defn- ensure-preconditions! [p]
  (when-not (and (:db/id p)
                 (= fixed-species (str/lower-case (str (:pokemon/species p))))
                 (= fixed-level (:pokemon/level p))
                 (= fixed-raw (:pokemon/xp p))
                 (= fixed-raw (:pokemon/xp-desde-nivel p))
                 (nil? (:pokemon/xp-progress-version p))
                 (nil? (:pokemon/xp-legacy-residual p))
                 (nil? (:pokemon/xp-legacy-nivel p))
                 (every? some? (map p [:pokemon/id-pokemon :pokemon/hp
                                       :pokemon/hp-atual :pokemon/ataque
                                       :pokemon/defesa :pokemon/atq-esp
                                       :pokemon/def-esp :pokemon/veloc])))
    (blocked! :reconciliation-snapshot-drift
              {:level (:pokemon/level p)
               :version (:pokemon/xp-progress-version p)}))
  p)

(defn preview
  "Read-only preview on JVM's existing Datomic connection. Caller must
   independently verify that the currently deployed bridge is frozen."
  [conn pokemon-id allowed-id]
  (ensure-id! pokemon-id allowed-id)
  (let [db (d/db conn)]
    (if-let [dup (duplicate! db pokemon-id)]
      dup
      (let [p (ensure-preconditions! (read-one db pokemon-id))]
        {:status :ready
         :pokemon-id pokemon-id
         :species fixed-species
         :level fixed-level :raw-xp fixed-raw
         :residual-before fixed-raw :residual-after fixed-target
         :snapshot-sha256 (sha256 (snapshot p))
         :receipt-id (op-id pokemon-id)
         :origin fixed-origin
         :scope "only-xp-fields-and-receipt"}))))

(defn reconcile!
  "The only mutation. Requires snapshot hash returned by preview in the same
   frozen JVM, and the hardcoded one-shot compensation policy. Do not call
   this outside the authenticated maintenance route. Do not retry CAS failure."
  [conn pokemon-id allowed-id expected-snapshot]
  (ensure-id! pokemon-id allowed-id)
  (when-not (and (string? expected-snapshot)
                 (re-matches #"[0-9a-f]{64}" expected-snapshot))
    (blocked! :reconciliation-missing-fingerprint {}))
  (let [db (d/db conn)]
    (if-let [dup (duplicate! db pokemon-id)]
      dup
      (let [p (ensure-preconditions! (read-one db pokemon-id))
            current (sha256 (snapshot p))]
        (when-not (= current expected-snapshot)
          (blocked! :reconciliation-fingerprint-mismatch {}))
        (let [eid (:db/id p)
              flat-total (growth/flat9-synthetic-total fixed-level fixed-target)
              ;; No-op CAS on observed immutable scalar values guards against
              ;; concurrent changes within Datomic itself, not just JVM gate.
              immutable-cas (for [attr protected-attrs
                                  :when (and (not= attr :pokemon/level)
                                             (some? (get p attr))
                                             (not (coll? (get p attr))))]
                              [:db/cas eid attr (get p attr) (get p attr)])
              xp-total-cas [:db/cas eid :pokemon/xp-total
                            (:pokemon/xp-total p) (long flat-total)]
              tx (vec (concat immutable-cas
                              [[:db/cas eid :pokemon/level fixed-level fixed-level]
                               [:db/cas eid :pokemon/xp fixed-raw fixed-target]
                               [:db/cas eid :pokemon/xp-desde-nivel fixed-raw fixed-target]
                               [:db/cas eid :pokemon/xp-progress-version nil
                                growth/flat9-progress-version]
                               xp-total-cas
                               [:db/cas eid :pokemon/xp-legacy-residual nil fixed-raw]
                               [:db/cas eid :pokemon/xp-legacy-nivel nil fixed-level]
                               {:operation-receipt/id (op-id pokemon-id)
                                :operation-receipt/kind "flat9-compensation-v3"
                                :operation-receipt/channel "maintenance"
                                :operation-receipt/payload-hash (payload-hash pokemon-id)
                                :operation-receipt/summary
                                (pr-str {:policy fixed-origin
                                         :pokemon-id pokemon-id
                                         :level-before fixed-level
                                         :level-after fixed-level
                                         :raw-xp-before fixed-raw
                                         :residual-after fixed-target
                                         :snapshot-sha256 current})}]))]
          (try
            (d/transact conn {:tx-data tx})
            (let [db-after (d/db conn)
                  result (or (duplicate! db-after pokemon-id)
                             (blocked! :reconciliation-receipt-not-committed {}))]
              (assoc result :status :applied))
            (catch Exception e
              (if-let [dup (duplicate! (d/db conn) pokemon-id)]
                dup
                (blocked! :reconciliation-atomic-cas-failed
                          {:cause (ex-message e)})))))))))
