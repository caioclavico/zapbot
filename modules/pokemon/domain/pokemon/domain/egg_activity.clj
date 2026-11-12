(ns pokemon.domain.egg-activity
  "Progressão A6 — PI, sorteio de ovos e anti-farm (puro).
  Distinto de PE do treinador (036) e XP do Pokémon (037)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def catalog-resource "pokemon/domain/data/egg_hatch_catalog_v1.edn")
(def catalog-version "egg-hatch-catalog-v1")

(def ^:private default-daily-cap 150)

(defn daily-cap
  []
  (let [raw (some-> (System/getenv "ADVENTURE_PI_DAILY_CAP") str/trim)]
    (if (str/blank? raw)
      default-daily-cap
      (try
        (max 0 (Long/parseLong raw))
        (catch Exception _ default-daily-cap)))))

(defn activity-enabled?
  []
  (not (#{"0" "false" "no" "off"}
        (some-> (System/getenv "ADVENTURE_EGG_ACTIVITY") str/lower-case))))

;; Pontos de incubação por atividade (antes do teto diário / split).
(def pi-por-atividade
  {:hunt-complete 5
   :wild-victory 8
   :wild-defeat 3
   :gym-victory 15
   :gym-defeat 6
   :pvp-victory 12
   :pvp-defeat 5
   :raid-rare 20
   :raid-epic 30
   :raid-legendary 45
   :raid-defeat 10
   :daily-mission 20})

;; Chance % de ovo (primeiro d100 canônico).
(def egg-chance
  {:first-daily-hunt 20
   :wild-victory 5
   :gym-victory 12
   :raid-rare 15
   :raid-epic 25
   :raid-legendary 35
   :pvp-victory 8})

(def egg-category-for
  {:first-daily-hunt :comum
   :wild-victory :comum
   :gym-victory :raro
   :raid-rare :raro
   :raid-epic :epico
   :raid-legendary :especial
   :pvp-victory :raro
   :welcome :comum})

(defonce ^:private catalog* (atom nil))

(defn catalog
  []
  (or @catalog*
      (let [raw (some-> catalog-resource io/resource slurp)]
        (when-not raw
          (throw (err/error :egg_catalog_missing {:resource catalog-resource})))
        (let [c (edn/read-string raw)]
          (when-not (= catalog-version (:version c))
            (throw (err/error :egg_catalog_version_mismatch
                              {:expected catalog-version :got (:version c)})))
          (reset! catalog* c)
          c))))

(defn- sha256-bytes
  [^String s]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes s StandardCharsets/UTF_8)))

(defn canonical-roll
  "Retorna double em [0,1) estável para seed."
  [seed]
  (let [b (sha256-bytes (str seed))
        ;; unsigned 32-bit / 2^32
        n (bit-and (long (+ (bit-shift-left (bit-and (aget b 0) 0xff) 24)
                            (bit-shift-left (bit-and (aget b 1) 0xff) 16)
                            (bit-shift-left (bit-and (aget b 2) 0xff) 8)
                            (bit-and (aget b 3) 0xff)))
                   0xffffffff)]
    (/ (double n) 4294967296.0)))

(defn roll-percent
  "true se roll*100 < chance-percent."
  [seed chance-percent]
  (< (* (canonical-roll seed) 100.0) (double chance-percent)))

(defn utc-day
  [epoch-ms]
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd")
           (.atZone (Instant/ofEpochMilli (long epoch-ms)) ZoneOffset/UTC)))

(defn sortear-especie
  "Sorteio ponderado estável. Espécie fixada na concessão."
  [categoria seed]
  (let [cat (keyword categoria)
        pool (get-in (catalog) [:pools cat])
        _ (when-not (seq pool)
            (throw (err/error :invalid_egg_category {:categoria categoria})))
        faixa (* (canonical-roll (str seed "|pool")) 100.0)
        grupo (loop [acc 0 xs pool]
                (when-let [g (first xs)]
                  (let [n (+ acc (long (:weight g)))]
                    (if (< faixa n) g (recur n (rest xs))))))
        grupo (or grupo (last pool))
        spp (:species grupo)
        idx (long (Math/floor (* (canonical-roll (str seed "|sp")) (count spp))))]
    (nth spp (min idx (dec (count spp))))))

