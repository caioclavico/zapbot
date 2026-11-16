(ns pokemon.infrastructure.datomic.shop-store
  "TX builders de compra e uso de itens."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]
            [pokemon.infrastructure.datomic.economy-store :as economy-store]))

(defn build-buy-tx
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        save-eco (economy-store/entity->economy-save save)
        spendable (economy-store/spendable-from-entity save)
        save-m (merge save-eco
                      {:trainer-level (long (:save/trainer-level save 1))
                       :trainer-xp (long (:save/trainer-xp save 0))
                       :expansoes-mochila-50 (long (:save/expansoes-mochila-50 save 0))
                       :expansoes-pokemon-50 (long (:save/expansoes-pokemon-50 save 0))
                       :capacidade-mochila-legada (long (:save/capacidade-mochila-legada save 50))
                       :capacidade-pokemon-legada (long (:save/capacidade-pokemon-legada save 0))})
        item-id (shop/normalizar-item (:item-id request))
        qty (long (or (:qty request) 1))
        _ (when-not (= 1 qty)
            (throw (err/error :invalid_buy_qty {:qty qty})))
        meta (shop/validar-compra! item-id save-m inventory spendable)
        preco (long (:preco meta))
        _ (economy-store/assert-debit! save-eco preco)
        coin-part (economy-store/apply-delta-tx
                   {:sk sk :save save :delta (- preco)
                    :operation-id (:operation-id request)
                    :source "shop" :role "primary"})
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        expansao? (boolean (:expansao? meta))
        incubadora? (boolean (:incubadora? meta))
        expansao-tipo (:expansao-tipo meta)
        item-tx (when-not (or expansao? incubadora?)
                  (inventory-adjust-tx db sk item-id 1))
        exp-mochila (long (:save/expansoes-mochila-50 save 0))
        exp-pokemon (long (:save/expansoes-pokemon-50 save 0))
        next-coins (:operational-after coin-part)
        ;; Incubadora: concede na conta Adventure (não vai à mochila).
        incubator-kind (:incubadora-kind meta)
        incubator-id (str "shop:" operation-id)
        ak (id/adventure-account-key chat-id player-id)
        acc-e (d/pull db adventure-store/account-pattern [:adventure-account/key ak])
        acc-exists? (some? (:db/id acc-e))
        shell0 (if acc-exists?
                 (adventure-store/account-shell acc-e edn/read-string #(some? (:db/id %)))
                 (eggs/estado-inicial))
        grant (when incubadora?
                (egg-activity/try-grant-incubator shell0 incubator-id incubator-kind))
        shell1 (when grant (:estado grant))
        incubator-tx
        (when incubadora?
          (let [account-ref (if acc-exists?
                              [:adventure-account/key ak]
                              (str "new-adventure-account:" ak))
                rev (long (:revision shell0 0))]
            (cond-> (if acc-exists?
                      [[:db/add account-ref :adventure-account/state-edn
                        (adventure-store/shell-edn shell1)]
                       [:db/cas account-ref :adventure-account/revision rev (inc rev)]]
                      [{:db/id account-ref
                        :adventure-account/key ak
                        :adventure-account/chat-id chat-id
                        :adventure-account/player-id player-id
                        :adventure-account/revision 1
                        :adventure-account/state-edn (adventure-store/shell-edn shell1)}])
              true (into (adventure-store/receipt-txs account-ref shell0 shell1)))))]
    {:tx (cond-> (into [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "shop-buy"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key (id/player-key chat-id player-id)
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary
                         (pr-str {:item-id item-id :preco preco :expansao? expansao?
                                  :expansao-tipo expansao-tipo
                                  :incubadora? incubadora?
                                  :incubadora-kind incubator-kind
                                  :incubator-deferred?
                                  (boolean (:deferred-incubator grant))})}]
                       (:tx coin-part))
           (and expansao? (= expansao-tipo :mochila))
           (conj [:db/add [:save/key sk] :save/expansoes-mochila-50 (inc exp-mochila)])
           (and expansao? (= expansao-tipo :pokemon))
           (conj [:db/add [:save/key sk] :save/expansoes-pokemon-50 (inc exp-pokemon)])
           (seq item-tx)
           (into item-tx)
           (seq incubator-tx)
           (into incubator-tx))
     :result {:operation-id operation-id
              :duplicate false
              :item-id item-id
              :nome (:nome meta)
              :emoji (:emoji meta)
              :preco preco
              :coins-depois next-coins
              :expansao? expansao?
              :expansao-tipo expansao-tipo
              :incubadora? incubadora?
              :incubadora-kind incubator-kind
              :incubator-grant (:incubator-grant grant)
              :deferred-incubator (:deferred-incubator grant)
              :expansoes-mochila-50 (if (and expansao? (= expansao-tipo :mochila))
                                      (inc exp-mochila) exp-mochila)
              :expansoes-pokemon-50 (if (and expansao? (= expansao-tipo :pokemon))
                                      (inc exp-pokemon) exp-pokemon)}}))

