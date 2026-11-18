#!/usr/bin/env bb
;; Ensaio isolado: inventário + simulação ensure-flat9-v2 (SEM transact).
;; Env: ODISSEU_BRIDGE_SYSTEM, ODISSEU_BRIDGE_STORAGE, ODISSEU_BRIDGE_DB, A6_EVID
(require '[datomic.client.api :as d]
         '[odisseu.persistence.datomic :as persistence]
         '[pokemon.domain.growth :as growth]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def evid (or (System/getenv "A6_EVID") "/tmp/flat9-reh-evid"))
(.mkdirs (io/file evid))

(def client (persistence/client {:system (or (System/getenv "ODISSEU_BRIDGE_SYSTEM") "odisseu-m90-core")
                                 :storage-dir (or (System/getenv "ODISSEU_BRIDGE_STORAGE") "/data/datomic-local")
                                 :maintenance-control? true}))
(def conn (persistence/ensure-db! client (or (System/getenv "ODISSEU_BRIDGE_DB") "core")))
(def db (d/db conn))

(defn attr-exists? [attr] (some? (d/pull db '[:db/ident] attr)))
(defn count-attr [attr]
  (if-not (attr-exists? attr) 0
    (or (ffirst (d/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db attr)) 0)))

(defn sha256-hex [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        dig (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) dig))))

(def players (max (count-attr :player/id) (count-attr :player/key) (count-attr :trainer/player-id)))
(def saves (count-attr :save/key))
(def pokemon-n (count-attr :pokemon/id-pokemon))
(def receipts (count-attr :operation-receipt/id))
(def eggs (count-attr :egg/id))
(def coins (long (or (when (attr-exists? :save/coins-operational)
                       (ffirst (d/q '[:find (sum ?c) :where [?e :save/coins-operational ?c]] db)))
                     (when (attr-exists? :save/coins)
                       (ffirst (d/q '[:find (sum ?c) :where [?e :save/coins ?c]] db)))
                     0)))
(def inv-qty (long (or (when (attr-exists? :inventory-item/qty)
                         (ffirst (d/q '[:find (sum ?q) :where [?e :inventory-item/qty ?q]] db))) 0)))

(def pull-pat '[:pokemon/id-pokemon :pokemon/level :pokemon/xp :pokemon/species
                :pokemon/nome :pokemon/shiny :pokemon/xp-progress-version
                :pokemon/xp-desde-nivel :pokemon/xp-legacy-nivel :pokemon/xp-legacy-residual
                :pokemon/xp-total :pokemon/growth-rate :pokemon/hp :pokemon/hp-atual
                :pokemon/ataque :pokemon/defesa :pokemon/atq-esp :pokemon/def-esp
                :pokemon/veloc :pokemon/golpes :pokemon/ivs])

(def poke-eids (mapv first (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db)))

(defn entity->reg [p]
  {:id-pokemon (str (:pokemon/id-pokemon p))
   :species (str (or (:pokemon/species p) ""))
   :nome (str (or (:pokemon/nome p) ""))
   :nivel (long (or (:pokemon/level p) 1))
   :xp (long (or (:pokemon/xp p) 0))
   :xp-desde-nivel (long (or (:pokemon/xp-desde-nivel p) (:pokemon/xp p) 0))
   :xp-total (when-let [t (:pokemon/xp-total p)] (long t))
   :xp-progress-version (when-let [v (:pokemon/xp-progress-version p)] (str v))
   :xp-legacy-nivel (when-let [v (:pokemon/xp-legacy-nivel p)] (long v))
   :xp-legacy-residual (when-let [v (:pokemon/xp-legacy-residual p)] (long v))
   :growth-rate (when-let [g (:pokemon/growth-rate p)] (keyword g))
   :shiny (boolean (:pokemon/shiny p))
   :hp (long (or (:pokemon/hp p) 0))
   :hp-atual (long (or (:pokemon/hp-atual p) 0))
   :ataque (long (or (:pokemon/ataque p) 0))
   :defesa (long (or (:pokemon/defesa p) 0))
   :atq-esp (long (or (:pokemon/atq-esp p) 0))
   :def-esp (long (or (:pokemon/def-esp p) 0))
   :veloc (long (or (:pokemon/veloc p) 0))
   :golpes (:pokemon/golpes p)
   :ivs (:pokemon/ivs p)})

(def rows
  (->> poke-eids
       (map (fn [e] (entity->reg (d/pull db pull-pat e))))
       (sort-by :id-pokemon)
       vec))

(defn classify [r]
  (let [ver (str (or (:xp-progress-version r) ""))]
    (cond
      (= ver growth/flat9-progress-version) :flat-9-v2
      (= ver growth/growth-v1-progress-version) :growth-v1
      (= ver growth/growth-v2-progress-version) :growth-v2
      (growth/ambiguous-legacy? r) :ambiguous
      (growth/proven-flat9-legacy? r) :legacy-flat9
      (and (nil? (:xp-total r))
           (nil? (:xp-progress-version r))
           (zero? (long (:xp-desde-nivel r 0)))
           (zero? (long (:xp r 0)))) :unknown-or-empty
      :else :other)))

(def by-ver (frequencies (map classify rows)))

(def simulations
  (mapv (fn [r]
          (let [c (growth/ensure-flat9-v2 r)
                converted? (= (str (:xp-progress-version c)) growth/flat9-progress-version)]
            {:id (:id-pokemon r)
             :species (:species r)
             :class (classify r)
             :nivel-antes (:nivel r)
             :nivel-depois (:nivel c)
             :resid-antes (:xp-desde-nivel r)
             :resid-depois (when converted? (:xp-desde-nivel c))
             :converted? converted?
             :level-delta (- (long (:nivel c)) (long (:nivel r)))
             :hp-antes (:hp r)
             :ataque-antes (:ataque r)
             :shiny (:shiny r)}))
        rows))

(def level-changes (filterv #(not (zero? (:level-delta %))) simulations))
(def ambiguous (filterv #(= :ambiguous (:class %)) simulations))
(def converted-ok (filterv :converted? simulations))
(def left-ambiguous (filterv #(and (= :ambiguous (:class %)) (not (:converted? %))) simulations))

(def repaired-check
  (for [[sp niv] [["blastoise" 74] ["tyranitar" 90] ["crobat" 5] ["amaura" 4]]]
    (let [matches (filter #(= (str/lower-case (:species %)) sp) simulations)]
      {:species sp :expected-nivel niv
       :matches (mapv #(select-keys % [:id :nivel-antes :nivel-depois :class :resid-antes]) matches)})))

(def report
  {:generatedAt (str (java.time.Instant/now))
   :aggregates {:players players :saves saves :pokemon pokemon-n :receipts receipts
                :coins coins :inv-qty inv-qty :eggs eggs
                :datoms (:datoms (d/db-stats db))}
   :by-version by-ver
   :conversion {:simulated (count simulations)
                :converted (count converted-ok)
                :level-changes (count level-changes)
                :ambiguous (count ambiguous)
                :left-ambiguous (count left-ambiguous)}
   :level-change-samples (vec (take 20 level-changes))
   :ambiguous-samples (vec (take 30 (map #(select-keys % [:id :species :nivel-antes :resid-antes]) ambiguous)))
   :repaired-pokemon repaired-check
   :pokemon-fingerprint (sha256-hex (pr-str (mapv (fn [r]
                                                   [(:id-pokemon r) (:nivel r) (:xp-desde-nivel r)
                                                    (:species r) (:shiny r) (:xp-progress-version r)])
                                                 rows)))})

(spit (io/file evid "flat9-conversion-rehearsal.edn") (pr-str report))
(spit (io/file evid "flat9-conversion-summary.edn")
      (pr-str {:aggregates (:aggregates report)
               :by-version by-ver
               :conversion (:conversion report)
               :level-changes (count level-changes)
               :repaired repaired-check
               :pokemon-fingerprint (:pokemon-fingerprint report)}))

(println "FLAT9_REH_OK")
(prn {:aggregates (:aggregates report)
      :by-version by-ver
      :conversion (:conversion report)
      :level-changes (count level-changes)
      :repaired repaired-check})

(when (pos? (count level-changes))
  (println "FAIL: conversion altered levels")
  (System/exit 2))
(System/exit 0)
