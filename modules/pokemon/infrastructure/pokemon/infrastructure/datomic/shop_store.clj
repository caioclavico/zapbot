(ns pokemon.infrastructure.datomic.shop-store
  "TX builders de compra e uso de itens."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.shop :as shop]))

(defn build-buy-tx
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        coins (long (:save/coins save 0))
        save-m {:trainer-level (long (:save/trainer-level save 1))
                :expansoes-mochila-50 (long (:save/expansoes-mochila-50 save 0))
                :capacidade-mochila-legada (long (:save/capacidade-mochila-legada save 50))}
        item-id (shop/normalizar-item (:item-id request))
        qty (long (or (:qty request) 1))
        _ (when-not (= 1 qty)
            (throw (err/error :invalid_buy_qty {:qty qty})))
        meta (shop/validar-compra! item-id save-m inventory coins)
        preco (long (:preco meta))
        next-coins (model/apply-coin-delta coins (- preco))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        expansao? (boolean (:expansao? meta))
        item-tx (when-not expansao?
                  (inventory-adjust-tx db sk item-id 1))
        exp-atual (long (:save/expansoes-mochila-50 save 0))]
    {:tx (cond-> [[:db/cas [:save/key sk] :save/coins coins next-coins]
                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                  {:operation-receipt/id operation-id
                   :operation-receipt/kind "shop-buy"
                   :operation-receipt/channel "gameplay"
                   :operation-receipt/payload-hash phash
                   :operation-receipt/player-key (id/player-key chat-id player-id)
                   :operation-receipt/save-id save-id
                   :operation-receipt/summary
                   (pr-str {:item-id item-id :preco preco :expansao? expansao?})}]
           expansao?
           (conj [:db/add [:save/key sk] :save/expansoes-mochila-50 (inc exp-atual)])
           (seq item-tx)
           (into item-tx))
     :result {:operation-id operation-id
              :duplicate false
              :item-id item-id
              :nome (:nome meta)
              :emoji (:emoji meta)
              :preco preco
              :coins-depois next-coins
              :expansao? expansao?
              :expansoes-mochila-50 (if expansao? (inc exp-atual) exp-atual)}}))

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

      :else
      (throw (err/error :item_use_unsupported
                        {:item-id item-id
                         :allowed ["status-cura" "pocao" "pocao-maxima"]})))))
