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

(defn- read-edn-safe [s]
  (when (string? s)
    (try (edn/read-string s) (catch Exception _ ::invalid))))

(defn summarize
  "Summarize the supplied existing DB value. Performs queries only; never transacts."
  [db p0-hotfix-at-ms]
  (let [battles (rows-with db :battle/id
                           [:battle/id :battle/kind :battle/active? :battle/phase
                            :battle/idle-expired? :battle/last-activity-at
                            :battle/state-edn])
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
        receipts (rows-with-value db :operation-receipt/kind "gym-challenge-start"
                                  [:operation-receipt/kind :operation-receipt/summary])
        refs (keep (fn [r]
                     (let [summary (read-edn-safe (:operation-receipt/summary r))]
                       (when (map? summary) (:battle-id summary)))) receipts)
        pokemon (rows-with db :pokemon/key
                           [:pokemon/key :pokemon/hp :pokemon/hp-atual
                            :pokemon/species :pokemon/level :pokemon/xp])
        occupations (rows-with db :gym-occupation/key
                               [:gym-occupation/key :gym-occupation/team-edn])
        migrations (set (map first (d/q '[:find ?id
                                          :where [?e :odisseu.schema/migration-id ?id]] db)))
        pre-hotfix (when p0-hotfix-at-ms
                     (count (filter #(and (open? %)
                                          (number? (:battle/last-activity-at %))
                                          (< (long (:battle/last-activity-at %))
                                             (long p0-hotfix-at-ms))) gyms)))]
    {:migrations {:pokemon-033? (contains? migrations "pokemon/033-trade-public-id")
                  :pokemon-034? (contains? migrations "pokemon/034-gym-permanence-daily")}
     :battles {:total (count battles)
               :active (count (filter #(true? (:battle/active? %)) battles))
               :gym-total (count gyms)
               :gym-open-or-resumable (count open-gyms)
               :gym-inconsistent-active-flag (count inconsistent)
               :gym-last-activity-before-p0 (or pre-hotfix :cutoff-not-provided)}
     :gym-start-receipts {:committed (count receipts)
                          :with-battle-reference (count refs)
                          :referenced-battle-present (count (filter battle-ids refs))
                          :referenced-battle-missing (count (remove battle-ids refs))
                          :summary-without-battle-reference (- (count receipts) (count refs))
                          :open-gym-without-start-receipt
                          (count (remove (set refs) (map :battle/id open-gyms)))}
     :receipts {:pending-state-modeled? false}
     :operations {:partial-datomic-transactions-possible? false}
     :pokemon {:count (count pokemon)
               :hp-zero (count (filter #(= 0 (:pokemon/hp-atual %)) pokemon))
               :hp-missing (count (filter #(nil? (:pokemon/hp-atual %)) pokemon))
               :hp-above-max (count (filter #(and (number? (:pokemon/hp-atual %))
                                                   (number? (:pokemon/hp %))
                                                   (> (:pokemon/hp-atual %) (:pokemon/hp %))) pokemon))
               :species-missing (count (filter #(nil? (:pokemon/species %)) pokemon))
               :level-missing (count (filter #(nil? (:pokemon/level %)) pokemon))
               :xp-missing (count (filter #(nil? (:pokemon/xp %)) pokemon))}
     :gym-occupations {:count (count occupations)
                       :invalid-defender-team-count
                       (count (filter (fn [o]
                                        (let [team (read-edn-safe (:gym-occupation/team-edn o))]
                                          (or (not (vector? team)) (not= 3 (count team)))))
                                      occupations))}}))
