(ns pokemon.infrastructure.datomic.profile-store
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.profile :as profile]
            [pokemon.domain.xp :as xp]
            [pokemon.domain.reward :as reward]
            [pokemon.infrastructure.datomic.trainer-progression :as trainer-progression]))

(defn- save-key [chat player save] (id/save-key chat player save))
(defn- save [db chat player slot] (d/pull db '[*] [:save/key (save-key chat player slot)]))
(defn- player [db chat player-id] (d/pull db '[*] [:player/key (id/player-key chat player-id)]))
(defn- present? [x] (boolean (:db/id x)))
(defn- read-edn [x] (if (seq x) (edn/read-string x) {}))
(defn- read-bed-pokemon [x] (or (battle/decode-compact-edn x) {}))
(defn- pokemon-at [db sk idx]
  (when-let [eid (ffirst (d/q '[:find ?p :in $ ?sk ?idx :where
                                 [?s :save/key ?sk] [?p :pokemon/save ?s]
                                 [?p :pokemon/collection-index ?idx]] db sk idx))]
    (d/pull db '[*] eid)))
(defn- receipt [db op]
  (let [r (d/pull db '[*] [:operation-receipt/id op])]
    (when (:db/id r) r)))
(defn- result
  "DTO plano para o Node (token/confirmed no topo) + :summary para recibos/testes."
  [op summary]
  (merge {:operation-id op :summary summary}
         (or summary {})))
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
                       (:tx built))
              progressed (trainer-progression/enrich db {:tx tx :result (:summary built)})
              tx (:tx progressed)
              summary (:result progressed)]
          (let [attempt (try
                          (d/transact conn {:tx-data tx})
                          {:ok true}
                          (catch Exception e {:ok false :error e}))]
            (if (:ok attempt)
              (result operation-id summary)
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
        s (save (d/db conn) (:chat-id ctx) (:player-id ctx) (:save-id ctx))
        title (profile/title-at {:doacoes (:save/doacoes-pokemon s 0)
                                 :vitorias-treinador (:save/vitorias-treinador s 0)
                                 :maior-sequencia-capturas (:save/maior-sequencia-capturas s 0)}
                                index)]
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

(defn- adjust-slots
  "Espelha ajustar-times-remocao do legado: remove idx e decrementa maiores."
  [slots idx]
  (mapv (fn [slot]
          (cond (nil? slot) nil
                (= (long slot) (long idx)) nil
                (> (long slot) (long idx)) (dec (long slot))
                :else slot))
        (vec (or slots []))))

(defn- adjust-slots-many
  "Aplica remoções em lote (índices altos primeiro)."
  [slots idxs]
  (reduce adjust-slots slots (sort > (map long idxs))))

(defn- next-active-after-removals
  [active removed-idxs]
  (let [removed (set (map long removed-idxs))
        active (long (or active 0))]
    (if (contains? removed active)
      0
      (- active (count (filter #(< % active) removed))))))

(defn- team-index-tx
  "Ajusta time-ginasio e times-liga (mapa liga→slots) após remoção(ões)."
  [sk s idx-or-idxs]
  (let [idxs (if (sequential? idx-or-idxs) (mapv long idx-or-idxs) [(long idx-or-idxs)])
        tg (let [v (read-edn (:save/time-ginasio-edn s))]
             (if (sequential? v) (vec v) []))
        tl (let [v (read-edn (:save/time-liga-edn s))]
             (cond (map? v) v
                   (sequential? v) {"default" (vec v)}
                   :else {}))
        tg' (adjust-slots-many tg idxs)
        tl' (into {} (map (fn [[liga slots]] [liga (adjust-slots-many slots idxs)]) tl))]
    (cond-> []
      (not= tg tg') (conj [:db/add [:save/key sk] :save/time-ginasio-edn (pr-str tg')])
      (not= tl tl') (conj [:db/add [:save/key sk] :save/time-liga-edn (pr-str tl')]))))

(defn- normalize-send-indexes
  "Aceita um índice, seq de índices ou nil."
  [idx-or-idxs]
  (cond
    (nil? idx-or-idxs) nil
    (number? idx-or-idxs) [(long idx-or-idxs)]
    (sequential? idx-or-idxs)
    (->> idx-or-idxs (map long) distinct sort vec)
    :else nil))

(defn- entry-from-pokemon
  [p idx]
  (let [nome (or (:pokemon/nickname p) (:pokemon/nome p) (:pokemon/species p) "?")]
    {:id-pokemon (:pokemon/id-pokemon p)
     :index (long idx)
     :display-index (inc (long idx))
     :species (:pokemon/species p)
     :nome nome
     :nivel (long (:pokemon/level p 1))
     :shiny (boolean (or (:pokemon/shiny? p) (:pokemon/shiny p)))
     :item (:pokemon/item p)
     :family (profile/family (:pokemon/species p))}))

(defn- in-enfermaria?
  [db sk id-pokemon]
  (let [beds (d/q '[:find ?e :in $ ?sk :where
                    [?e :enfermaria-bed/save-key ?sk]]
                  db sk)]
    (some (fn [[eid]]
            (let [e (d/pull db '[:enfermaria-bed/pokemon-edn] eid)
                  poke (read-bed-pokemon (:enfermaria-bed/pokemon-edn e))
                  id (or (:id-pokemon poke) (get poke "id-pokemon"))]
              (= (str id) (str id-pokemon))))
          beds)))

(defn professor-send! [conn ctx idx-or-idxs now]
  (let [idxs (normalize-send-indexes idx-or-idxs)
        payload {:action :professor-send :indexes idxs}
        sk (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
    (when (or (nil? idxs) (empty? idxs))
      (throw (err/error :invalid_collection_index {:index idx-or-idxs})))
    (when (> (count idxs) 50)
      (throw (err/error :invalid_collection_index {:index idx-or-idxs :hint "máx 50"})))
    (transact! conn ctx payload
      (fn [db s]
        (when (seq (pending s)) (throw (err/error :professor_busy {})))
        (when (:player/active-battle-id (player db (:chat-id ctx) (:player-id ctx)))
          (throw (err/error :battle_busy {})))
        (let [entries
              (mapv (fn [idx]
                      (let [p (pokemon-at db sk idx)]
                        (when-not p
                          (throw (err/error :invalid_collection_index {:index idx})))
                        (when (in-enfermaria? db sk (:pokemon/id-pokemon p))
                          (throw (err/error :pokemon_in_treatment
                                            {:id-pokemon (:pokemon/id-pokemon p)})))
                        (when (or (:pokemon/favorite? p)
                                  (= (:save/favorito-id-pokemon s) (:pokemon/id-pokemon p)))
                          (throw (err/error :pokemon_favorite {})))
                        (entry-from-pokemon p idx)))
                    idxs)
              token (str (+ 100 (mod (Math/abs (hash (:operation-id ctx))) 900)))
              first-e (first entries)
              pend (cond-> {:token token
                            :expires-at (+ (long now) 300000)
                            :save-id (:save-id ctx)
                            :entries entries
                            :count (count entries)
                            ;; Campos do 1º mantêm compat com formatProfessor antigo.
                            :species (:species first-e)
                            :nome (:nome first-e)
                            :nivel (:nivel first-e)
                            :shiny (:shiny first-e)
                            :item (:item first-e)
                            :id-pokemon (:id-pokemon first-e)
                            :index (:index first-e)
                            :display-index (:display-index first-e)
                            :family (:family first-e)}
                     (> (count entries) 1) (assoc :batch true))]
          {:summary pend
           :tx [[:db/add [:save/key sk] :save/professor-pending-edn (pr-str pend)]]})))))

(defn professor-cancel! [conn ctx]
  (transact! conn ctx {:action :professor-cancel}
             (fn [_ _] {:summary {:cancelled true :pending nil}
                         :tx [[:db/retract [:save/key (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
                                               :save/professor-pending-edn]]})))

(defn- pending-entries
  "Normaliza pendente legado (1 poke) ou lote (:entries)."
  [pend]
  (if (seq (:entries pend))
    (vec (:entries pend))
    [(select-keys pend [:id-pokemon :index :display-index :species :nome
                        :nivel :shiny :item :family])]))

(defn professor-confirm! [conn ctx token now]
  (let [payload {:action :professor-confirm :token token}
        sk (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
    (transact! conn ctx payload
      (fn [db s]
        (let [pend (pending s)]
          (when-not (seq pend) (throw (err/error :professor_invalid_token {})))
          (when-not (= (str token) (str (:token pend)))
            (throw (err/error :professor_invalid_token {})))
          (when (>= (long now) (long (:expires-at pend 0)))
            (throw (err/error :professor_expired {})))
          ;; Save ativo diferente do pedido → fence/CAS já cobre; reforço explícito.
          (when (and (:save-id pend) (not= (:save-id pend) (:save-id ctx)))
            (throw (err/error :stale_writer_fence {:reason "save-switched"})))
          (let [entries (pending-entries pend)
                ids (set (map :id-pokemon entries))
                idxs (mapv long (map :index entries))
                col (collection db sk)
                by-id (into {} (map (fn [p] [(:pokemon/id-pokemon p) p]) col))
                entities (mapv (fn [e]
                                 (let [p (get by-id (:id-pokemon e))]
                                   (when-not p
                                     (throw (err/error :pokemon_not_found
                                                       {:id-pokemon (:id-pokemon e)})))
                                   (let [save-ref (:db/id (:pokemon/save p))
                                         save-eid (:db/id s)]
                                     (when-not (= save-ref save-eid)
                                       (throw (err/error :pokemon_not_found
                                                         {:reason "wrong-save"}))))
                                   (when (in-enfermaria? db sk (:pokemon/id-pokemon p))
                                     (throw (err/error :pokemon_in_treatment
                                                       {:id-pokemon (:pokemon/id-pokemon p)})))
                                   (when (or (:pokemon/favorite? p)
                                             (= (:save/favorito-id-pokemon s)
                                                (:pokemon/id-pokemon p)))
                                     (throw (err/error :pokemon_favorite {})))
                                   p))
                               entries)
                rest-pokemon (vec (remove #(contains? ids (:pokemon/id-pokemon %)) col))
                reindex-tx (map-indexed
                            (fn [i q]
                              [:db/add [:pokemon/id-pokemon (:pokemon/id-pokemon q)]
                               :pokemon/collection-index (long i)])
                            (sort-by :pokemon/collection-index rest-pokemon))
                retract-tx (mapv #(vector :db/retractEntity (:db/id %)) entities)
                item-counts (frequencies (keep :pokemon/item entities))
                item-tx (mapv (fn [[item n]]
                                (let [ik (id/inventory-item-key sk item)
                                      old (long (:inventory-item/qty
                                                 (d/pull db '[:inventory-item/qty]
                                                         [:inventory-item/key ik]) 0))]
                                  {:inventory-item/key ik
                                   :inventory-item/save [:save/key sk]
                                   :inventory-item/item-id item
                                   :inventory-item/qty (+ old (long n))}))
                              item-counts)
                cards0 (read-edn (:save/professor-cards-edn s))
                cards' (reduce (fn [m e]
                                 (update m (:family e) (fnil inc 0)))
                               cards0 entries)
                n (count entries)
                next-active (next-active-after-removals
                             (long (:save/ativo-index s 0)) idxs)
                team-tx (team-index-tx sk s idxs)
                first-e (first entries)
                base-tx [[:db/retract [:save/key sk] :save/professor-pending-edn]
                         [:db/add [:save/key sk] :save/professor-cards-edn (pr-str cards')]
                         [:db/add [:save/key sk] :save/doacoes-pokemon
                          (+ (long (:save/doacoes-pokemon s 0)) n)]
                         [:db/add [:save/key sk] :save/ativo-index next-active]]
                summary (cond-> {:confirmed true
                                 :count n
                                 :cards-granted n
                                 :items-returned (long (reduce + 0 (vals item-counts)))
                                 :family (:family first-e)
                                 :id-pokemon (:id-pokemon first-e)
                                 :nome (:nome first-e)
                                 :collection-count (count rest-pokemon)}
                          (= n 1) (assoc :item-returned (:item first-e))
                          (> n 1) (assoc :entries entries))]
            {:summary summary
             :tx (vec (concat retract-tx base-tx reindex-tx item-tx team-tx))}))))))

(defn professor-use! [conn ctx idx]
  (let [payload {:action :professor-use :index idx} sk (save-key (:chat-id ctx) (:player-id ctx) (:save-id ctx))]
    (when (nil? idx)
      (throw (err/error :invalid_collection_index {:index idx})))
    (transact! conn ctx payload
      (fn [db s]
        (let [p (pokemon-at db sk idx)]
          (when-not p (throw (err/error :invalid_collection_index {:index idx})))
          (let [fam (profile/family (:pokemon/species p)) cards (read-edn (:save/professor-cards-edn s))]
            (when-not (pos? (long (get cards fam 0))) (throw (err/error :professor_no_cards {})))
            (when (>= (long (:pokemon/level p 1)) xp/nivel-maximo)
              (throw (err/error :pokemon_max_level {})))
            (let [nivel0 (long (:pokemon/level p 1))
                  reg (cond-> {:nivel nivel0
                               :xp-desde-nivel (long (:pokemon/xp-desde-nivel p 0))
                               :hp (long (:pokemon/hp p 1))
                               :hp-atual (long (:pokemon/hp-atual p (:pokemon/hp p 1)))
                               :ataque (long (:pokemon/ataque p 0))
                               :defesa (long (:pokemon/defesa p 0))
                               :atq-esp (long (:pokemon/atq-esp p 0))
                               :def-esp (long (:pokemon/def-esp p 0))
                               :veloc (long (:pokemon/veloc p 0))
                               :amizade (long (:pokemon/amizade p 70))
                               :species (:pokemon/species p)
                               :nome (or (:pokemon/nickname p) (:pokemon/nome p) (:pokemon/species p))}
                        (some? (:pokemon/xp-total p))
                        (assoc :xp-total (long (:pokemon/xp-total p)))
                        (some? (:pokemon/xp-progress-version p))
                        (assoc :xp-progress-version (:pokemon/xp-progress-version p)))
                  applied (xp/aplicar-xp-atividade reg 3 {:atividade :captura})
                  n (:registro applied)
                  nome (:nome applied)
                  lookup [:pokemon/id-pokemon (:pokemon/id-pokemon p)]]
            {:summary {:used true :family fam :id-pokemon (:pokemon/id-pokemon p)
                       :nome nome :level (:nivel-final applied)
                       :leveled-up? (> (long (:nivel-final applied)) nivel0)}
             :tx (cond-> [[:db/add [:save/key sk] :save/professor-cards-edn
                           (pr-str (update cards fam dec))]
                          [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel n))]
                          [:db/add lookup :pokemon/level (long (:nivel n))]
                          [:db/add lookup :pokemon/hp (long (:hp n))]
                          [:db/add lookup :pokemon/hp-atual (long (:hp-atual n))]
                          [:db/add lookup :pokemon/amizade (long (:amizade n))]]
                   (some? (:xp-total n))
                   (conj [:db/add lookup :pokemon/xp-total (long (:xp-total n))])
                   (some? (:xp-progress-version n))
                   (conj [:db/add lookup :pokemon/xp-progress-version
                          (str (:xp-progress-version n))])
                   (some? (:growth-rate n))
                   (conj [:db/add lookup :pokemon/growth-rate (name (:growth-rate n))])
                   (some? (:xp-legacy-residual n))
                   (conj [:db/add lookup :pokemon/xp-legacy-residual
                          (long (:xp-legacy-residual n))]))})))))))
