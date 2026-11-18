#!/usr/bin/env bb
;; Auditoria RO: inventário + histórico Datomic dos Pokémon ambíguos (unversioned residual≥9).
;; NÃO transact. Env: ODISSEU_BRIDGE_*, A6_EVID, A6_PRIVATE_DIR (manifesto privado).
(require '[datomic.client.api :as d]
         '[odisseu.persistence.datomic :as persistence]
         '[pokemon.domain.growth :as growth]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def evid (or (System/getenv "A6_EVID") "/tmp/flat9-ambig-evid"))
(def private-dir (or (System/getenv "A6_PRIVATE_DIR") (str evid "/private")))
(.mkdirs (io/file evid))
(.mkdirs (io/file private-dir))

(def client (persistence/client {:system (or (System/getenv "ODISSEU_BRIDGE_SYSTEM") "odisseu-m90-core")
                                 :storage-dir (or (System/getenv "ODISSEU_BRIDGE_STORAGE") "/data/datomic-local")
                                 :maintenance-control? true}))
(def conn (persistence/ensure-db! client (or (System/getenv "ODISSEU_BRIDGE_DB") "core")))
(def db (d/db conn))
(def db-hist (d/history db))

(defn attr-exists? [attr] (some? (d/pull db '[:db/ident] attr)))

(defn sha256-hex [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        dig (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) dig))))

(def pull-pat '[:db/id :pokemon/id-pokemon :pokemon/level :pokemon/xp :pokemon/species
                :pokemon/nome :pokemon/shiny :pokemon/xp-progress-version
                :pokemon/xp-desde-nivel :pokemon/xp-legacy-nivel :pokemon/xp-legacy-residual
                :pokemon/xp-total :pokemon/growth-rate :pokemon/hp :pokemon/hp-atual
                :pokemon/ataque :pokemon/defesa :pokemon/atq-esp :pokemon/def-esp
                :pokemon/veloc :pokemon/golpes :pokemon/ivs
                {:pokemon/save [:save/key :save/slot]
                 :save/_pokemon [:save/key :save/slot
                                 {:save/trainer [:trainer/player-id :trainer/chat-id]}]}])

;; Fallback pulls if reverse refs differ across schemas
(def pull-simple '[:db/id :pokemon/id-pokemon :pokemon/level :pokemon/xp :pokemon/species
                   :pokemon/nome :pokemon/shiny :pokemon/xp-progress-version
                   :pokemon/xp-desde-nivel :pokemon/xp-legacy-nivel :pokemon/xp-legacy-residual
                   :pokemon/xp-total :pokemon/growth-rate :pokemon/hp :pokemon/hp-atual
                   :pokemon/ataque :pokemon/defesa :pokemon/atq-esp :pokemon/def-esp
                   :pokemon/veloc :pokemon/golpes :pokemon/ivs])

(defn entity->reg [p]
  (let [nivel (long (or (:pokemon/level p) 1))
        xp (long (or (:pokemon/xp p) 0))
        resid (long (or (:pokemon/xp-desde-nivel p) xp 0))
        ver (when-let [v (:pokemon/xp-progress-version p)] (str v))]
    {:eid (:db/id p)
     :id-pokemon (str (:pokemon/id-pokemon p))
     :species (str (or (:pokemon/species p) ""))
     :nome (str (or (:pokemon/nome p) ""))
     :nivel nivel
     :xp xp
     :xp-desde-nivel resid
     :xp-total (when-let [t (:pokemon/xp-total p)] (long t))
     :xp-progress-version ver
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
     :ivs (:pokemon/ivs p)}))

(defn history-attr
  "Timeline of values for attr on eid (newest last)."
  [eid attr]
  (->> (d/q '[:find ?tx ?v ?added ?t
              :in $ ?e ?a
              :where
              [?e ?a ?v ?tx ?added]
              [?tx :db/txInstant ?t]]
            db-hist eid attr)
       (map (fn [[tx v added t]] {:tx tx :v v :added? added :t t}))
       (sort-by :tx)
       vec))

