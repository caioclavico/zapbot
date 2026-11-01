(ns pokemon.infrastructure.datomic.bag-store
  "TX builders — !pk mochila kit (via onboarding) e !pk mochila diario."
  (:require [clojure.edn :as edn]
            [pokemon.domain.bag-rewards :as bag]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.reward :as reward]
            [pokemon.infrastructure.datomic.onboarding-store :as onboarding-store]))

(defn- read-edn [s]
  (if (and s (not (empty? s))) (edn/read-string s) {}))

(defn- daily-flags-tx*
  [db chat-id player-id dia sequencia]
  (into []
        (mapcat (fn [e]
                  (let [sk (:save/key e)]
                    [[:db/add [:save/key sk] :save/ultimo-bonus-diario (str dia)]
                     [:db/add [:save/key sk] :save/sequencia-bonus-diario (long sequencia)]]))
                (onboarding-store/save-entities-for-player db chat-id player-id))))

(defn build-claim-daily-tx
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx draw instant timezone]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        ref [:save/key sk]
        operation-id (:operation-id request)
        tz (or timezone
               (not-empty (System/getenv "MISSOES_TIMEZONE"))
               mundo/default-timezone)
        dia (or (:dia request) (bag/dia-atual (or instant (java.time.Instant/now)) tz))
        ;; Anti-farm: se QUALQUER save já resgatou hoje, bloqueia.
        any-today?
        (some (fn [e]
                (bag/diario-ja-resgatado-hoje? (:save/ultimo-bonus-diario e) dia))
              (onboarding-store/save-entities-for-player db chat-id player-id))
        ultimo (:save/ultimo-bonus-diario save (:ultimo-bonus-diario save))
        seq0 (long (:save/sequencia-bonus-diario save (:sequencia-bonus-diario save 1)))
        rev (long (:save/revision save (:revision save 0)))]
    (if (or any-today? (bag/diario-ja-resgatado-hoje? ultimo dia))
      {:tx []
       :result {:operation-id operation-id
                :duplicate true
                :daily-already-claimed true
                :dia dia
                :timezone tz
                :items {}
                :added {}
                :pending (read-edn (:save/recompensas-pendentes-edn save))}}
      (let [sequencia (bag/proxima-sequencia-diaria ultimo dia seq0)
            items (or (:items request)
                      (bag/recompensas-diario sequencia draw))
            payload (reward/reward-payload
                     {:daily {:dia dia :sequencia sequencia :items items}})
            phash (reward/payload-hash payload)
            capacity (capacity/capacidade-mochila
                      (long (:save/trainer-level save (:trainer-level save 1)))
                      (long (:save/expansoes-mochila-50 save 0))
                      (long (:save/capacidade-mochila-legada save 50)))
            free (max 0 (- capacity (reduce + 0 (map :qty (or inventory [])))))
            base-pending (read-edn (:save/recompensas-pendentes-edn save))
            allocated (bag/allocate-diario items base-pending free)
            inv-tx (mapcat (fn [[item-id n]]
                             (inventory-adjust-tx db sk item-id n))
                           (:added allocated))
            flag-tx (daily-flags-tx* db chat-id player-id dia sequencia)
            tx (into (into (vec inv-tx) flag-tx)
                     [[:db/add ref :save/recompensas-pendentes-edn
                       (pr-str (:pending allocated))]
                      [:db/cas ref :save/revision rev (inc rev)]
                      {:operation-receipt/id operation-id
                       :operation-receipt/kind "bag-daily"
                       :operation-receipt/channel "gameplay"
                       :operation-receipt/payload-hash phash
                       :operation-receipt/player-key (id/player-key chat-id player-id)
                       :operation-receipt/save-id save-id
                       :operation-receipt/summary
                       (pr-str {:dia dia :sequencia sequencia
                                :items items
                                :added (:added allocated)
                                :pending (:pending allocated)})}])]
        {:tx tx
         :result {:operation-id operation-id
                  :duplicate false
                  :daily-already-claimed false
                  :dia dia
                  :timezone tz
                  :sequencia sequencia
                  :items items
                  :added (:added allocated)
                  :pending (:pending allocated)
                  :fruta-dourada (pos? (long (get items "fruta-dourada" 0)))
                  :payload-hash phash
                  :rewards-text (bag/texto-recompensas items)}}))))
