(ns pokemon.application.shop
  "Loja / inventário / uso de itens suportados."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.shop :as shop]))

(defn catalog
  []
  (shop/catalogo-loja))

(defn list-inventory
  [store chat-id player-id save-id]
  (ports/list-inventory store chat-id player-id save-id))

(defn buy!
  ([store chat-id player-id request]
   (buy! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/buy-item! store writer-ctx full))))

(defn use-item!
  ([store chat-id player-id request]
   (use-item! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/use-item! store writer-ctx full))))