(defn classify-origin
  "A..E classification from residual vs official span + history signals."
  [reg hist-xp hist-ver hist-total hist-level]
  (let [species (:species reg)
        rate (or (:growth-rate reg) (growth/growth-rate-for-species species))
        nivel (:nivel reg)
        resid (long (:xp-desde-nivel reg))
        span (growth/level-span rate nivel)
        base (growth/total-xp-for-level rate nivel)
        next-base (if (< nivel 100) (growth/total-xp-for-level rate (inc nivel)) base)
        had-version? (some (fn [{:keys [v added?]}] (and added? (some? v) (not= (str v) ""))) hist-ver)
        had-total? (some (fn [{:keys [v added?]}] (and added? (number? v) (pos? (long v)))) hist-total)
        level-vals (->> hist-level (filter :added?) (map #(long (:v %))) distinct vec)
        xp-vals (->> hist-xp (filter :added?) (map #(long (:v %))) distinct sort vec)
        ;; residual fits inside official span at current level
        within-span? (and (pos? span) (< resid span))
        ;; residual equals official total-at-level + something (mis-stored total)
        looks-like-total? (and (>= resid base) (< resid next-base) (not within-span?))
        ;; residual == official absolute xp for some level
        matches-total-at-level?
        (some (fn [n] (= resid (growth/total-xp-for-level rate n))) (range 1 101))
        origin
        (cond
          (and had-version? (not (str/blank? (str (:xp-progress-version reg)))))
          :D-partial-migrate

          (and within-span? (or had-total? (nil? (:xp-progress-version reg))))
          :B-official-residual-unversioned

          (and looks-like-total? (not within-span?))
          :A-accumulated-other-model

          (and (< resid 9) false) ;; unreachable for ambiguous set
          :C-flat9-misstored

          matches-total-at-level?
          :A-accumulated-other-model

          (and (pos? span) (>= resid span) (< resid (* 3 span)))
          :A-accumulated-other-model

          :else
          :E-indeterminate)]
    {:rate rate
     :span span
     :base-xp base
     :within-span? within-span?
     :had-version-hist? (boolean had-version?)
     :had-total-hist? (boolean had-total?)
     :level-hist level-vals
     :xp-hist-sample (vec (take-last 12 xp-vals))
     :origin origin}))

(defn proposed-flat9
  "If origin proven enough, propose residual 0..8 preserving level. Else nil."
  [reg classif]
  (let [origin (:origin classif)
        rate (:rate classif)
        nivel (:nivel reg)
        resid (long (:xp-desde-nivel reg))
        span (long (:span classif))]
    (case origin
      :B-official-residual-unversioned
      (when (pos? span)
        (min 8 (long (Math/floor (* (/ (double resid) (double span)) 9.0)))))

      :A-accumulated-other-model
      ;; Treat raw as official absolute xp-total at preserved level: residual in span
      (let [base (:base-xp classif)
            ;; if resid looks like absolute total, use residual-in-level
            abs? (>= resid base)
            official-total (if abs? resid (+ base (min (dec span) resid)))
            r-in (growth/residual-in-level rate nivel official-total)
            sp (growth/level-span rate nivel)]
        (when (pos? sp)
          (min 8 (long (Math/floor (* (/ (double r-in) (double sp)) 9.0))))))

      :D-partial-migrate
      nil

      :C-flat9-misstored
      nil

      :E-indeterminate
      nil

      nil)))

(def poke-eids (mapv first (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db)))

(def all-rows
  (->> poke-eids
       (map (fn [e]
              (try
                (entity->reg (d/pull db pull-pat e))
                (catch Exception _
                  (entity->reg (d/pull db pull-simple e))))))
       (sort-by :id-pokemon)
       vec))

(def ambiguous
  (filterv growth/ambiguous-legacy? all-rows))

(def audits
  (mapv
   (fn [reg]
     (let [eid (:eid reg)
           hx (history-attr eid :pokemon/xp)
           hxd (history-attr eid :pokemon/xp-desde-nivel)
           hv (history-attr eid :pokemon/xp-progress-version)
           ht (history-attr eid :pokemon/xp-total)
           hl (history-attr eid :pokemon/level)
           classif (classify-origin reg (concat hx hxd) hv ht hl)
           flat9 (proposed-flat9 reg classif)
           confidence (if (and flat9 (#{:B-official-residual-unversioned :A-accumulated-other-model}
                                      (:origin classif))
                                (:within-span? classif))
                        :comprovado
                        (if flat9 :comprovado-fraco :indeterminado))
           ;; tighten: only auto-convert B with within-span
           action (if (and (= :B-official-residual-unversioned (:origin classif))
                           (:within-span? classif)
                           (some? flat9))
                    :converter
                    :manter-bloqueado)]
       {:id (:id-pokemon reg)
        :eid eid
        :species (:species reg)
        :nivel (:nivel reg)
        :xp-bruto (:xp-desde-nivel reg)
        :xp (:xp reg)
        :xp-total (:xp-total reg)
        :xp-legacy-residual (:xp-legacy-residual reg)
        :xp-legacy-nivel (:xp-legacy-nivel reg)
        :xp-progress-version (:xp-progress-version reg)
        :rate (:rate classif)
        :span (:span classif)
        :origin (:origin classif)
        :xp-flat9-proposto flat9
        :confidence confidence
        :action action
        :within-span? (:within-span? classif)
        :evidence {:had-version-hist? (:had-version-hist? classif)
                   :had-total-hist? (:had-total-hist? classif)
                   :level-hist (:level-hist classif)
                   :xp-hist-sample (:xp-hist-sample classif)
                   :tx-xp-count (count hx)
                   :tx-ver-count (count hv)
                   :last-xp-tx (some-> (last (concat hx hxd)) (select-keys [:tx :v :t :added?]))
                   :last-ver-tx (some-> (last hv) (select-keys [:tx :v :t :added?]))}
        :hp (:hp reg)
        :shiny (:shiny reg)}))
   ambiguous))

(def summary
  {:generatedAt (str (java.time.Instant/now))
   :storage (System/getenv "ODISSEU_BRIDGE_STORAGE")
   :pokemon-total (count all-rows)
   :ambiguous-count (count ambiguous)
   :by-origin (frequencies (map :origin audits))
   :by-action (frequencies (map :action audits))
   :by-confidence (frequencies (map :confidence audits))
   :convertible (count (filter #(= :converter (:action %)) audits))
   :unresolved (count (filter #(= :manter-bloqueado (:action %)) audits))
   :level-sum (reduce + 0 (map :nivel audits))
   :fingerprint (sha256-hex (pr-str (mapv (juxt :id :nivel :xp-bruto :origin :action) audits)))})

;; Public summary (no player identifiers beyond pokemon uuid which is already in prior reports)
(spit (io/file evid "ambiguous-audit-summary.edn") (pr-str summary))
(spit (io/file evid "ambiguous-audit-matrix.edn")
      (pr-str (mapv #(select-keys % [:id :species :nivel :xp-bruto :origin :xp-flat9-proposto
                                     :confidence :action :span :within-span? :rate])
                    audits)))

;; Private full audit (contains eids + evidence) — outside git.
;; Never clobber a non-empty reconciliation manifest with a later empty
;; post-apply audit against an already-reconciled restore.
(let [manifest-path (io/file private-dir "reconciliation-manifest.edn")
      full-path (io/file private-dir "ambiguous-audit-full.edn")
      entries (mapv (fn [a]
                      {:id (:id a)
                       :expected-nivel (:nivel a)
                       :expected-raw-xp (:xp-bruto a)
                       :origin (:origin a)
                       :action (:action a)
                       :target-version "flat-9-v2"
                       :target-residual (:xp-flat9-proposto a)
                       :receipt-id (str "flat9-ambig-recon:" (:id a))
                       :confidence (:confidence a)})
                    audits)
      existing (when (.exists manifest-path)
                 (try (edn/read-string (slurp manifest-path))
                      (catch Exception _ nil)))
      existing-n (count (:entries existing))
      skip-overwrite? (and (zero? (count entries))
                           (pos? (long existing-n)))]
  (if skip-overwrite?
    (do
      (spit (io/file evid "ambiguous-audit-manifest-preserve.txt")
            (str "preserved existing private manifest with " existing-n
                 " entries; post-audit ambiguous-count=0\n"))
      (println "PRIVATE_MANIFEST_PRESERVED" existing-n))
    (do
      (when (.exists manifest-path)
        (spit (io/file private-dir (str "reconciliation-manifest.prev-"
                                        (.toEpochMilli (java.time.Instant/now))
                                        ".edn"))
              (slurp manifest-path)))
      (spit full-path (pr-str audits))
      (spit manifest-path
            (pr-str {:version "flat9-ambig-recon-v1"
                     :generatedAt (:generatedAt summary)
                     :entries entries})))))

(println "AMBIG_AUDIT_OK")
(prn summary)
(doseq [a audits]
  (println (format "%s | %s L%d raw=%d origin=%s flat9=%s action=%s conf=%s span=%s"
                   (:id a) (:species a) (:nivel a) (:xp-bruto a)
                   (name (:origin a))
                   (str (:xp-flat9-proposto a))
                   (name (:action a))
                   (name (:confidence a))
                   (str (:span a)))))
(System/exit 0)
