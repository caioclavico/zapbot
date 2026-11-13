#!/usr/bin/env clojure
;; Ensaio isolado growth-v1 → growth-v2.
;; NÃO conectar a volumes live. Uso típico (container com storage do restore):
;;   clojure -M:bridge-http -e "(load-file \"/work/scripts/a6_growth_v2_rehearsal.clj\")"
;; Variáveis: ODISSEU_BRIDGE_STORAGE, ODISSEU_BRIDGE_SYSTEM, ODISSEU_BRIDGE_DB,
;;            A6_GV2_EVID (dir para relatórios), A6_GV2_MODE=inventory|convert|gameplay|all
(ns a6.growth-v2-rehearsal
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.store :as store]))

(def storage (or (System/getenv "ODISSEU_BRIDGE_STORAGE") "/data/datomic-local"))
(def system-name (or (System/getenv "ODISSEU_BRIDGE_SYSTEM") "odisseu-m90-core"))
(def db-name (or (System/getenv "ODISSEU_BRIDGE_DB") "core"))
(def evid-dir (or (System/getenv "A6_GV2_EVID") "/tmp/a6-gv2-evid"))
(def mode (or (System/getenv "A6_GV2_MODE") "all"))

(defn- spit! [name data]
  (.mkdirs (io/file evid-dir))
  (let [f (io/file evid-dir name)]
    (spit f (if (string? data) data (pr-str data)))
    (println "wrote" (.getPath f))))

(defn- sha12 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        dig (.digest md (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) dig)) 0 12)))

(defn- markers [db]
  (->> (d/q '[:find ?m :where [_ :odisseu.schema/migration-id ?m]] db)
       (map first) sort vec))

(defn- pull-pokemon [db e]
  (d/pull db
          '[:pokemon/id-pokemon :pokemon/species :pokemon/nickname :pokemon/level
            :pokemon/xp :pokemon/xp-desde-nivel :pokemon/xp-total
            :pokemon/xp-progress-version :pokemon/growth-rate
            :pokemon/xp-legacy-residual :pokemon/xp-legacy-nivel
            :pokemon/shiny :pokemon/hp :pokemon/hp-atual
            :pokemon/ataque :pokemon/defesa :pokemon/atq-esp :pokemon/def-esp
            :pokemon/veloc :pokemon/amizade :pokemon/golpes-edn :pokemon/ivs-edn
            {:pokemon/save [:save/key]}]
          e))

(defn- entity->row [ent]
  (let [nivel (long (or (:pokemon/level ent) 1))
        ver (some-> (:pokemon/xp-progress-version ent) str)
        residual (when (or (some? (:pokemon/xp-desde-nivel ent))
                           (some? (:pokemon/xp ent))
                           (some? (:pokemon/xp-total ent))
                           (some? ver)
                           (some? (:pokemon/xp-legacy-residual ent)))
                   (long (or (:pokemon/xp-desde-nivel ent)
                             (:pokemon/xp ent)
                             0)))
        known? (boolean residual)
        rate (or (some-> (:pokemon/growth-rate ent) keyword)
                 (when (= ver "growth-v1")
                   (growth/growth-rate-for-species (:pokemon/species ent))))
        span-old (cond
                   (= ver "growth-v2") (growth/level-span-simple nivel)
                   (and rate (not= rate :simple)) (growth/level-span rate nivel)
                   :else nil)
        pct (when (and known? span-old (pos? span-old) (< nivel 100))
              (/ (double residual) (double span-old)))]
    {:id (:pokemon/id-pokemon ent)
     :owner-hash (sha12 (str (get-in ent [:pokemon/save :save/key])))
     :species (:pokemon/species ent)
     :nickname (:pokemon/nickname ent)
     :nivel nivel
     :ver (or ver (if known? "legacy-unversioned" "unknown-xp"))
     :residual residual
     :xp-total (:pokemon/xp-total ent)
     :growth-rate rate
     :span-old span-old
     :pct pct
     :shiny (boolean (:pokemon/shiny ent))
     :hp (long (or (:pokemon/hp ent) 0))
     :hp-atual (long (or (:pokemon/hp-atual ent) 0))
     :ataque (:pokemon/ataque ent)
     :defesa (:pokemon/defesa ent)
     :atq-esp (:pokemon/atq-esp ent)
     :def-esp (:pokemon/def-esp ent)
     :veloc (:pokemon/veloc ent)
     :amizade (:pokemon/amizade ent)
     :golpes-edn (:pokemon/golpes-edn ent)
     :ivs-edn (:pokemon/ivs-edn ent)
     :legacy-residual (:pokemon/xp-legacy-residual ent)
     :legacy-nivel (:pokemon/xp-legacy-nivel ent)
     :known? known?}))

