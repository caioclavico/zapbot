(ns pokemon.infrastructure.datomic.mission-economy
  "Builders atômicos: recompensa, pendências, presente e recibo do resultado."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.mission-rewards :as rewards]
            [pokemon.domain.mundo :as mundo]
            [pokemon.infrastructure.datomic.mission-queries :as queries]))

(def ^:dynamic *rand-int* rand-int)
(defn- read-edn [s] (if s (edn/read-string s) {}))

(defn freeze-target [db chat target]
  (let [player (d/pull db '[*] [:player/key (id/player-key chat target)])]
    {:player-id target :save-id (or (:player/active-save-id player) "a")
     :fence-generation (if (:player/active-save-id player) (:player/fence-generation player)
                          (inc (:player/fence-generation player 0)))}))

(defn- grant [db save sk ref inventory items base-pending adjust]
  (let [capacity (capacity/capacidade-mochila (queries/trainer-level save)
                                            (:save/expansoes-mochila-50 save 0)
                                            (:save/capacidade-mochila-legada save 50))
        free (max 0 (- capacity (reduce + 0 (map :qty inventory))))
        allocated (rewards/allocate items base-pending free)
        tx (mapcat (fn [[item n]]
                     (map #(assoc % :inventory-item/save ref) (adjust db sk item n))) (:added allocated))]
    (assoc allocated :tx (conj (vec tx) [:db/add ref :save/recompensas-pendentes-edn
                                       (pr-str (:pending allocated))]))))

(defn- recipient
  "Conta/saves já inicializados. Entrega usa lookup existente e CAS de revisão."
  [db chat {:keys [player-id save-id fence-generation]}]
  (let [pk (id/player-key chat player-id)
        player (d/pull db '[*] [:player/key pk])
        active (:player/active-save-id player)
        initializing? (nil? active)
        _ (when-not (and (= save-id (or active "a"))
                         (= fence-generation (if active (:player/fence-generation player)
                                                 (inc (:player/fence-generation player 0)))))
            (throw (err/error :gift_target_changed {:player-id player-id})))
        pref [:player/key pk]
        sk (id/save-key chat player-id save-id)
        save (d/pull db '[*] [:save/key sk])
        _ (when-not (and (:player/key player) (:save/key save) (not initializing?))
            (throw (err/error :no_active_save {:player-id player-id})))
        ref [:save/key sk]
        tx [[:db/cas pref :player/active-save-id active save-id]
                        [:db/cas pref :player/fence-generation (:player/fence-generation player)
                         fence-generation]
                        [:db/cas ref :save/revision (:save/revision save) (inc (:save/revision save 0))]]]
    {:save save :ref ref :key sk :tx tx}))

(defn build
  [{:keys [db writer request save kind instant draw target inventory-query adjust]}]
  (let [{:keys [chat-id player-id save-id]} writer
        sk (id/save-key chat-id player-id save-id)
        ref [:save/key sk]
        inventory (inventory-query db sk)
        timezone (or (not-empty (System/getenv "MISSOES_TIMEZONE")) mundo/default-timezone)
        {:keys [day week]} (catalog/periods instant timezone)
        period (if (= kind :daily) day week)
        operation-id (:operation-id request)
        payload-hash (:payload-hash request)
        pending (read-edn (:save/recompensas-pendentes-edn save))
        rev (:save/revision save)
        common [[:db/cas ref :save/revision rev (inc (or rev 0))]]
        built
        (case kind
          (:daily :weekly)
          (do
            (when-not (= period (:period request))
              (throw (err/error :mission_period_conflict {:period (:period request) :current period})))
            (let [daily? (= kind :daily)
                  attr (if daily? :save/missoes-diarias-edn :save/missoes-semanais-edn)
                  current (read-edn (get save attr))
                  _ (when (pos? (compare (get current (if daily? "dia" "semana") period) period))
                      (throw (err/error :mission_period_conflict {:period period})))
                  state (if daily? (catalog/daily-state current day (queries/trainer-level save))
                            (catalog/weekly-state current week))
                  reward (if daily? (rewards/daily state draw) (rewards/weekly state draw))
                  grant (grant db save sk ref inventory (:items reward) pending adjust)
                  coins (+ (:save/coins save 0) (:coins reward))
                  tx (into (conj common [:db/add ref attr (pr-str (:state reward))]) (:tx grant))]
              {:tx (cond-> tx
                     (pos? (:xp reward))
                     (conj [:db/add ref :save/xp-missoes (+ (:save/xp-missoes save 0) (:xp reward))])
                     (pos? (:coins reward))
                     (conj [:db/cas ref :save/coins (:save/coins save 0) coins]))
               :result {:kind (name kind) :period period :ids (:ids reward)
                        :empty (empty? (:ids reward)) :xp (:xp reward) :coins (:coins reward)
                        :coins-after coins :items (:items reward) :added (:added grant) :pending (:pending grant)}}))

          :gift
          (let [cards (some #(when (= "cartao-presente" (:item-id %)) (:qty %)) inventory)
                _ (when-not (pos? (or cards 0)) (throw (err/error :insufficient_item {:item-id "cartao-presente"})))
                recipient (recipient db chat-id target)
                items (rewards/gift (draw :gift 100))
                target-pending (read-edn (:save/recompensas-pendentes-edn (:save recipient)))
                grant (grant db (:save recipient) (:key recipient) (:ref recipient)
                             (inventory-query db (:key recipient)) items target-pending adjust)]
            {:tx (into common (concat (adjust db sk "cartao-presente" -1) (:tx recipient) (:tx grant)))
             :result {:kind "gift" :sent true :target-player-id (:player-id target)
                      :target-save-id (:save-id target)
                      :items items :added (:added grant) :pending (:pending grant)}})

          :pending
          (let [grant (grant db save sk ref inventory pending (apply dissoc pending rewards/order) adjust)]
            {:tx (into common (:tx grant))
             :result {:kind "pending" :items (:added grant) :added (:added grant)
                      :pending (:pending grant) :empty (empty? (:added grant))}}))
        result (merge (:result built) {:operation-id operation-id :duplicate false
                                       :chat-id chat-id :player-id player-id :save-id save-id})
        receipt {:db/id "mission-economic-receipt" :operation-receipt/id operation-id
                 :operation-receipt/kind (case kind :gift "gift-send" :daily "missions-claim-daily"
                                              :weekly "missions-claim-weekly" :pending "missions-pending")
                 :operation-receipt/channel "gameplay" :operation-receipt/payload-hash payload-hash
                 :operation-receipt/player-key (id/player-key chat-id player-id)
                 :operation-receipt/save-id save-id :operation-receipt/summary (pr-str {:result result})}]
    ;; :operation-receipt/id é :db.unique/value. Ao contrário de identity,
    ;; uma segunda escrita com o mesmo operation-id falha, em vez de fazer
    ;; upsert no recibo de outro treinador.
    {:tx (conj (:tx built) receipt)
     :result result}))
