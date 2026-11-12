(ns pokemon.infrastructure.datomic.adventure-activity
  "Enrich transacional: PI + ovos nas mesmas TXs de gameplay (A6).
  Espelha o padrão de mission-events/attach — sem fila em memória.
  Raid/PvP creditam **todos** os participantes elegíveis na TX canônica."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]))

(defn- read-edn [s] (when s (edn/read-string s)))

(defn- present? [e]
  (some? (:db/id e)))

(defn- account-shell+eggs
  [db chat-id player-id]
  (let [ak (id/adventure-account-key chat-id player-id)
        e (d/pull db '[*] [:adventure-account/key ak])
        exists? (present? e)
        shell (if exists?
                (adventure-store/account-shell e read-edn present?)
                nil)
        eggs (when exists?
               (mapv #(adventure-store/egg-entity->map % read-edn present?)
                     (map first
                          (d/q '[:find (pull ?e [*])
                                 :in $ ?ak
                                 :where [?e :egg/account-key ?ak]]
                               db ak))))]
    {:exists? exists?
     :shell shell
     :eggs (vec eggs)}))

(defn- first-daily-hunt?
  [shell agora]
  (let [day (egg-activity/utc-day agora)
        marker (str "first-daily-hunt:" day)]
    (not (contains? (:receipts shell #{}) marker))))

(defn- mark-first-daily!
  [day]
  (str "first-daily-hunt:" day))

(defn- raid-category-of
  [raid summary]
  (or (:boss-categoria raid)
      (:categoria raid)
      (:category raid)
      (:tier raid)
      (:boss-categoria summary)
      (:categoria summary)
      (:category summary)))

(defn- outcome-kw
  [x]
  (let [k (cond
            (keyword? x) x
            (string? x) (keyword x)
            (symbol? x) (keyword (name x))
            :else nil)]
    (case k
      (:vitoria :victory :win) :vitoria
      (:derrota :defeat :lose :loss) :derrota
      k)))

(defn- detect-targets
  "Retorna vetor de alvos {:player-id :opts}. Vários jogadores em raid/PvP."
  [db writer built receipt agora]
  (let [kind (:operation-receipt/kind receipt)
        summary (or (read-edn (:operation-receipt/summary receipt)) {})
        result (:result built)
        next (or (:battle result) (:encounter result) (:raid result))
        bid (or (:battle-id next) (:battle-id result) (:battle-id summary))
        previous (when bid (d/pull db '[*] [:battle/id bid]))
        before (or (battle/decode-compact-edn (:battle/state-edn previous)) {})
        finished? (and (:active? before) (not (:active? next)))
        winner (:outcome next)
        chat-id (:chat-id writer)
        player-id (:player-id writer)
        day (egg-activity/utc-day agora)]
    (cond
      (and (= kind "encounter-capture") (:capturou? result))
      (let [shell-eggs (account-shell+eggs db chat-id player-id)
            shell (or (:shell shell-eggs) (eggs/estado-inicial))
            first? (first-daily-hunt? shell agora)]
        [{:player-id player-id
          :opts {:activity :hunt-complete
                 :event-id (str "egg-act:hunt:" bid)
                 :first-daily-hunt? first?
                 :first-daily-marker (when first? (mark-first-daily! day))
                 :suppress-wild-victory-pi? true
                 :agora agora}}])

      (and (= kind "capture") (:capturou? result))
      (let [shell-eggs (account-shell+eggs db chat-id player-id)
            shell (or (:shell shell-eggs) (eggs/estado-inicial))
            first? (first-daily-hunt? shell agora)
            oid (:operation-receipt/id receipt)]
        [{:player-id player-id
          :opts {:activity :hunt-complete
                 :event-id (str "egg-act:capture:" oid)
                 :first-daily-hunt? first?
                 :first-daily-marker (when first? (mark-first-daily! day))
                 :suppress-wild-victory-pi? true
                 :agora agora}}])

      (and (= kind "battle-turn") finished? (= winner :win) (= :wild (:kind next)))
      [{:player-id player-id
        :opts {:activity :wild-victory
               :event-id (str "egg-act:wild-win:" bid)
               :agora agora}}]

      (and (= kind "battle-turn") finished? (= winner :lose) (= :wild (:kind next)))
      [{:player-id player-id
        :opts {:activity :wild-defeat
               :event-id (str "egg-act:wild-lose:" bid)
               :agora agora}}]

      (and (= kind "battle-turn") finished? (= winner :win) (= :gym (:kind next)))
      [{:player-id player-id
        :opts {:activity :gym-victory
               :event-id (str "egg-act:gym-win:" bid)
               :agora agora}}]

      (and (= kind "battle-turn") finished? (= winner :lose) (= :gym (:kind next)))
      [{:player-id player-id
        :opts {:activity :gym-defeat
               :event-id (str "egg-act:gym-lose:" bid)
               :agora agora}}]

      (and (= kind "gym-resolve") (:venceu? result))
      [{:player-id player-id
        :opts {:activity :gym-victory
               :event-id (str "egg-act:gym-resolve:" (:operation-receipt/id receipt))
               :agora agora}}]

      ;; PvP: credita vencedor e perdedor na mesma conclusão canônica,
      ;; independentemente de quem enviou o turno final.
      (and (= kind "pvp-turn") finished? (#{:x :o} winner))
      (let [winner-pid (get-in before [:jogadores winner])
            loser-side (if (= winner :x) :o :x)
            loser-pid (get-in before [:jogadores loser-side])]
        (cond-> []
          winner-pid
          (conj {:player-id winner-pid
                 :opts {:activity :pvp-victory
                        :event-id (str "egg-act:pvp:" bid ":" winner-pid)
                        :opponent-id loser-pid
                        :agora agora}})
          loser-pid
          (conj {:player-id loser-pid
                 :opts {:activity :pvp-defeat
                        :event-id (str "egg-act:pvp:" bid ":" loser-pid)
                        :opponent-id winner-pid
                        :agora agora}})))

      ;; Raid: usa :rewards do resultado (um por elegível) — cobre vitória e derrota.
      (and (= kind "raid-attack") (seq (:rewards result)))
      (let [raid (:raid result)
            rid (or (:raid-id raid) (:raid-id summary))
            cat (raid-category-of raid summary)]
        (mapv (fn [r]
                (let [pid (:player-id r)
                      out (outcome-kw (:outcome r))
                      act (if (= :derrota out) :raid-defeat :raid-victory)]
                  {:player-id pid
                   :opts (cond-> {:activity act
                                  :event-id (str "egg-act:raid:" rid ":" pid)
                                  :agora agora}
                           (= :raid-victory act)
                           (assoc :raid-category cat))}))
              (:rewards result)))

      ;; Fallback: raid-attack encerrou sem vetor rewards (não deveria), usa flags.
      (and (= kind "raid-attack") (or (:venceu? result) (:derrota? result)))
      (let [raid (:raid result)
            rid (or (:raid-id raid) (:raid-id summary))
            cat (raid-category-of raid summary)
            act (if (:derrota? result) :raid-defeat :raid-victory)]
        [{:player-id player-id
          :opts (cond-> {:activity act
                         :event-id (str "egg-act:raid:" rid ":" player-id)
                         :agora agora}
                  (= :raid-victory act) (assoc :raid-category cat))}])

      ;; Recibo raid-reward isolado (idempotente com egg-act:raid:rid:pid).
      (= kind "raid-reward")
      (let [rid (or (:raid-id summary) (get-in result [:raid :raid-id]))
            out (outcome-kw (or (:outcome summary) (:outcome result)))
            cat (raid-category-of (or (:raid result) {}) summary)
            ;; player-key do recibo: chat|player
            pk (:operation-receipt/player-key receipt)
            pid (or (:player-id summary)
                    (when (string? pk)
                      (second (re-find #"\|(.+)$" pk)))
                    player-id)
            act (if (= :derrota out) :raid-defeat :raid-victory)]
        [{:player-id pid
          :opts (cond-> {:activity act
                         :event-id (str "egg-act:raid:" rid ":" pid)
                         :agora agora}
                  (= :raid-victory act) (assoc :raid-category cat))}])

      (and (= kind "mission-claim") (or (:claimed result) (:rewarded result)
                                        (:ok result)))
      (let [oid (:operation-receipt/id receipt)
            egg-cat (or (:egg-category summary) (:eggCategory summary))]
        [{:player-id player-id
          :opts {:activity :daily-mission
                 :event-id (str "egg-act:mission:" oid)
                 :mission-egg-category (when egg-cat (keyword egg-cat))
                 :agora agora}}])

      :else nil)))

(defn- apply-marker
  [enrich marker]
  (if-not marker
    enrich
    (let [estado-edn (some (fn [form]
                             (when (and (vector? form)
                                        (= :db/add (first form))
                                        (= :adventure-account/state-edn (nth form 2 nil)))
                               (nth form 3)))
                           (:tx enrich))
          ;; Also handle map assert on ensure
          estado-edn (or estado-edn
                         (some (fn [form]
                                 (when (and (map? form) (:adventure-account/state-edn form))
                                   (:adventure-account/state-edn form)))
                               (:tx enrich)))
          shell* (when estado-edn (edn/read-string estado-edn))
          shell** (when shell*
                    (update shell* :receipts #(set (conj (or % #{}) marker))))]
      (if-not shell**
        enrich
        (update enrich :tx
                (fn [tx]
                  (mapv (fn [form]
                          (cond
                            (and (vector? form)
                                 (= :db/add (first form))
                                 (= :adventure-account/state-edn (nth form 2 nil)))
                            (assoc form 3 (pr-str shell**))

                            (and (map? form) (:adventure-account/state-edn form))
                            (assoc form :adventure-account/state-edn (pr-str shell**))

                            :else form))
                        tx)))))))

(defn- enrich-target
  [db chat-id built target]
  (let [pid (:player-id target)
        opts (:opts target)
        {:keys [exists? shell eggs]} (account-shell+eggs db chat-id pid)
        enrich (adventure-store/build-activity-enrich-tx
                {:writer-ctx {:chat-id chat-id :player-id pid}
                 :account-shell (or shell (eggs/estado-inicial))
                 :egg-maps eggs
                 :activity-opts opts
                 :ensure-tx? (not exists?)})
        enrich (apply-marker enrich (:first-daily-marker opts))]
    enrich))

(defn attach
  "Anexa PI/ovos à TX para todos os alvos detectados. Não altera 036/037."
  [db writer built]
  (if-not (egg-activity/activity-enabled?)
    built
    (let [receipt (some #(when (and (map? %) (:operation-receipt/id %)) %) (:tx built))
          agora (System/currentTimeMillis)
          targets (when receipt (detect-targets db writer built receipt agora))]
      (if-not (seq targets)
        built
        (let [chat-id (:chat-id writer)
              enrichments (mapv #(enrich-target db chat-id built %) targets)
              summaries (mapv #(get-in % [:result :adventure-activity]) enrichments)
              extra-tx (mapcat :tx enrichments)
              summary0 (read-edn (:operation-receipt/summary receipt))
              summary (assoc (or summary0 {})
                             :adventure-activity summaries)]
          (-> built
              (update :tx #(into (mapv (fn [item]
                                         (if (= item receipt)
                                           (assoc item :operation-receipt/summary
                                                  (pr-str summary))
                                           item))
                                       %)
                                 extra-tx))
              (assoc-in [:result :adventure-activity] summaries)))))))
