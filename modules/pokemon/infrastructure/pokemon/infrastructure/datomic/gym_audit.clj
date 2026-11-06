(ns pokemon.infrastructure.datomic.gym-audit
  "Read-only aggregate diagnostics for the A6 gym safety gate.
   Output is aggregate-only; identifiers and save payloads never leave this namespace."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]))

(defn- rows-with [db attr attrs]
  (mapv (fn [[eid]] (d/pull db attrs eid))
        (d/q '[:find ?e :in $ ?a :where [?e ?a]] db attr)))

(defn- rows-with-value [db attr value attrs]
  (mapv (fn [[eid]] (d/pull db attrs eid))
        (d/q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn- attrs-present? [db attrs]
  (every? (fn [attr]
            (seq (d/q '[:find ?e :in $ ?ident :where [?e :db/ident ?ident]] db attr)))
          attrs))

(defn- read-edn-safe [s]
  (when (string? s)
    (try (edn/read-string s) (catch Exception _ ::invalid))))

(defn- schedule-summary [db key-attr enabled-attr next-attr marker-attr]
  (let [attrs [key-attr enabled-attr next-attr marker-attr]
        available? (attrs-present? db attrs)
        rows (if available? (rows-with db key-attr attrs) [])
        marker-frequencies (frequencies (keep marker-attr rows))]
    (if available?
      {:status :available
       :count (count rows)
       :enabled (count (filter #(true? (enabled-attr %)) rows))
       :next-scheduled-at (some->> rows (keep next-attr) seq (apply min))
       :with-persisted-marker (count (filter #(some? (marker-attr %)) rows))
       :duplicate-marker-groups (count (filter #(> (val %) 1) marker-frequencies))}
      {:status :unknown})))

(defn summarize
  "Summarize the supplied existing DB value. Performs queries only; never transacts."
  [db p0-hotfix-at-ms]
  (let [battle-attrs [:battle/id :battle/kind :battle/active? :battle/phase
                      :battle/idle-expired? :battle/last-activity-at :battle/state-edn]
        battles-known? (attrs-present? db battle-attrs)
        battles (if battles-known? (rows-with db :battle/id battle-attrs) [])
        gyms (filter #(= "gym" (:battle/kind %)) battles)
        open? (fn [b]
                (and (not (true? (:battle/idle-expired? b)))
                     (or (true? (:battle/active? b))
                         (= "waiting" (:battle/phase b))
                         (true? (:aguardando-captura?
                                 (read-edn-safe (:battle/state-edn b)))))))
        open-gyms (filter open? gyms)
        inconsistent (filter #(and (not (true? (:battle/idle-expired? %)))
                                   (false? (:battle/active? %))
                                   (contains? #{"active" "waiting"} (:battle/phase %))) gyms)
        battle-ids (set (map :battle/id battles))
        receipt-attrs [:operation-receipt/kind :operation-receipt/summary]
        receipts-known? (attrs-present? db receipt-attrs)
        receipts (if receipts-known?
                   (rows-with-value db :operation-receipt/kind "gym-challenge-start" receipt-attrs)
                   [])
        refs (keep (fn [r]
                     (let [summary (read-edn-safe (:operation-receipt/summary r))]
                       (when (map? summary) (:battle-id summary)))) receipts)
        pokemon-attrs [:pokemon/key :pokemon/hp :pokemon/hp-atual
                       :pokemon/species :pokemon/level :pokemon/xp]
        pokemon-known? (attrs-present? db pokemon-attrs)
        pokemon (if pokemon-known? (rows-with db :pokemon/key pokemon-attrs) [])
        occupation-attrs [:gym-occupation/key :gym-occupation/team-edn]
        occupations-known? (attrs-present? db occupation-attrs)
        occupations (if occupations-known? (rows-with db :gym-occupation/key occupation-attrs) [])
        raid-schedules (schedule-summary db :raid-auto-schedule/chat-id
                                         :raid-auto-schedule/enabled
                                         :raid-auto-schedule/next-at
                                         :raid-auto-schedule/last-raid-id)
        event-schedules (schedule-summary db :thematic-event-schedule/chat-id
                                          :thematic-event-schedule/enabled
                                          :thematic-event-schedule/next-at
                                          :thematic-event-schedule/last-occurrence-id)
        occurrence-attrs [:thematic-event-occurrence/id :thematic-event-occurrence/status]
        occurrences-known? (attrs-present? db occurrence-attrs)
        occurrences (if occurrences-known?
                      (rows-with db :thematic-event-occurrence/id occurrence-attrs) [])
        bag-attrs [:save/key :save/ultimo-bonus-diario :save/sequencia-bonus-diario
                   :save/kit-inicial-resgatado]
        bag-known? (attrs-present? db bag-attrs)
        bag-saves (if bag-known? (rows-with db :save/key bag-attrs) [])
        migrations-known? (attrs-present? db [:odisseu.schema/migration-id])
        migrations (if migrations-known?
                     (set (map first (d/q '[:find ?id
                                            :where [?e :odisseu.schema/migration-id ?id]] db)))
                     #{})
        pre-hotfix (when (and battles-known? p0-hotfix-at-ms)
                     (count (filter #(and (open? %)
                                          (number? (:battle/last-activity-at %))
                                          (< (long (:battle/last-activity-at %))
                                             (long p0-hotfix-at-ms))) gyms)))]
    {:migrations (if migrations-known?
                   {:status :available
                    :pokemon-033? (contains? migrations "pokemon/033-trade-public-id")
                    :pokemon-034? (contains? migrations "pokemon/034-gym-permanence-daily")}
                   {:status :unknown})
     :battles (if battles-known?
                {:status :available
                 :total (count battles)
                 :active (count (filter #(true? (:battle/active? %)) battles))
                 :gym-total (count gyms)
                 :gym-open-or-resumable (count open-gyms)
                 :gym-inconsistent-active-flag (count inconsistent)
                 :gym-last-activity-before-p0 (or pre-hotfix :cutoff-not-provided)}
                {:status :unknown})
     :gym-start-receipts (if (and receipts-known? battles-known?)
                           {:status :available
                            :committed (count receipts)
                            :with-battle-reference (count refs)
                            :referenced-battle-present (count (filter battle-ids refs))
                            :referenced-battle-missing (count (remove battle-ids refs))
                            :summary-without-battle-reference (- (count receipts) (count refs))
                            :open-gym-without-start-receipt
                            (count (remove (set refs) (map :battle/id open-gyms)))}
                           {:status :unknown})
     :receipts {:pending-state-modeled? false}
     :operations {:partial-datomic-transactions-possible? false
                  :delayed-mutations-status :unknown}
     :pokemon (if pokemon-known?
                {:status :available
                 :count (count pokemon)
                 :hp-zero (count (filter #(= 0 (:pokemon/hp-atual %)) pokemon))
                 :hp-missing (count (filter #(nil? (:pokemon/hp-atual %)) pokemon))
                 :hp-above-max (count (filter #(and (number? (:pokemon/hp-atual %))
                                                    (number? (:pokemon/hp %))
                                                    (> (:pokemon/hp-atual %) (:pokemon/hp %))) pokemon))
                 :species-missing (count (filter #(nil? (:pokemon/species %)) pokemon))
                 :level-missing (count (filter #(nil? (:pokemon/level %)) pokemon))
                 :xp-missing (count (filter #(nil? (:pokemon/xp %)) pokemon))}
                {:status :unknown})
     :gym-occupations (if occupations-known?
                        {:status :available
                         :count (count occupations)
                         :invalid-defender-team-count
                         (count (filter (fn [o]
                                          (let [team (read-edn-safe (:gym-occupation/team-edn o))]
                                            (or (not (vector? team)) (not= 3 (count team)))))
                                        occupations))}
                        {:status :unknown})
     :schedulers {:raids raid-schedules
                  :thematic-events
                  (if occurrences-known?
                    (assoc event-schedules
                           :occurrences (count occurrences)
                           :active-occurrences
                           (count (filter #(= "active" (:thematic-event-occurrence/status %)) occurrences))
                           :ended-occurrences
                           (count (filter #(= "ended" (:thematic-event-occurrence/status %)) occurrences))
                           :cancelled-occurrences
                           (count (filter #(= "cancelled" (:thematic-event-occurrence/status %)) occurrences))
                           :unknown-status-occurrences
                           (count (remove #(contains? #{"active" "ended" "cancelled"}
                                                      (:thematic-event-occurrence/status %)) occurrences)))
                    (assoc event-schedules :occurrences :unknown))}
     :bag-daily (if bag-known?
                  {:status :available
                   :saves-with-daily-bonus-marker
                   (count (filter #(some? (:save/ultimo-bonus-diario %)) bag-saves))
                   :saves-with-initial-kit-flag
                   (count (filter #(some? (:save/kit-inicial-resgatado %)) bag-saves))
                   :daily-kit-status :unknown}
                  {:status :unknown :daily-kit-status :unknown})}))