(defn- load-rows [db]
  (let [ids (map first (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db))]
    (mapv (comp entity->row #(pull-pokemon db %)) ids)))

(defn- inventory-summary [rows]
  (let [by-ver (frequencies (map :ver rows))
        unknown (count (filter #(= "unknown-xp" (:ver %)) rows))
        lvl100 (count (filter #(= 100 (:nivel %)) rows))
        level-hist (->> rows (map :nivel) frequencies (sort-by key))
        inconsistent
        (filter (fn [r]
                  (and (= "growth-v1" (:ver r))
                       (:xp-total r)
                       (:residual r)
                       (:growth-rate r)
                       (let [base (growth/total-xp-for-level (:growth-rate r) (:nivel r))
                             expect (+ base (long (:residual r 0)))]
                         (not= expect (long (:xp-total r))))))
                rows)]
    {:pokemon-count (count rows)
     :owners (count (distinct (map :owner-hash rows)))
     :by-version by-ver
     :unknown-xp unknown
     :level-100 lvl100
     :level-histogram (into {} level-hist)
     :sum-nivel (reduce + 0 (map :nivel rows))
     :sum-hp (reduce + 0 (map :hp rows))
     :sum-hp-atual (reduce + 0 (map :hp-atual rows))
     :shiny-count (count (filter :shiny rows))
     :inconsistent-xp-total (count inconsistent)
     :known-xp (count (filter :known? rows))}))

(defn- integrity-fp [rows]
  {:count (count rows)
   :ids (sort (map :id rows))
   :species (sort (map (juxt :id :species) rows))
   :niveis (sort (map (juxt :id :nivel) rows))
   :hp (sort (map (juxt :id :hp :hp-atual) rows))
   :shiny (sort (map (juxt :id :shiny) rows))
   :golpes (sort (map (juxt :id :golpes-edn) rows))
   :ivs (sort (map (juxt :id :ivs-edn) rows))
   :stats (sort (map (juxt :id :ataque :defesa :atq-esp :def-esp :veloc :amizade) rows))})

(defn- convert-row [row]
  (let [reg {:species (:species row)
             :nivel (:nivel row)
             :xp-desde-nivel (:residual row)
             :xp (:residual row)
             :xp-total (:xp-total row)
             :xp-progress-version (when (and (:ver row)
                                             (not (#{"legacy-unversioned" "unknown-xp"} (:ver row))))
                                    (:ver row))
             :growth-rate (:growth-rate row)
             :xp-legacy-residual (:legacy-residual row)
             :xp-legacy-nivel (:legacy-nivel row)}
        ;; XP desconhecido: não inventar residual na conversão em massa.
        skip? (= "unknown-xp" (:ver row))]
    (if skip?
      {:id (:id row)
       :skipped true
       :reason :unknown-xp
       :nivel-antes (:nivel row)
       :nivel-depois (:nivel row)
       :ver-antes (:ver row)
       :ver-depois (:ver row)}
      (let [v2 (growth/convert-to-growth-v2 reg)
            again (growth/convert-to-growth-v2 v2)
            span (growth/level-span-simple (long (:nivel v2)))
            pct-new (when (and (pos? span) (< (long (:nivel v2)) 100))
                      (/ (double (:xp-desde-nivel v2 0)) (double span)))]
        {:id (:id row)
         :skipped false
         :nivel-antes (:nivel row)
         :nivel-depois (long (:nivel v2))
         :ver-antes (:ver row)
         :ver-depois (:xp-progress-version v2)
         :pct-antes (:pct row)
         :pct-depois pct-new
         :residual-antes (:residual row)
         :residual-depois (:xp-desde-nivel v2)
         :span-depois span
         :idempotent? (= (:xp-total v2) (:xp-total again))
         :level-preserved? (= (long (:nivel row)) (long (:nivel v2)))
         :converted v2}))))

(defn- persist-conversion!
  "Persiste growth-v2 somente no restore isolado via store grant path-like attrs."
  [conn rows-before conversions]
  (let [by-id (into {} (map (juxt :id identity) rows-before))]
    (doseq [c conversions
            :when (and (not (:skipped c)) (:converted c))]
      (let [id (:id c)
            v2 (:converted c)
            lookup [:pokemon/id-pokemon id]
            tx (cond-> [[:db/add lookup :pokemon/xp-progress-version (str (:xp-progress-version v2))]
                        [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel v2 0))]
                        [:db/add lookup :pokemon/xp (long (:xp-desde-nivel v2 0))]
                        [:db/add lookup :pokemon/xp-total (long (:xp-total v2 0))]
                        [:db/add lookup :pokemon/growth-rate (str (:growth-rate v2))]
                        [:db/add lookup :pokemon/level (long (:nivel v2))]]
                 true identity)]
        (d/transact conn {:tx-data tx})))
    true))

(defn- gameplay-suite [store]
  (let [chat "gv2-reh-chat"
        player "gv2-reh-player"
        _ (try (trainer/create-trainer! store {:chat-id chat :player-id player})
               (catch Exception _ nil))
        mk (fn [id sp nv ver residual]
             (let [base {:id-pokemon id :species sp :nome (str/capitalize sp)
                         :nivel nv :hp 50 :hp-atual 50
                         :ataque 40 :defesa 30 :atq-esp 40 :def-esp 40 :veloc 50
                         :tipos ["normal"] :amizade 70
                         :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                                   :poder 40 :classe :fisico}]}
                   with-xp (case ver
                             :v2 (let [span (growth/level-span-simple nv)
                                       r (min (dec span) (long residual))]
                                   (assoc base
                                          :xp-progress-version "growth-v2"
                                          :growth-rate :simple
                                          :xp-desde-nivel r :xp r
                                          :xp-total (+ (growth/total-xp-for-level-simple nv) r)))
                             :v1 (let [rate (growth/growth-rate-for-species sp)
                                       span (growth/level-span rate nv)
                                       r (min (max 0 (dec span)) (long residual))]
                                   (assoc base
                                          :xp-progress-version "growth-v1"
                                          :growth-rate rate
                                          :xp-desde-nivel r :xp r
                                          :xp-total (+ (growth/total-xp-for-level rate nv) r)))
                             :legacy (assoc base :xp-desde-nivel (long residual) :xp (long residual))
                             :unknown base)]
               with-xp))
        seeds [(mk "reh-v2-mid" "pikachu" 10 :v2 5)
               (mk "reh-v2-near" "eevee" 20 :v2 25)
               (mk "reh-v1" "charmander" 15 :v1 40)
               (mk "reh-legacy" "squirtle" 8 :legacy 3)
               (mk "reh-unknown" "bulbasaur" 12 :unknown 0)
               (mk "reh-max" "mewtwo" 100 :v2 0)]]
    (doseq [p seeds]
      (collection/adicionar-pokemon! store chat player p 50 nil))
    (let [col0 (ports/list-collection store chat player "a")
          by0 (into {} (map (juxt :id-pokemon identity) col0))
          ;; ganho sem subir
          g1 (collection/ganhar-xp-pokemon! store chat player
                                            (.indexOf (mapv :id-pokemon col0) "reh-v2-mid") 2)
          mid1 (first (filter #(= "reh-v2-mid" (:id-pokemon %))
                              (ports/list-collection store chat player "a")))
          ;; sobe exatamente 1 (span L10=28; tinha 5+23=28)
          need (- (growth/level-span-simple 10) 5)
          _ (collection/ganhar-xp-pokemon! store chat player
                                           (.indexOf (mapv :id-pokemon
                                                           (ports/list-collection store chat player "a"))
                                                     "reh-v2-mid")
                                           (dec need))
          mid2 (first (filter #(= "reh-v2-mid" (:id-pokemon %))
                              (ports/list-collection store chat player "a")))
          ;; multi-level + excesso no near (25/48 + enough)
          near-idx (.indexOf (mapv :id-pokemon (ports/list-collection store chat player "a"))
                             "reh-v2-near")
          multi-need (+ (- (growth/level-span-simple 20) 25)
                        (growth/level-span-simple 21)
                        7)
          _ (collection/ganhar-xp-pokemon! store chat player near-idx multi-need)
          near (first (filter #(= "reh-v2-near" (:id-pokemon %))
                              (ports/list-collection store chat player "a")))
          ;; v1 lazy convert on grant
          v1-idx (.indexOf (mapv :id-pokemon (ports/list-collection store chat player "a"))
                           "reh-v1")
          _ (collection/ganhar-xp-pokemon! store chat player v1-idx 1)
          v1a (first (filter #(= "reh-v1" (:id-pokemon %))
                             (ports/list-collection store chat player "a")))
          ;; unknown stays unknown until grant; grant converts via ensure
          unk0 (xp/progresso-pokemon (by0 "reh-unknown"))
          unk-idx (.indexOf (mapv :id-pokemon (ports/list-collection store chat player "a"))
                            "reh-unknown")
          _ (collection/ganhar-xp-pokemon! store chat player unk-idx 5)
          unk1 (first (filter #(= "reh-unknown" (:id-pokemon %))
                              (ports/list-collection store chat player "a")))
          ;; L100 discard
          max-idx (.indexOf (mapv :id-pokemon (ports/list-collection store chat player "a"))
                            "reh-max")
          gmax (collection/ganhar-xp-pokemon! store chat player max-idx 999)
          max1 (first (filter #(= "reh-max" (:id-pokemon %))
                              (ports/list-collection store chat player "a")))
          ;; retry idempotente: segundo grant 0
          again (collection/ganhar-xp-pokemon! store chat player max-idx 0)]
      {:mid-no-levelup {:nivel (:nivel mid1) :residual (:xp-desde-nivel mid1)}
       :mid-after-near-level {:nivel (:nivel mid2)}
       :multi {:nivel (:nivel near)
               :residual (:xp-desde-nivel near)
               :ver (:xp-progress-version near)}
       :v1-converted {:nivel (:nivel v1a)
                      :ver (:xp-progress-version v1a)
                      :level-preserved? (= 15 (:nivel v1a))}
       :unknown-before unk0
       :unknown-after-grant {:nivel (:nivel unk1)
                             :ver (:xp-progress-version unk1)
                             :residual (:xp-desde-nivel unk1)}
       :max {:nivel (:nivel max1) :residual (:xp-desde-nivel max1)}
       :retry-max again})))

(defn -main [& _]
  (println "gv2-rehearsal" {:storage storage :system system-name :db db-name :mode mode})
  (.mkdirs (io/file evid-dir))
  ;; Isolado: marker do cold fica :frozen — maintenance-control? só no ensaio.
  (let [client (persistence/client {:system system-name
                                    :storage-dir storage
                                    :maintenance-control? true})
        conn (persistence/ensure-db! client db-name)
        db0 (d/db conn)
        rows0 (load-rows db0)
        inv0 (inventory-summary rows0)
        fp0 (integrity-fp rows0)
        marks (markers db0)]
    (spit! "00-markers.edn" marks)
    (spit! "01-inventory-summary.edn" inv0)
    ;; relatório privado: sem player ids crus — owner-hash apenas
    (spit! "01-inventory-private.edn"
           (mapv #(select-keys % [:id :owner-hash :species :nivel :ver :residual
                                  :span-old :pct :shiny :hp :hp-atual
                                  :golpes-edn :ivs-edn :ataque :defesa
                                  :atq-esp :def-esp :veloc :amizade])
                 rows0))
    (spit! "01-integrity-fp-before.edn" fp0)
    (println "INVENTORY" (pr-str inv0))
    (when (#{"convert" "all"} mode)
      (let [conversions (mapv convert-row rows0)
            lowered (filter #(false? (:level-preserved? %))
                            (remove :skipped conversions))
            non-idem (filter #(and (not (:skipped %)) (false? (:idempotent? %)))
                             conversions)
            converted-n (count (remove :skipped conversions))
            skipped-n (count (filter :skipped conversions))
            pct-deltas
            (->> conversions
                 (remove :skipped)
                 (filter #(and (:pct-antes %) (:pct-depois %)))
                 (map (fn [c]
                        (Math/abs (- (double (:pct-depois c))
                                     (double (:pct-antes c))))))
                 vec)
            max-pct-delta (if (seq pct-deltas) (apply max pct-deltas) 0.0)]
        (spit! "02-conversion-report.edn"
               {:converted converted-n
                :skipped-unknown skipped-n
                :level-changed (count lowered)
                :non-idempotent (count non-idem)
                :max-abs-pct-delta max-pct-delta
                :sample (take 20 (remove :skipped conversions))})
        (println "CONVERT_PLAN"
                 (pr-str {:converted converted-n :skipped skipped-n
                          :level-changed (count lowered)
                          :max-pct-delta max-pct-delta}))
        (when (seq lowered)
          (spit! "02-LEVEL-CHANGED-FAIL.edn" lowered)
          (throw (ex-info "level-changed-during-conversion" {:n (count lowered)})))
        (when (seq non-idem)
          (throw (ex-info "conversion-not-idempotent" {:n (count non-idem)})))
        (persist-conversion! conn rows0 conversions)
        (let [db1 (d/db conn)
              rows1 (load-rows db1)
              inv1 (inventory-summary rows1)
              fp1 (integrity-fp rows1)
              again (mapv convert-row rows1)
              again-changed (filter #(false? (:level-preserved? %))
                                    (remove :skipped again))]
          (spit! "03-inventory-after.edn" inv1)
          (spit! "03-integrity-fp-after.edn" fp1)
          (spit! "03-idempotent-reconvert.edn"
                 {:level-changed (count again-changed)
                  :converted (count (remove :skipped again))})
          (when-not (= (:ids fp0) (:ids fp1))
            (throw (ex-info "ids-diverged" {})))
          (when-not (= (:niveis fp0) (:niveis fp1))
            (throw (ex-info "levels-diverged" {})))
          (when-not (= (:species fp0) (:species fp1))
            (throw (ex-info "species-diverged" {})))
          (when-not (= (:hp fp0) (:hp fp1))
            (throw (ex-info "hp-diverged" {})))
          (when-not (= (:shiny fp0) (:shiny fp1))
            (throw (ex-info "shiny-diverged" {})))
          (when-not (= (:golpes fp0) (:golpes fp1))
            (throw (ex-info "moves-diverged" {})))
          (when-not (= (:ivs fp0) (:ivs fp1))
            (throw (ex-info "ivs-diverged" {})))
          (when (seq again-changed)
            (throw (ex-info "reconvert-changed-levels" {})))
          (println "CONVERT_OK" (pr-str inv1)))))
    (when (#{"gameplay" "all"} mode)
      (let [s (store/make-store conn)
            _ (ports/ensure-schema! s)
            gp (gameplay-suite s)]
        (spit! "04-gameplay.edn" gp)
        (println "GAMEPLAY" (pr-str gp))))
    (spit! "99-done.edn" {:ok true :mode mode :ts (str (java.time.Instant/now))})
    (println "DONE")))

(-main)
