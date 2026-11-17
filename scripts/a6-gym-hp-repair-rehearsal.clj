(ns odisseu.scripts.a6-gym-hp-repair-rehearsal
  "Ensaio de reparação seletiva HP ginásio — Datomic descartável apenas.
   Uso (no host com volume montado):
   STORAGE=/path/to/copy clojure -M -m odisseu.scripts.a6-gym-hp-repair-rehearsal
   Variáveis: ODISSEU_REPAIR_WIPE_TX (default 13194139535629)
              ODISSEU_REPAIR_PLAYER_HASH (default 6635c7f7bf15)
              ODISSEU_REPAIR_SAVE (default recovery-v1)
              ODISSEU_REPAIR_DRY_RUN (default false)"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as p])
  (:import [java.security MessageDigest]))

(defn- sha12 [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        b (.digest md (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) b)) 0 12)))

(defn- env
  [k default]
  (or (some-> (System/getenv k) str/trim not-empty) default))

(defn -main
  [& _]
  (let [storage (env "ODISSEU_BRIDGE_STORAGE"
                     (env "STORAGE" "/data/datomic-local"))
        system (env "ODISSEU_BRIDGE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_BRIDGE_DB" "core")
        wipe-tx (Long/parseLong (env "ODISSEU_REPAIR_WIPE_TX" "13194139535629"))
        player-hash (env "ODISSEU_REPAIR_PLAYER_HASH" "6635c7f7bf15")
        save-id (env "ODISSEU_REPAIR_SAVE" "recovery-v1")
        dry? (#{"1" "true" "yes"} (str/lower-case (env "ODISSEU_REPAIR_DRY_RUN" "false")))
        c (p/client {:system system :storage-dir storage})
        conn (d/connect c {:db-name db-name})
        db (d/db conn)
        hist (d/history db)
        saves (d/q '[:find ?sk ?pid ?sid
                     :where
                     [?s :save/key ?sk]
                     [?s :save/player ?pl]
                     [?pl :player/player-id ?pid]
                     [?s :save/slot-id ?sid]]
                   db)
        target (first (filter (fn [[_ pid sid]]
                                (and (= save-id sid) (= player-hash (sha12 pid))))
                              saves))]
    (when-not target
      (binding [*out* *err*]
        (println "TARGET_NOT_FOUND" {:player_hash player-hash :save save-id}))
      (System/exit 2))
    (let [[sk _pid _sid] target
          pokes (d/q '[:find ?e ?idp ?hp ?hpmax ?idx
                       :in $ ?sk
                       :where
                       [?s :save/key ?sk]
                       [?p :pokemon/save ?s]
                       [?p :pokemon/id-pokemon ?idp]
                       [?p :pokemon/hp-atual ?hp]
                       [?p :pokemon/hp ?hpmax]
                       [?p :pokemon/collection-index ?idx]
                       [(identity ?p) ?e]]
                     db sk)
          plans
          (vec
           (for [[e idp hp hpmax idx] pokes
                 :when (<= (long hp) 0)
                 :let [txes (sort-by first
                              (d/q '[:find ?tx ?h ?added
                                     :in $ ?e
                                     :where [?e :pokemon/hp-atual ?h ?tx ?added]]
                                   hist e))
                       prev (last (filter (fn [[tx h added]]
                                            (and added (pos? (long h))
                                                 (< (long tx) wipe-tx)))
                                          txes))
                       in-wipe? (boolean
                                 (some (fn [[tx h added]]
                                         (and added (= tx wipe-tx) (zero? (long h))))
                                       txes))]
                 :when (and in-wipe? prev)]
             {:e e
              :id_hash (sha12 idp)
              :idx (long idx)
              :hpmax (long hpmax)
              :restore_hp (long (second prev))
              :idp idp}))
          others-zero
          (count (filter (fn [[_ _ hp]] (<= (long hp) 0)) pokes))
          before-zeros others-zero
          tx-data (mapv (fn [{:keys [idp restore_hp]}]
                          [:db/add [:pokemon/id-pokemon idp]
                           :pokemon/hp-atual restore_hp])
                        plans)]
      (println :rehearsal
               {:storage storage :dry dry? :wipe-tx wipe-tx
                :player_hash player-hash :save save-id
                :plans (count plans) :before_zeros before-zeros})
      (when-not dry?
        (when (seq tx-data)
          (d/transact conn {:tx-data tx-data}))
        ;; idempotent second pass
        (let [db2 (d/db conn)
              pokes2 (d/q '[:find ?idp ?hp
                            :in $ ?sk
                            :where
                            [?s :save/key ?sk]
                            [?p :pokemon/save ?s]
                            [?p :pokemon/id-pokemon ?idp]
                            [?p :pokemon/hp-atual ?hp]]
                          db2 sk)
              still (filter (fn [[idp hp]]
                              (and (<= (long hp) 0)
                                   (some #(= (:idp %) idp) plans)))
                            pokes2)
              zeros2 (count (filter (fn [[_ hp]] (<= (long hp) 0)) pokes2))
              restored (count (filter (fn [[idp hp]]
                                        (some #(and (= (:idp %) idp)
                                                    (= (long hp) (:restore_hp %)))
                                              plans))
                                      pokes2))]
          (d/transact conn {:tx-data tx-data}) ; noop-ish second apply
          (let [db3 (d/db conn)
                pokes3 (d/q '[:find ?idp ?hp
                              :in $ ?sk
                              :where
                              [?s :save/key ?sk]
                              [?p :pokemon/save ?s]
                              [?p :pokemon/id-pokemon ?idp]
                              [?p :pokemon/hp-atual ?hp]]
                            db3 sk)
                zeros3 (count (filter (fn [[_ hp]] (<= (long hp) 0)) pokes3))]
            (println :after
                     {:restored restored
                      :still_zero_in_plan (count still)
                      :zeros_after zeros2
                      :zeros_after_idempotent zeros3
                      :ok (and (zero? (count still))
                               (= zeros2 zeros3)
                               (= restored (count plans)))})
            (spit (str storage "/repair-rehearsal-result.edn")
                  (pr-str {:player_hash player-hash
                           :save save-id
                           :wipe-tx wipe-tx
                           :restored (count plans)
                           :zeros_before before-zeros
                           :zeros_after zeros3
                           :plan_hashes (mapv :id_hash plans)}))
            (System/exit (if (and (zero? (count still)) (= zeros2 zeros3)) 0 1)))))
      (when dry?
        (println :dry_plans (count plans))
        (System/exit 0)))))
