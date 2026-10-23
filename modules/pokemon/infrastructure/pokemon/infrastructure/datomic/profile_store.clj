(ns pokemon.infrastructure.datomic.profile-store
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.profile :as profile]
            [pokemon.domain.xp :as xp]
            [pokemon.domain.reward :as reward]))

(defn- save-key [chat player save] (id/save-key chat player save))
(defn- save [db chat player slot] (d/pull db '[*] [:save/key (save-key chat player slot)]))
(defn- player [db chat player-id] (d/pull db '[*] [:player/key (id/player-key chat player-id)]))
(defn- present? [x] (boolean (:db/id x)))
(defn- read-edn [x] (if (seq x) (edn/read-string x) {}))
(defn- pokemon-at [db sk idx]
  (when-let [eid (ffirst (d/q '[:find ?p :in $ ?sk ?idx :where
                                 [?s :save/key ?sk] [?p :pokemon/save ?s]
                                 [?p :pokemon/collection-index ?idx]] db sk idx))]
    (d/pull db '[*] eid)))
(defn- receipt [db op]
  (let [r (d/pull db '[*] [:operation-receipt/id op])]
    (when (:db/id r) r)))
(defn- result [op summary] {:operation-id op :summary summary})
(defn- transact! [conn ctx payload build]
  (let [{:keys [chat-id player-id save-id fence-generation operation-id]} ctx
        ph (reward/payload-hash payload) sk (save-key chat-id player-id save-id)]
    (if-let [r (receipt (d/db conn) operation-id)]
      (do (reward/assert-receipt-payload! (:operation-receipt/payload-hash r) ph operation-id)
          (assoc (result operation-id (read-edn (:operation-receipt/summary r))) :duplicate true))
      (loop [n 0]
      (let [db (d/db conn) p (player db chat-id player-id) s (save db chat-id player-id save-id)]
        (when-not (and (present? p) (present? s)) (throw (err/error :save_not_found {:save-id save-id})))
        (when (or (not= (:player/active-save-id p) save-id)
                  (not= (:player/fence-generation p) fence-generation))
          (throw (err/error :stale_writer_fence {})))
        (let [rev (long (:save/revision s 0))
              built (build db s)
              tx (into [[:db/cas [:player/key (id/player-key chat-id player-id)] :player/fence-generation fence-generation fence-generation]
                        [:db/cas [:player/key (id/player-key chat-id player-id)] :player/active-save-id save-id save-id]
                        [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id :operation-receipt/kind "profile"
                         :operation-receipt/channel "gameplay" :operation-receipt/payload-hash ph
                         :operation-receipt/player-key (id/player-key chat-id player-id)
                         :operation-receipt/save-id save-id :operation-receipt/summary (pr-str (:summary built))}]
                       (:tx built))]
          (let [attempt (try
                          (d/transact conn {:tx-data tx})
                          {:ok true}
                          (catch Exception e {:ok false :error e}))]
            (if (:ok attempt)
              (result operation-id (:summary built))
              (if (< n 8)
                (recur (inc n))
                (throw (err/error :writer_fence_conflict {:cause (ex-message (:error attempt))})))))))))))

(defn friendship [conn chat player slot idx]
  (let [db (d/db conn) sk (save-key chat player slot) s (save db chat player slot) p (pokemon-at db sk idx)]
    (when-not (present? s) (throw (err/error :save_not_found {:save-id slot})))
    (when-not p (throw (err/error :invalid_collection_index {:index idx})))
    (merge {:collection-index idx :id-pokemon (:pokemon/id-pokemon p) :species (:pokemon/species p)}
           (profile/friendship-band (:pokemon/amizade p)))))

(defn titles [conn chat player slot]
  (let [s (save (d/db conn) chat player slot)]
    (when-not (present? s) (throw (err/error :save_not_found {:save-id slot})))
    {:selected (:save/title s) :titles (profile/available-titles
      {:doacoes (:save/doacoes-pokemon s 0) :vitorias-treinador (:save/vitorias-treinador s 0)
       :maior-sequencia-capturas (:save/maior-sequencia-capturas s 0)})}))

(defn select-title! [conn ctx index]
  (let [payload {:action :select-title :index index}
        title (profile/title-at (save (d/db conn) (:chat-id ctx) (:player-id ctx) (:save-id ctx)) index)]
    (when-not title (throw (err/error :invalid_title {:index index})))
    (transact! conn ctx payload (fn [_ s] {:summary {:title (:id title) :label (:label title)}
                                           :tx [[:db/add [:save/key (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
                                                 :save/title (:id title)]]}))))

(defn professor-cards [conn chat player slot]
  (let [s (save (d/db conn) chat player slot)]
    (when-not (present? s) (throw (err/error :save_not_found {:save-id slot})))
    {:cards (read-edn (:save/professor-cards-edn s))}))

(defn- pending [s] (read-edn (:save/professor-pending-edn s)))
(defn- collection [db sk]
  (->> (d/q '[:find ?p :in $ ?sk :where
              [?s :save/key ?sk] [?p :pokemon/save ?s]
              [?p :pokemon/collection-index _]] db sk)
       (map first)
       (map #(d/pull db '[*] %))
       (sort-by :pokemon/collection-index)
       vec))

(defn professor-send! [conn ctx idx now]
  (let [payload {:action :professor-send :index idx}
        sk (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
    (transact! conn ctx payload
      (fn [db s]
        (when (seq (pending s)) (throw (err/error :professor_busy {})))
        (when (:player/active-battle-id (player db (:chat-id ctx) (:player-id ctx)))
          (throw (err/error :battle_busy {})))
        (let [p (pokemon-at db sk idx)]
          (when-not p (throw (err/error :invalid_collection_index {:index idx})))
          (when (or (:pokemon/favorite? p)
                    (= (:save/favorito-id-pokemon s) (:pokemon/id-pokemon p)))
            (throw (err/error :pokemon_favorite {})))
          (let [token (str (+ 100 (mod (Math/abs (hash (:operation-id ctx))) 900)))]
            (let [pend {:token token :expires-at (+ now 300000) :species (:pokemon/species p)
                        :id-pokemon (:pokemon/id-pokemon p) :index idx
                        :family (profile/family (:pokemon/species p))}]
              {:summary pend
               :tx [[:db/add [:save/key sk] :save/professor-pending-edn (pr-str pend)] ]})))))))

(defn professor-cancel! [conn ctx]
  (transact! conn ctx {:action :professor-cancel}
             (fn [_ _] {:summary {:cancelled true :pending nil}
                         :tx [[:db/retract [:save/key (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
                                               :save/professor-pending-edn]]})))

(defn professor-confirm! [conn ctx token now]
  (let [payload {:action :professor-confirm :token token}
        sk (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
    (transact! conn ctx payload
      (fn [db s]
        (let [pend (pending s)]
          (when-not (= token (:token pend)) (throw (err/error :professor_invalid_token {})))
          (when (>= now (long (:expires-at pend 0))) (throw (err/error :professor_expired {})))
          (let [eid (ffirst (d/q '[:find ?p :in $ ?id :where [?p :pokemon/id-pokemon ?id]] db (:id-pokemon pend)))
                p (when eid (d/pull db '[*] eid))]
            (when-not p (throw (err/error :pokemon_not_found {})))
            (let [idx (:index pend)
                  rest-pokemon (remove #(= (:db/id %) (:db/id p)) (collection db sk))
                  item (:pokemon/item p)
                  item-key (when item (id/inventory-item-key sk item))
                  old-qty (long (:inventory-item/qty (when item (d/pull db '[:inventory-item/qty]
                                                                          [:inventory-item/key item-key])) 0))
                  reindex-tx (mapcat (fn [q]
                                       (when (> (long (:pokemon/collection-index q)) (long idx))
                                         [[:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon q)]
                                                    :pokemon/collection-index (dec (long (:pokemon/collection-index q)))]]))
                                     rest-pokemon)
                  active (long (:save/ativo-index s 0))
                  next-active (cond (= active idx) 0 (> active idx) (dec active) :else active)
                  item-tx (when item
                            [{:inventory-item/key item-key :inventory-item/save [:save/key sk]
                              :inventory-item/item-id item :inventory-item/qty (inc old-qty)}])]
             (let [base-tx [[:db/retractEntity (:db/id p)]
                            [:db/retract [:save/key sk] :save/professor-pending-edn]
                            [:db/add [:save/key sk] :save/professor-cards-edn
                                      (pr-str (update (read-edn (:save/professor-cards-edn s))
                                                      (:family pend) (fnil inc 0)))]
                            [:db/add [:save/key sk] :save/doacoes-pokemon
                                      (inc (long (:save/doacoes-pokemon s 0)))]
                            [:db/add [:save/key sk] :save/ativo-index next-active]]]
               {:summary {:confirmed true :family (:family pend) :id-pokemon (:id-pokemon pend)
                          :item-returned item}
                :tx (vec (concat base-tx reindex-tx item-tx))}))))))))

(defn professor-use! [conn ctx idx]
  (let [payload {:action :professor-use :index idx} sk (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
    (transact! conn ctx payload
      (fn [db s]
        (let [p (pokemon-at db sk idx)]
          (when-not p (throw (err/error :invalid_collection_index {:index idx})))
          (let [fam (profile/family (:pokemon/species p)) cards (read-edn (:save/professor-cards-edn s))]
            (when-not (pos? (long (get cards fam 0))) (throw (err/error :professor_no_cards {})))
            (when (>= (long (:pokemon/level p 1)) xp/nivel-maximo)
              (throw (err/error :pokemon_max_level {})))
            (let [applied (xp/aplicar-xp-pokemon p 3)
                  n (:registro applied)]
            {:summary {:used true :family fam :id-pokemon (:pokemon/id-pokemon p)
                       :level (:nivel-final applied)}
             :tx [[:db/add [:save/key sk] :save/professor-cards-edn
                   (pr-str (update cards fam dec))]
                  [:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon p)] :pokemon/xp-desde-nivel
                   (long (:xp-desde-nivel n))]
                  [:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon p)] :pokemon/level (long (:nivel n))]
                  [:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon p)] :pokemon/hp (long (:hp n))]
                  [:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon p)] :pokemon/hp-atual (long (:hp-atual n))]
                  [:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon p)] :pokemon/amizade (long (:amizade n))]]})))))))
