(ns a6-fase7-integrity-manifest
  "Fase 7 — manifesto de integridade Datomic (cópia isolada ou storage RO).

  Emite contagens + hashes canônicos por jogador/slot sem IDs literais.
  Não escreve no banco (somente leitura via d/db).

  Uso:
    ODISSEU_PROBE_STORAGE=/path/to/datomic-parent \\
    ODISSEU_PROBE_SYSTEM=odisseu-m90-core \\
    ODISSEU_PROBE_DB=core \\
    ODISSEU_FASE7_OUT=/path/report.edn \\
    clojure -M -e '(load-file \"scripts/a6_fase7_integrity_manifest.clj\")'"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.identities :as id]
            [pokemon.domain.reward :as reward]))

(defn- env [k default]
  (or (System/getenv k) default))

(defn- anon12 [x]
  (when x (subs (reward/payload-hash {:id (str x)}) 0 12)))

(defn- attr-exists? [db ident]
  (some? (d/pull db '[:db/ident] ident)))

(defn- count-attr [db attr]
  (if-not (attr-exists? db attr)
    0
    (or (ffirst (d/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db attr)) 0)))

(defn- sum-coins [db]
  (if-not (attr-exists? db :save/coins)
    0
    (or (ffirst (d/q '[:find (sum ?c) :where [?e :save/coins ?c]] db)) 0)))

(defn- migrations [db]
  (->> (d/q '[:find ?id :where [?e :odisseu.schema/migration-id ?id]] db)
       (map first)
       sort
       vec))

(defn- inventory-digest [db save-eid]
  (let [rows (->> (d/q '[:find ?sku ?qty
                         :in $ ?s
                         :where
                         [?i :inventory-item/save ?s]
                         [?i :inventory-item/item-id ?sku]
                         [?i :inventory-item/qty ?qty]]
                       db save-eid)
                  (map (fn [[sku qty]] [(str sku) (long qty)]))
                  sort
                  vec)]
    {:sku-count (count rows)
     :digest (subs (reward/payload-hash {:skus rows}) 0 16)
     :qty-sum (reduce + 0 (map second rows))}))

(defn- pokemon-rows [db save-eid]
  (->> (d/q '[:find (pull ?p [:pokemon/id-pokemon :pokemon/level :pokemon/xp
                              :pokemon/hp-atual :pokemon/hp :pokemon/species
                              :pokemon/shiny :pokemon/in-party])
              :in $ ?s
              :where [?p :pokemon/save ?s]]
            db save-eid)
       (map first)
       (map (fn [p]
              {:id (str (:pokemon/id-pokemon p))
               :level (long (:pokemon/level p 1))
               :xp (long (:pokemon/xp p 0))
               :hp (long (or (:pokemon/hp-atual p) (:pokemon/hp p) 0))
               :species (str (:pokemon/species p ""))
               :shiny (boolean (:pokemon/shiny p))
               :in-party (boolean (:pokemon/in-party p))}))
       vec))

(defn- pokemon-digest [pokes]
  (let [ids (sort (map :id pokes))
        canon (mapv (fn [p]
                      [(:id p) (:level p) (:xp p) (:hp p)
                       (:species p) (:shiny p) (:in-party p)])
                    (sort-by :id pokes))]
    {:count (count pokes)
     :ids-digest (subs (reward/payload-hash {:ids ids}) 0 16)
     :state-digest (subs (reward/payload-hash {:rows canon}) 0 16)
     :level-sum (reduce + 0 (map :level pokes))
     :xp-sum (reduce + 0 (map :xp pokes))
     :hp-sum (reduce + 0 (map :hp pokes))
     :party-count (count (filter :in-party pokes))
     :shiny-count (count (filter :shiny pokes))
     ;; ids individuais só como hashes curtos — sem PII
     :id-hashes (mapv #(subs (reward/payload-hash {:pokemon-id %}) 0 12) ids)}))

(defn- receipt-stats [db save]
  (let [save-key (:save/key save)
        slot (:save/slot-id save)
        n (if-not (attr-exists? db :operation-receipt/id)
            0
            (or (ffirst
                 (d/q '[:find (count ?r)
                        :in $ ?slot
                        :where
                        [?r :operation-receipt/id]
                        [?r :operation-receipt/save-id ?slot]]
                      db (str slot)))
                0))
        pending (when (attr-exists? db :save/recompensas-pendentes-edn)
                  (:save/recompensas-pendentes-edn save))]
    {:receipt-count (long n)
     :save-key-hash (when save-key (anon12 save-key))
     :pending-rewards-present? (boolean (and pending (not (str/blank? (str pending)))))
     :pending-rewards-bytes (if pending (count (str pending)) 0)}))

(defn- economy-stats [db save]
  {:coins (long (:save/coins save 0))
   :coins-operational (when (contains? save :save/coins-operational)
                        (long (:save/coins-operational save)))
   :economy-status (:save/economy-status save)
   :historical-status (:save/coins-historical-status save)
   :historical-amount (when (contains? save :save/coins-historical-amount)
                        (long (:save/coins-historical-amount save)))})

(defn- save-manifest [db player save]
  (let [eid (:db/id save)
        pokes (pokemon-rows db eid)
        inv (inventory-digest db eid)
        eco (economy-stats db save)
        rec (receipt-stats db save)
        slot (:save/slot-id save)
        body {:slot slot
              :origin (:save/origin save)
              :legacy-version (:save/legacy-version save)
              :import-status (:save/import-status save)
              :revision (long (:save/revision save 0))
              :trainer-level (long (:save/trainer-level save 0))
              :trainer-xp (long (:save/trainer-xp save 0))
              :vitorias (long (:save/vitorias-treinador save 0))
              :pokemon (pokemon-digest pokes)
              :inventory inv
              :economy eco
              :receipts rec}]
    (assoc body
           :canonical-hash (reward/payload-hash (dissoc body :pokemon))
           :pokemon-state-hash (get-in body [:pokemon :state-digest])
           :pokemon-ids-hash (get-in body [:pokemon :ids-digest]))))

(defn- player-manifest
  [db player]
  (let [parsed (try (id/parse-player-key (:player/key player))
                    (catch Exception _ nil))
        chat (:chat-id parsed)
        pid (:player-id parsed)
        saves (->> (d/q '[:find (pull ?s [*])
                          :in $ ?p
                          :where [?s :save/player ?p]]
                        db (:db/id player))
                   (map first)
                   vec)
        slots (mapv #(save-manifest db player %)
                    (sort-by :save/slot-id saves))
        active (:player/active-save-id player)
        fence (long (:player/fence-generation player 0))
        body {:player-hash (anon12 pid)
              :chat-hash (anon12 chat)
              :active-save-id active
              :fence-generation fence
              :slot-count (count slots)
              :slots slots}]
    (assoc body
           :player-canonical-hash
           (reward/payload-hash
            {:active active
             :fence fence
             :slots (mapv (fn [s]
                            [(:slot s)
                             (:canonical-hash s)
                             (:pokemon-ids-hash s)
                             (:pokemon-state-hash s)])
                          slots)}))))

(defn- globals [db]
  (let [m (migrations db)]
    {:migrations m
     :migration-count (count m)
     :has-028? (boolean (some #{"pokemon/028-economy-ledger"} m))
     :has-029? (boolean (some #{"pokemon/029-raid-lobby"} m))
     :has-030? (boolean (some #{"pokemon/030-raid-auto"} m))
     :players (count-attr db :player/key)
     :saves (count-attr db :save/key)
     :pokemon (count-attr db :pokemon/key)
     :pokemon-id (count-attr db :pokemon/id-pokemon)
     :coins-sum (sum-coins db)
     :inventory-items (count-attr db :inventory-item/key)
     :operation-receipts (count-attr db :operation-receipt/id)
     :coin-ledger (count-attr db :coin-ledger/key)
     :raids (count-attr db :raid/id)
     :battles (count-attr db :battle/id)}))

(defn- compare-manifests
  "Compara dois mapas de globals + maps player-hash→canonical."
  [src dst]
  (let [sg (:globals src) dg (:globals dst)
        sp (into {} (map (juxt :player-hash :player-canonical-hash) (:players src)))
        dp (into {} (map (juxt :player-hash :player-canonical-hash) (:players dst)))
        missing-players (sort (remove dp (keys sp)))
        extra-players (sort (remove sp (keys dp)))
        changed (->> sp
                     (keep (fn [[h ch]]
                             (when-let [dh (get dp h)]
                               (when (not= ch dh)
                                 h))))
                     sort
                     vec)
        count-keys [:players :saves :pokemon :pokemon-id :coins-sum
                    :inventory-items :operation-receipts]
        count-diffs (into {}
                          (keep (fn [k]
                                  (let [a (get sg k) b (get dg k)]
                                    (when (not= a b) [k {:src a :dst b}])))
                                count-keys))
        intentional-schema
        (cond-> []
          (and (not (:has-029? sg)) (:has-029? dg)) (conj :applied-029-raid-lobby)
          (and (not (:has-030? sg)) (:has-030? dg)) (conj :applied-030-raid-auto)
          (not= (:migration-count sg) (:migration-count dg))
          (conj :migration-count-delta))
        unexpected-counts (dissoc count-diffs)
        block? (or (seq missing-players)
                   (seq extra-players)
                   (seq changed)
                   (seq unexpected-counts))]
    {:missing-players missing-players
     :extra-players extra-players
     :changed-player-hashes changed
     :count-diffs count-diffs
     :intentional-schema intentional-schema
     :unexpected-divergences (boolean (or (seq missing-players)
                                          (seq extra-players)
                                          (seq changed)
                                          (seq unexpected-counts)))
     :block-migration? (boolean block?)}))

(defn -main [& _args]
  (let [storage (env "ODISSEU_PROBE_STORAGE" nil)
        system (env "ODISSEU_PROBE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_PROBE_DB" "core")
        out (env "ODISSEU_FASE7_OUT" nil)
        label (env "ODISSEU_FASE7_LABEL" "fase7")
        compare-path (env "ODISSEU_FASE7_COMPARE" nil)]
    (when (or (str/blank? storage) (str/blank? out))
      (binding [*out* *err*]
        (println "ODISSEU_PROBE_STORAGE e ODISSEU_FASE7_OUT obrigatórios"))
      (System/exit 2))
    (when (re-find #"(?i)oracle|odisseu-live|/data/datomic-local$|odisseu-core-a56-datomic"
                    (str storage))
      (binding [*out* *err*]
        (println "Path de storage parece volume live — abortando por segurança:" storage))
      (System/exit 3))
    (when-not (re-find #"(?i)odisseu-recovery|fase7|restore-check|rehearsal|\.datomic-local"
                       (str storage))
      (binding [*out* *err*]
        (println "Storage fora de área descartável (odisseu-recovery/fase7):" storage))
      (System/exit 3))
    (println "fase7.storage=" storage "system=" system "db=" db-name)
    (let [client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          db (d/db conn)
          g (globals db)
          players (->> (d/q '[:find (pull ?p [*]) :where [?p :player/key]] db)
                       (map first)
                       (map #(player-manifest db %))
                       (sort-by :player-hash)
                       vec)
          report {:phase "fase7-datomic-manifest"
                  :label label
                  :system system
                  :db db-name
                  :privacy "player/chat hashed sha12; pokemon ids hashed"
                  :globals g
                  :player-count (count players)
                  :players players
                  :aggregate
                  {:slot-count (reduce + 0 (map :slot-count players))
                   :pokemon-count-sum (reduce + 0 (mapcat (fn [p]
                                                            (map #(get-in % [:pokemon :count] 0)
                                                                 (:slots p)))
                                                          players))
                   :coins-sum-saves (reduce + 0 (mapcat (fn [p]
                                                          (map #(get-in % [:economy :coins] 0)
                                                               (:slots p)))
                                                        players))}}]
      (io/make-parents out)
      (spit out (with-out-str (pp/pprint report)))
      (println "wrote" out)
      (pp/pprint (select-keys report [:label :player-count :globals :aggregate]))
      (when-not (str/blank? compare-path)
        (let [src (read-string (slurp compare-path))
              cmp (compare-manifests src report)
              cmp-out (str out ".compare.edn")]
          (spit cmp-out (with-out-str (pp/pprint cmp)))
          (println "compare->" cmp-out)
          (pp/pprint cmp)
          (when (:block-migration? cmp)
            (binding [*out* *err*]
              (println "BLOCK: divergências inesperadas — ver compare"))
            (System/exit 4))))
      (System/exit 0))))

(-main)