(defn display-name
  [species]
  (let [s (str species)]
    (if (str/blank? s) "Pokémon"
        (str (str/upper-case (subs s 0 1)) (subs s 1)))))

(defn raid-tier-key
  "Mapeia categoria de raid → chave de PI/ovo."
  [categoria]
  (let [c (-> (str (or categoria "")) str/lower-case)]
    (cond
      (re-find #"lend|myth|mitic|legendary" c) :raid-legendary
      (re-find #"epic|épico|epico" c) :raid-epic
      :else :raid-rare)))

(defn- pi-day-state
  [shell day]
  (let [d (:pi-daily shell)]
    (if (= day (:day d))
      d
      {:day day :points 0})))

(defn remaining-daily-pi
  [shell agora]
  (let [day (utc-day agora)
        used (long (:points (pi-day-state shell day) 0))]
    (max 0 (- (daily-cap) used))))

(defn credit-daily-pi
  "Atualiza contador diário; retorna [shell' pontos-efetivos]."
  [shell agora pontos]
  (let [day (utc-day agora)
        cur (pi-day-state shell day)
        used (long (:points cur 0))
        room (max 0 (- (daily-cap) used))
        gain (min (long pontos) room)]
    [(assoc shell :pi-daily {:day day :points (+ used gain)}) gain]))

(defn pending-egg
  [event-id category species catalog-ver]
  {:event-id (str event-id)
   :category (keyword category)
   :species (str species)
   :catalog-version (str catalog-ver)})

(defn enqueue-pending-egg
  [shell pending]
  (let [xs (vec (:pending-egg-grants shell []))
        id (:event-id pending)]
    (if (some #(= id (:event-id %)) xs)
      shell
      (assoc shell :pending-egg-grants (conj xs pending)))))

(defn pop-deliverable-pending
  "Remove o primeiro pending se houver espaço; retorna [shell' pending-or-nil]."
  [shell egg-count]
  (let [xs (vec (:pending-egg-grants shell []))]
    (if (or (empty? xs) (>= (long egg-count) eggs/limite-inventario))
      [shell nil]
      (let [p (first xs)]
        [(assoc shell :pending-egg-grants (vec (rest xs))) p]))))

(defn materialize-pending
  "Drena a fila FIFO enquanto houver vaga. Não re-sorteia espécie/categoria.
   Identidade `egg:{event-id}` estável. Idempotente via receipt pending-deliver:."
  [estado agora]
  (loop [st estado delivered []]
    (let [n (eggs/inventariados-count st)
          [st1 pend] (pop-deliverable-pending st n)]
      (if-not pend
        {:estado st :delivered delivered}
        (let [eid (str (:event-id pend))
              deliver-key (str "pending-deliver:" eid)]
          (if (contains? (:receipts st1 #{}) deliver-key)
            (recur st1 delivered)
            (let [ovo (eggs/novo-ovo (str "egg:" eid)
                                     (:category pend)
                                     agora
                                     (:species pend))
                  st2 (-> st1
                          (eggs/conceder-ovo ovo)
                          (update :receipts conj deliver-key))]
              (recur st2 (conj delivered ovo)))))))))

(declare apply-raid-win-progress materialize-pending-incubators)

(defn apply-activity
  "Aplica PI + possível ovo sobre estado mergeado (shell+eggs).
  Idempotente por event-id em :receipts.
  Retorna {:estado :pi-granted :egg-grant :deferred-egg :summary}."
  [estado
   {:keys [event-id activity agora opponent-id first-daily-hunt? suppress-wild-victory-pi?
           raid-category mission-egg-category]
    :as _opts}]
  (let [event-id (str event-id)
        activity (keyword activity)
        agora (long (or agora (System/currentTimeMillis)))]
    (cond
      ;; Só aplica crossed-levels em build-activity-enrich-tx (sem PI/ovo).
      (= activity :incubator-level-reward)
      {:estado estado :pi-granted 0 :egg-grant nil :deferred-egg nil
       :summary {:event-id event-id :activity "incubator-level-reward"}}

      (contains? (:receipts estado #{}) event-id)
      {:estado estado :pi-granted 0 :egg-grant nil :deferred-egg nil
       :summary {:duplicate true :event-id event-id}}

      :else
      (let [;; Anti-farm PvP: no máximo 1 crédito PI/ovo por oponente/dia.
            pvp? (#{:pvp-victory :pvp-defeat} activity)
            day (utc-day agora)
            farm-key (when (and pvp? opponent-id)
                       (str "pvp-farm:" day ":" opponent-id))
            farmed? (and farm-key (contains? (:receipts estado #{}) farm-key))
            raid-key (when (= activity :raid-victory)
                       (raid-tier-key raid-category))
            pi-key (cond
                     (= activity :raid-victory) raid-key
                     :else activity)
            pi0 (long (or (get pi-por-atividade pi-key) 0))
            pi0 (cond
                  suppress-wild-victory-pi? 0
                  farmed? 0
                  :else pi0)
            [shell1 pi*] (credit-daily-pi estado agora pi0)
            estado1 (eggs/aplicar-pontos (merge estado shell1) event-id pi*)
            ;; Ovos
            egg-kind (cond
                       first-daily-hunt? :first-daily-hunt
                       (= activity :wild-victory) :wild-victory
                       (= activity :gym-victory) :gym-victory
                       (= activity :pvp-victory) :pvp-victory
                       (= activity :raid-victory) raid-key
                       (= activity :daily-mission) :daily-mission
                       (= activity :welcome) :welcome
                       :else nil)
            egg-kind (if (and farmed? (#{:pvp-victory} egg-kind)) nil egg-kind)
            chance (or (get egg-chance egg-kind) 0)
            force-cat (or mission-egg-category (get egg-category-for egg-kind))
            roll-ok? (cond
                       (nil? egg-kind) false
                       (= egg-kind :welcome) true
                       (= egg-kind :daily-mission) (some? mission-egg-category)
                       :else (roll-percent (str event-id "|egg") chance))
            species (when (and roll-ok? force-cat)
                      (sortear-especie force-cat (str event-id "|species")))
            egg-map (when species
                      (eggs/novo-ovo (str "egg:" event-id) force-cat agora species))
            invent (eggs/inventariados-count estado1)
            [estado2 egg-grant deferred]
            (cond
              (nil? egg-map) [estado1 nil nil]
              (< invent eggs/limite-inventario)
              [(eggs/conceder-ovo estado1 egg-map) egg-map nil]
              :else
              (let [pend (pending-egg event-id force-cat species catalog-version)
                    sh (enqueue-pending-egg estado1 pend)]
                [(merge estado1 sh) nil pend]))
            estado3 (cond-> estado2
                      farm-key (update :receipts conj farm-key)
                      true (update :receipts conj event-id)
                      true (assoc :pi-daily (:pi-daily shell1)))
            ;; Progressão de incubadoras: só vitória canônica de raid conta.
            raid-prog (when (= activity :raid-victory)
                        (apply-raid-win-progress estado3))
            estado4 (if raid-prog (:estado raid-prog) estado3)
            drained-inc (materialize-pending-incubators estado4)
            estado5 (:estado drained-inc)]
        {:estado estado5
         :pi-granted pi*
         :egg-grant egg-grant
         :deferred-egg deferred
         :incubator-grant (:incubator-grant raid-prog)
         :incubators-delivered (:delivered drained-inc)
         :summary {:event-id event-id
                   :activity (name activity)
                   :pi pi*
                   :egg-id (or (:egg-id egg-grant) (:event-id deferred))
                   :egg-category (when force-cat (name force-cat))
                   :deferred? (boolean deferred)
                   :day day
                   :raid-wins (long (:raid-wins estado5 0))
                   :incubator-reward (:incubator-grant raid-prog)
                   :incubators-delivered (:delivered drained-inc)}}))))

;;; ---------------------------------------------------------------------------
;;; Incubadoras por progressão (níveis 10/20/30 + 10 raids)
;;; ---------------------------------------------------------------------------

(def incubator-reward-catalog
  "Regras configuráveis de desbloqueio. Identidade canônica = reward-id."
  {"incubator-reward:level:10"
   {:kind :extra :source :level :threshold 10 :uses 3 :bonus-points 0
    :label "Incubadora extra (nível 10)"}
   "incubator-reward:level:20"
   {:kind :extra :source :level :threshold 20 :uses 3 :bonus-points 0
    :label "Incubadora extra (nível 20)"}
   "incubator-reward:level:30"
   {:kind :especial :source :level :threshold 30 :uses 3 :bonus-points 25
    :label "Incubadora especial (nível 30)"}
   "incubator-reward:raids:10"
   {:kind :extra :source :raids :threshold 10 :uses 3 :bonus-points 0
    :label "Incubadora extra (10 raids)"}})

(def raid-wins-reward-threshold 10)

(defn reward-already-recorded?
  "True se o marco já foi registrado (recibo, incubadora ativa ou fila)."
  [estado reward-id]
  (let [id (str reward-id)
        receipts (set (:receipts estado #{}))
        pending (vec (:pending-incubator-grants estado []))
        incubators (vec (:incubators estado []))]
    (or (contains? receipts id)
        (some #(= id (str (:reward-id %))) pending)
        (some #(= id (str (:incubator-id %))) incubators))))

(defn- pending-incubator-entry
  [reward-id meta]
  {:reward-id (str reward-id)
   :kind (keyword (:kind meta))
   :source (keyword (:source meta))
   :label (str (:label meta))
   :uses (long (:uses meta 3))
   :bonus-points (long (:bonus-points meta 0))})

(defn enqueue-pending-incubator
  [estado pending]
  (let [xs (vec (:pending-incubator-grants estado []))
        id (str (:reward-id pending))]
    (if (some #(= id (str (:reward-id %))) xs)
      estado
      (assoc estado :pending-incubator-grants (conj xs pending)))))

(defn grant-or-enqueue-incubator
  "Registra o marco canônico e materializa se houver vaga temporária.
   Idempotente: se já registrado, devolve estado inalterado.
   Retorno: {:estado :status :reward-id :kind :pending?}
   status ∈ #{:duplicate :granted :enqueued :unknown-reward}."
  [estado reward-id]
  (let [id (str reward-id)
        meta (get incubator-reward-catalog id)]
    (cond
      (nil? meta)
      {:estado estado :status :unknown-reward :reward-id id :kind nil :pending? false}

      (reward-already-recorded? estado id)
      {:estado estado :status :duplicate :reward-id id
       :kind (:kind meta) :pending? false}

      (pos? (eggs/free-temporary-slots estado))
      (let [st (-> estado
                   (eggs/adicionar-incubadora id (:kind meta))
                   (update :receipts #(set (conj (or % #{}) id))))]
        {:estado st :status :granted :reward-id id
         :kind (:kind meta) :pending? false})

      :else
      (let [pend (pending-incubator-entry id meta)
            st (-> estado
                   (enqueue-pending-incubator pend)
                   (update :receipts #(set (conj (or % #{}) id))))]
        {:estado st :status :enqueued :reward-id id
         :kind (:kind meta) :pending? true}))))

(defn materialize-pending-incubators
  "Drena a fila FIFO enquanto houver vaga temporária.
   Não remove o recibo do marco (já marcado na concessão do direito)."
  [estado]
  (loop [st estado delivered []]
    (let [xs (vec (:pending-incubator-grants st []))
          free (eggs/free-temporary-slots st)]
      (if (or (empty? xs) (zero? free))
        {:estado st :delivered delivered}
        (let [p (first xs)
              id (str (:reward-id p))
              kind (keyword (:kind p))
              st1 (assoc st :pending-incubator-grants (vec (rest xs)))
              ;; Se a incubadora já existe (legado), só remove da fila.
              already (some #(= id (:incubator-id %)) (:incubators st1))]
          (if already
            (recur st1 delivered)
            (let [st2 (eggs/adicionar-incubadora st1 id kind)]
              (recur st2 (conj delivered {:reward-id id :kind kind})))))))))

(defn eligible-level-reward-ids
  "Marcos de nível elegíveis para o nível persistido 036 (não recalcula fórmula)."
  [trainer-level]
  (let [lvl (long (or trainer-level 0))]
    (cond-> []
      (>= lvl 10) (conj "incubator-reward:level:10")
      (>= lvl 20) (conj "incubator-reward:level:20")
      (>= lvl 30) (conj "incubator-reward:level:30"))))

(defn apply-level-rewards
  "Concede direitos para níveis cruzados (números) ou nível legado (número).
   Aceita também vetor de reward-ids canônicos. Ordem estável.
   Retorno: {:estado :grants}."
  [estado levels-or-ids]
  (let [xs (if (sequential? levels-or-ids) (vec levels-or-ids) [levels-or-ids])
        ids (->> xs
                 (mapcat (fn [x]
                           (cond
                             (string? x) (when (get incubator-reward-catalog (str x))
                                           [(str x)])
                             (number? x) (eligible-level-reward-ids x)
                             (keyword? x) (when (get incubator-reward-catalog (name x))
                                            [(name x)])
                             :else nil)))
                 (filter some?)
                 distinct
                 vec)]
    (reduce (fn [{:keys [estado grants]} rid]
              (let [r (grant-or-enqueue-incubator estado rid)]
                {:estado (:estado r)
                 :grants (conj grants (dissoc r :estado))}))
            {:estado estado :grants []}
            ids)))

(defn apply-raid-win-progress
  "Incrementa vitórias canônicas e concede marco raids:10 se atingido.
   Só chamar no caminho não-duplicado de :raid-victory."
  [estado]
  (let [wins (inc (long (:raid-wins estado 0)))
        st (assoc estado :raid-wins wins)]
    (if (>= wins raid-wins-reward-threshold)
      (let [r (grant-or-enqueue-incubator st "incubator-reward:raids:10")]
        {:estado (:estado r)
         :raid-wins wins
         :incubator-grant (dissoc r :estado)})
      {:estado st :raid-wins wins :incubator-grant nil})))

(defn reconcile-incubator-rewards
  "Reconciliação idempotente (Datomic descartável / GO explícito).
   Usa nível persistido + raid-wins do shell. Não inventa concessões:
   checa recibo, incubadora ativa e fila antes de registrar."
  [estado {:keys [trainer-level]}]
  (let [after-levels (apply-level-rewards estado trainer-level)
        st (:estado after-levels)
        raid-r (when (>= (long (:raid-wins st 0)) raid-wins-reward-threshold)
                 (grant-or-enqueue-incubator st "incubator-reward:raids:10"))
        st2 (if raid-r (:estado raid-r) st)
        drained (materialize-pending-incubators st2)]
    {:estado (:estado drained)
     :level-grants (:grants after-levels)
     :raid-grant (when raid-r (dissoc raid-r :estado))
     :delivered (:delivered drained)}))

(defn redeem-next-incubator
  "Resgata a próxima pendente se houver vaga. Idempotente se fila vazia."
  [estado]
  (let [free (eggs/free-temporary-slots estado)
        xs (vec (:pending-incubator-grants estado []))]
    (cond
      (zero? free)
      {:estado estado :status :no-slot :delivered nil :pending-remaining (count xs)}

      (empty? xs)
      {:estado estado :status :empty :delivered nil :pending-remaining 0}

      :else
      (let [drained (materialize-pending-incubators estado)
            delivered (first (:delivered drained))]
        {:estado (:estado drained)
         :status (if delivered :redeemed :empty)
         :delivered delivered
         :pending-remaining (count (:pending-incubator-grants (:estado drained) []))}))))