(defn build-use-tx
  "Usa cura de status ou poção no índice da coleção."
  [{:keys [db writer-ctx request save colecao inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        item-id (shop/normalizar-item (:item-id request))
        meta (or (shop/item item-id)
                 (throw (err/error :unknown_shop_item {:item-id item-id})))
        idx (long (or (:idx request)
                      (:save/ativo-index save 0)))
        _ (when-not (and (integer? idx) (<= 0 idx (dec (count colecao))))
            (throw (err/error :invalid_collection_index {:idx idx})))
        poke (nth colecao idx)
        status-atual (or (:status poke) (get poke "status"))
        status-str (some-> status-atual str)
        cura-status (:status meta)
        fracao (:cura-hp meta)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        rev (long (:save/revision save 0))
        lookup [:pokemon/id-pokemon (:id-pokemon poke)]]
    (cond
      cura-status
      (do
        (when-not (= (str cura-status) (some-> status-str str))
          (throw (err/error :wrong_status_item
                            {:item-id item-id
                             :precisa cura-status
                             :atual status-str})))
        (let [item-tx (inventory-adjust-tx db sk item-id -1)]
          {:tx (concat item-tx
                       [[:db/retract lookup :pokemon/status status-str]
                        [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "shop-use"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key (id/player-key chat-id player-id)
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary
                         (pr-str {:item-id item-id :idx idx :kind "status"})}])
           :result {:operation-id operation-id
                    :duplicate false
                    :kind "status"
                    :item-id item-id
                    :idx idx
                    :status-antes status-str
                    :pokemon (select-keys poke [:nome :species :hp :hp-atual])}}))

      fracao
      (let [heal (shop/aplicar-pocao poke fracao)
            item-tx (inventory-adjust-tx db sk item-id -1)]
        {:tx (concat item-tx
                     [[:db/add lookup :pokemon/hp-atual (long (:hp-depois heal))]
                      [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                      {:operation-receipt/id operation-id
                       :operation-receipt/kind "shop-use"
                       :operation-receipt/channel "gameplay"
                       :operation-receipt/payload-hash phash
                       :operation-receipt/player-key (id/player-key chat-id player-id)
                       :operation-receipt/save-id save-id
                       :operation-receipt/summary
                       (pr-str {:item-id item-id :idx idx :kind "pocao"
                                :hp (:hp-depois heal)})}])
         :result {:operation-id operation-id
                  :duplicate false
                  :kind "pocao"
                  :item-id item-id
                  :idx idx
                  :hp-antes (:hp-antes heal)
                  :hp-depois (:hp-depois heal)
                  :hp-max (:hp-max heal)
                  :pokemon (select-keys poke [:nome :species])}})

      (= item-id "reviver")
      (do
        (when (pos? (long (or (:hp-atual poke) 0)))
          (throw (err/error :pokemon_not_fainted {:idx idx})))
        (let [hp-max (long (or (:hp poke) 0))
              item-tx (inventory-adjust-tx db sk item-id -1)
              tx (cond-> (vec (concat item-tx
                                      [[:db/add lookup :pokemon/hp-atual hp-max]]))
                   status-str (conj [:db/retract lookup :pokemon/status status-str])
                   true (conj [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                              {:operation-receipt/id operation-id
                               :operation-receipt/kind "shop-use"
                               :operation-receipt/channel "gameplay"
                               :operation-receipt/payload-hash phash
                               :operation-receipt/player-key (id/player-key chat-id player-id)
                               :operation-receipt/save-id save-id
                               :operation-receipt/summary
                               (pr-str {:item-id item-id :idx idx :kind "reviver"})}))]
          {:tx tx
           :result {:operation-id operation-id
                    :duplicate false
                    :kind "reviver"
                    :item-id item-id
                    :idx idx
                    :pokemon (select-keys poke [:nome :species :hp :hp-atual])}}))

      (:equipavel? meta)
      (do
        (when (= item-id (or (:item poke) (get poke "item")))
          (throw (err/error :item_already_equipped {:item-id item-id :idx idx})))
        (let [item-tx (concat (inventory-adjust-tx db sk item-id -1)
                              (when-let [old-item (or (:item poke) (get poke "item"))]
                                (inventory-adjust-tx db sk old-item 1)))]
          {:tx (concat item-tx
                       (if-let [old-item (or (:item poke) (get poke "item"))]
                         [[:db/retract lookup :pokemon/item old-item]
                          [:db/add lookup :pokemon/item item-id]]
                         [[:db/add lookup :pokemon/item item-id]])
                       [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "shop-use"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key (id/player-key chat-id player-id)
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary
                         (pr-str {:item-id item-id :idx idx :kind "equip"})}])
           :result {:operation-id operation-id
                    :duplicate false
                    :kind "equip"
                    :item-id item-id
                    :idx idx
                    :pokemon (assoc (select-keys poke [:nome :species :item]) :item item-id)}}))

      :else
      (throw (err/error :item_use_unsupported
                        {:item-id item-id
                         :allowed ["status-cura" "pocao" "pocao-maxima"]})))))
