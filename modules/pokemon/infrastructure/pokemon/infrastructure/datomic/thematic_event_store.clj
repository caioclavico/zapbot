(ns pokemon.infrastructure.datomic.thematic-event-store
  "Persistência de agenda/ocorrências temáticas + claim de missões (12.8.1).
  Flag off → tick no-op. Outbox reutiliza :pokemon-outbox/*."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.event-missions :as event-missions]
            [pokemon.domain.event-schedule :as sched]
            [pokemon.domain.events :as events]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mission-rewards :as rewards]
            [pokemon.domain.xp :as xp]))

(defn- read-edn [s] (if (string? s) (edn/read-string s) (or s {})))

(defn schedule->map
  [e]
  (when (and e (:thematic-event-schedule/chat-id e))
    {:chat-id (:thematic-event-schedule/chat-id e)
     :enabled (boolean (:thematic-event-schedule/enabled e))
     :next-at (:thematic-event-schedule/next-at e 0)
     :last-catalog-id (:thematic-event-schedule/last-catalog-id e)
     :last-occurrence-id (:thematic-event-schedule/last-occurrence-id e)
     :last-start-ms (:thematic-event-schedule/last-start-ms e)
     :revision (long (:thematic-event-schedule/revision e 0))
     :policy (read-edn (:thematic-event-schedule/policy-edn e))}))

(defn occurrence->map
  [e]
  (when (and e (:thematic-event-occurrence/id e))
    {:occurrence-id (:thematic-event-occurrence/id e)
     :chat-id (:thematic-event-occurrence/chat-id e)
     :catalog-id (:thematic-event-occurrence/catalog-id e)
     :inicio-ms (:thematic-event-occurrence/inicio-ms e)
     :fim-ms (:thematic-event-occurrence/fim-ms e)
     :status (:thematic-event-occurrence/status e)
     :announced-start? (boolean (:thematic-event-occurrence/announced-start? e))
     :announced-end? (boolean (:thematic-event-occurrence/announced-end? e))
     :created-at (:thematic-event-occurrence/created-at e)}))

(defn load-schedule [db chat-id]
  (schedule->map (d/pull db '[*] [:thematic-event-schedule/chat-id chat-id])))

(defn- occurrences-by-status
  [db chat-id status]
  (let [ids (map first
                 (d/q '[:find ?id
                        :in $ ?chat ?st
                        :where
                        [?e :thematic-event-occurrence/chat-id ?chat]
                        [?e :thematic-event-occurrence/status ?st]
                        [?e :thematic-event-occurrence/id ?id]]
                      db chat-id status))]
    (->> ids
         (map #(occurrence->map (d/pull db '[*] [:thematic-event-occurrence/id %])))
         (filter some?)
         vec)))

(defn active-occurrences
  "Ocorrências status=active ainda dentro da janela (gameplay/caça/missões)."
  [db chat-id agora-ms]
  (->> (occurrences-by-status db chat-id "active")
       (filter #(< (long agora-ms) (long (:fim-ms % 0))))
       vec))

(defn open-occurrences
  "Status=active inclusive já vencidas — para o tick encerrar."
  [db chat-id]
  (occurrences-by-status db chat-id "active"))

(defn history
  [db chat-id limit]
  (let [ids (d/q '[:find ?id ?start
                   :in $ ?chat
                   :where
                   [?e :thematic-event-occurrence/chat-id ?chat]
                   [?e :thematic-event-occurrence/id ?id]
                   [?e :thematic-event-occurrence/inicio-ms ?start]]
                 db chat-id)]
    (->> ids
         (sort-by second >)
         (take (long (or limit 20)))
         (mapv (fn [[id _]]
                 (occurrence->map (d/pull db '[*] [:thematic-event-occurrence/id id])))))))

(defn- outbox-tx
  [event-id chat-id kind texto agora]
  [{:pokemon-outbox/id event-id
    :pokemon-outbox/chat-id chat-id
    :pokemon-outbox/kind kind
    :pokemon-outbox/texto texto
    :pokemon-outbox/created-at (long agora)}])

(defn ensure-schedule!
  [conn chat-id agora-ms]
  (let [db (d/db conn)
        existing (load-schedule db chat-id)]
    (if existing
      existing
      (do
        (d/transact conn {:tx-data [{:thematic-event-schedule/chat-id chat-id
                                     :thematic-event-schedule/enabled false
                                     :thematic-event-schedule/next-at (long agora-ms)
                                     :thematic-event-schedule/revision 0}]})
        (load-schedule (d/db conn) chat-id)))))

(defn set-schedule-enabled!
  [conn chat-id enabled? agora-ms]
  (ensure-schedule! conn chat-id agora-ms)
  (d/transact conn {:tx-data [[:db/add [:thematic-event-schedule/chat-id chat-id]
                               :thematic-event-schedule/enabled (boolean enabled?)]]})
  (load-schedule (d/db conn) chat-id))

(defn admin-schedule!
  "Agenda next-at / force catalog / duração forçada (h) / enable.
   duration-h ⇒ one-shot: force-duration-h + disable-on-end (sem recorrência)."
  [conn chat-id {:keys [next-at catalog-id enabled? duration-h]}]
  (ensure-schedule! conn chat-id (or next-at (System/currentTimeMillis)))
  (let [policy (cond-> {}
                 catalog-id (assoc :force-catalog-id (str catalog-id))
                 (some? duration-h) (assoc :force-duration-h (long duration-h)
                                           :disable-on-end true))
        tx (cond-> [[:db/add [:thematic-event-schedule/chat-id chat-id]
                     :thematic-event-schedule/next-at (long next-at)]]
             (some? enabled?)
             (conj [:db/add [:thematic-event-schedule/chat-id chat-id]
                    :thematic-event-schedule/enabled (boolean enabled?)])
             (seq policy)
             (conj [:db/add [:thematic-event-schedule/chat-id chat-id]
                    :thematic-event-schedule/policy-edn (pr-str policy)]))]
    (d/transact conn {:tx-data tx})
    (load-schedule (d/db conn) chat-id)))

(defn- force-catalog-id [sched-m]
  (get-in sched-m [:policy :force-catalog-id]))

(defn- force-duration-ms [sched-m catalog-id]
  (if-let [h (get-in sched-m [:policy :force-duration-h])]
    (* (long h) 60 60 1000)
    (sched/duration-ms catalog-id)))

(defn- disable-on-end? [sched-m]
  (true? (get-in sched-m [:policy :disable-on-end])))

(defn- policy-after-force-start
  "Remove force-* mas preserva disable-on-end para o encerramento one-shot."
  [sched-m]
  (cond-> {}
    (disable-on-end? sched-m) (assoc :disable-on-end true)))

(defn tick-chat!
  "Processa fim de ocorrências + possível ativação. Idempotente."
  [conn chat-id agora-ms draw]
  (if-not (events/thematic-events-enabled?)
    {:skipped true :reason "flag-off"}
    (let [db (d/db conn)
          sched-m (or (load-schedule db chat-id)
                      (ensure-schedule! conn chat-id agora-ms))
          open (open-occurrences db chat-id)
          ends (filterv #(sched/should-end? % agora-ms) open)
          actives (filterv #(not (sched/should-end? % agora-ms)) open)
          tx-end
          (mapcat
           (fn [occ]
             (let [ev (events/by-id (:catalog-id occ))
                   oid (:occurrence-id occ)
                   end-outbox (str "thematic-end:" oid)
                   existing (d/pull db '[*] [:pokemon-outbox/id end-outbox])]
               (cond-> [[:db/add [:thematic-event-occurrence/id oid]
                         :thematic-event-occurrence/status "ended"]]
                 (and ev (not (:announced-end? occ)) (not (:pokemon-outbox/id existing)))
                 (concat (outbox-tx end-outbox chat-id "thematic-event-ended"
                                    (events/announce-end-text ev) agora-ms)
                         [[:db/add [:thematic-event-occurrence/id oid]
                           :thematic-event-occurrence/announced-end? true]]))))
           ends)
          ;; One-shot piloto: ao encerrar, desliga agenda (impede recorrência em next-at).
          tx-disable
          (when (and (seq ends) (disable-on-end? sched-m) (:enabled sched-m))
            [[:db/add [:thematic-event-schedule/chat-id chat-id]
              :thematic-event-schedule/enabled false]
             [:db/add [:thematic-event-schedule/chat-id chat-id]
              :thematic-event-schedule/policy-edn (pr-str {})]])
          forced (force-catalog-id sched-m)
          ;; Não inicia novo evento no mesmo tick em que encerrou (one-shot / anti-corrida).
          plan (when (and (:enabled sched-m) (empty? ends) (nil? tx-disable))
                 (if (and forced (empty? actives))
                   (let [start (long agora-ms)
                         dur (force-duration-ms sched-m forced)
                         oid (sched/occurrence-id chat-id forced start)]
                     {:catalog-id forced :occurrence-id oid
                      :inicio-ms start :fim-ms (+ start dur)
                      :chat-id chat-id :status "active"})
                   (sched/plan-activation-for-chat
                    chat-id
                    {:enabled? true
                     :agora-ms agora-ms
                     :next-at (:next-at sched-m)
                     :last-catalog-id (:last-catalog-id sched-m)
                     :last-start-ms (:last-start-ms sched-m)
                     :active-occurrences actives
                     :draw (or draw 0)})))
          existing-occ (when plan
                         (d/pull db '[*] [:thematic-event-occurrence/id (:occurrence-id plan)]))
          tx-start
          (when (and plan (not (:thematic-event-occurrence/id existing-occ)))
            (let [ev (events/by-id (:catalog-id plan))
                  oid (:occurrence-id plan)
                  start-outbox (str "thematic-start:" oid)
                  rem (sched/format-remaining (- (:fim-ms plan) (:inicio-ms plan)))
                  policy' (policy-after-force-start sched-m)]
              (concat
               [{:thematic-event-occurrence/id oid
                 :thematic-event-occurrence/chat-id chat-id
                 :thematic-event-occurrence/catalog-id (:catalog-id plan)
                 :thematic-event-occurrence/inicio-ms (:inicio-ms plan)
                 :thematic-event-occurrence/fim-ms (:fim-ms plan)
                 :thematic-event-occurrence/status "active"
                 :thematic-event-occurrence/announced-start? true
                 :thematic-event-occurrence/announced-end? false
                 :thematic-event-occurrence/created-at (long agora-ms)}
                [:db/add [:thematic-event-schedule/chat-id chat-id]
                 :thematic-event-schedule/last-catalog-id (:catalog-id plan)]
                [:db/add [:thematic-event-schedule/chat-id chat-id]
                 :thematic-event-schedule/last-occurrence-id oid]
                [:db/add [:thematic-event-schedule/chat-id chat-id]
                 :thematic-event-schedule/last-start-ms (:inicio-ms plan)]
                [:db/add [:thematic-event-schedule/chat-id chat-id]
                 :thematic-event-schedule/next-at (sched/next-slot-after (:inicio-ms plan))]
                [:db/add [:thematic-event-schedule/chat-id chat-id]
                 :thematic-event-schedule/revision (inc (long (:revision sched-m 0)))]
                ;; Limpa force-* ; preserva disable-on-end se one-shot
                [:db/add [:thematic-event-schedule/chat-id chat-id]
                 :thematic-event-schedule/policy-edn (pr-str policy')]]
               (when ev
                 (outbox-tx start-outbox chat-id "thematic-event-started"
                            (events/announce-start-text ev rem) agora-ms)))))
          tx (vec (concat tx-end tx-disable tx-start))]
      (when (seq tx)
        (d/transact conn {:tx-data tx}))
      {:ended (mapv :occurrence-id ends)
       :started (when (and plan (not (:thematic-event-occurrence/id existing-occ)))
                  (:occurrence-id plan))
       :disabled-on-end (boolean (seq tx-disable))
       :schedule (load-schedule (d/db conn) chat-id)
       :active (active-occurrences (d/db conn) chat-id agora-ms)})))

(defn cancel-occurrence!
  "Desativação emergencial: status cancelled + anúncio de fim (idempotente).
   Se o schedule do chat tiver disable-on-end, desliga a agenda."
  [conn occurrence-id]
  (let [db (d/db conn)
        e (d/pull db '[*] [:thematic-event-occurrence/id occurrence-id])
        occ (occurrence->map e)]
    (when-not (:thematic-event-occurrence/id e)
      (throw (err/error :not_found {:occurrence-id occurrence-id})))
    (let [agora (System/currentTimeMillis)
          chat-id (:chat-id occ)
          sched-m (load-schedule db chat-id)
          ev (events/by-id (:catalog-id occ))
          end-outbox (str "thematic-end:" occurrence-id)
          existing (d/pull db '[*] [:pokemon-outbox/id end-outbox])
          tx (cond-> [[:db/add [:thematic-event-occurrence/id occurrence-id]
                       :thematic-event-occurrence/status "cancelled"]]
               (and ev (not (:announced-end? occ)) (not (:pokemon-outbox/id existing)))
               (concat (outbox-tx end-outbox chat-id "thematic-event-ended"
                                  (events/announce-end-text ev) agora)
                       [[:db/add [:thematic-event-occurrence/id occurrence-id]
                         :thematic-event-occurrence/announced-end? true]])
               (and sched-m (disable-on-end? sched-m) (:enabled sched-m))
               (concat [[:db/add [:thematic-event-schedule/chat-id chat-id]
                         :thematic-event-schedule/enabled false]
                        [:db/add [:thematic-event-schedule/chat-id chat-id]
                         :thematic-event-schedule/policy-edn (pr-str {})]]))]
      (d/transact conn {:tx-data (vec tx)})
      (occurrence->map (d/pull (d/db conn) '[*] [:thematic-event-occurrence/id occurrence-id])))))

(defn- trainer-level [save]
  (:nivel (xp/progresso-treinador
           (max (long (:save/trainer-xp save 0))
                (xp/xp-treinador-total
                 (into {} (for [k [:vitorias-treinador :pe-ginasios :pe-raids :xp-insignias :xp-missoes]]
                            [k (get save (keyword "save" (name k)) 0)])))))))

(defn- grant-items
  [db save sk ref inventory items base-pending adjust]
  (let [capacity (capacity/capacidade-mochila (trainer-level save)
                                              (:save/expansoes-mochila-50 save 0)
                                              (:save/capacidade-mochila-legada save 50))
        free (max 0 (- capacity (reduce + 0 (map :qty inventory))))
        allocated (rewards/allocate items base-pending free)
        tx (mapcat (fn [[item n]]
                     (map #(assoc % :inventory-item/save ref) (adjust db sk item n)))
                   (:added allocated))]
    (assoc allocated :tx (conj (vec tx) [:db/add ref :save/recompensas-pendentes-edn
                                         (pr-str (:pending allocated))]))))

(defn- claim-receipt-result
  [db operation-id]
  (let [prior (d/pull db '[*] [:operation-receipt/id operation-id])]
    (when (:operation-receipt/id prior)
      (let [summary (read-edn (:operation-receipt/summary prior))]
        (assoc (:result summary) :duplicate true :operation-id operation-id)))))

(defn claim-event-missions!
  "Resgate idempotente com retry em CAS (dois clientes / restart)."
  [conn writer {:keys [occurrence-id operation-id payload-hash draw inventory-query adjust]}]
  (let [{:keys [chat-id player-id save-id fence-generation]} writer
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)]
    (loop [attempt 0]
      (or (claim-receipt-result (d/db conn) operation-id)
          (let [db (d/db conn)
                occ (occurrence->map (d/pull db '[*] [:thematic-event-occurrence/id occurrence-id]))
                _ (when-not occ (throw (err/error :not_found {:occurrence-id occurrence-id})))
                _ (when-not (= chat-id (:chat-id occ))
                    (throw (err/error :forbidden {:occurrence-id occurrence-id})))
                player (d/pull db '[*] [:player/key pk])
                _ (when (or (not= save-id (:player/active-save-id player))
                            (and (some? fence-generation)
                                 (not= fence-generation (:player/fence-generation player))))
                    (throw (err/error :stale_writer_fence
                                      {:expected writer
                                       :active (:player/active-save-id player)
                                       :fence (:player/fence-generation player)})))
                save (d/pull db '[*] [:save/key sk])
                _ (when-not (:save/key save) (throw (err/error :save_not_found {:save-id save-id})))
                events-edn (read-edn (:save/missoes-eventos-edn save))
                st (event-missions/occurrence-state events-edn occurrence-id)
                claim (event-missions/build-claim (:catalog-id occ) st (or draw (fn [_ _] 0)))
                _ (when (empty? (:ids claim))
                    (throw (err/error :mission_nothing_to_claim {:occurrence-id occurrence-id})))
                ref [:save/key sk]
                inventory (inventory-query db sk)
                pending (read-edn (:save/recompensas-pendentes-edn save))
                grant (grant-items db save sk ref inventory (:items claim) pending adjust)
                candy-map (events/grant-candy-map (:doces claim))
                cards0 (read-edn (:save/professor-cards-edn save))
                cards' (merge-with + cards0 candy-map)
                events' (event-missions/upsert-occurrence-state events-edn occurrence-id (:state claim))
                rev (:save/revision save)
                result {:kind "event" :occurrence-id occurrence-id :ids (:ids claim)
                        :items (:items claim) :doces candy-map
                        :added (:added grant) :pending (:pending grant)
                        :operation-id operation-id :duplicate false}
                receipt {:db/id "thematic-mission-receipt"
                         :operation-receipt/id operation-id
                         :operation-receipt/kind "missions-claim-event"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash payload-hash
                         :operation-receipt/player-key pk
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary (pr-str {:result result})}
                fence (or fence-generation (:player/fence-generation player))
                tx (into [[:db/cas ref :save/revision rev (inc (or rev 0))]
                          [:db/cas [:player/key pk] :player/active-save-id save-id save-id]
                          [:db/cas [:player/key pk] :player/fence-generation fence fence]
                          [:db/add ref :save/missoes-eventos-edn (pr-str events')]
                          [:db/add ref :save/professor-cards-edn (pr-str cards')]]
                         (conj (:tx grant) receipt))]
            (let [outcome
                  (try
                    (d/transact conn {:tx-data tx})
                    {:ok true :value result}
                    (catch Exception ex
                      {:ok false :ex ex}))]
              (if (:ok outcome)
                (:value outcome)
                (or (claim-receipt-result (d/db conn) operation-id)
                    (let [ex (:ex outcome)]
                      (if (and (err/conflict? ex) (< attempt 8))
                        (recur (inc attempt))
                        (throw (if (err/conflict? ex)
                                 (err/error :writer_fence_conflict
                                            {:cause (ex-message ex) :attempt attempt})
                                 ex))))))))))))
