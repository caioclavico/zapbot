(ns pokemon.infrastructure.datomic.onboarding-store
  "TX builders M9.6 — kit inicial e escolha do starter.
   Kit: uma vez por treinador (flags em TODOS os saves) — sem kit extra ao trocar save.
   Starter: uma vez por save (playthrough A/B independente), com coleção>0 bloqueando."
  (:require [datomic.client.api :as d]
            [pokemon.domain.bag-rewards :as bag]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.onboarding :as onboard]
            [pokemon.domain.reward :as reward]
            [pokemon.domain.shop :as shop]))

(defn save-entities-for-player
  [db chat-id player-id]
  (->> (d/q '[:find (pull ?s [*])
              :in $ ?pk
              :where
              [?p :player/key ?pk]
              [?s :save/player ?p]]
            db (id/player-key chat-id player-id))
       (map first)
       vec))

(defn kit-claimed-any?
  [db chat-id player-id]
  (boolean
   (some (fn [e] (boolean (:save/kit-inicial-resgatado e)))
         (save-entities-for-player db chat-id player-id))))

(defn kit-flag-tx
  "Marca kit-inicial-resgatado em todos os saves existentes do treinador.
   Inclui recovery-v1 e qualquer slot além de a/b."
  [db chat-id player-id]
  (let [existing (save-entities-for-player db chat-id player-id)
        keys (mapv :save/key existing)
        ;; Garante a/b mesmo se ainda não materializados (bootstrap legado).
        ensured (into (set keys)
                      (map #(id/save-key chat-id player-id %) model/default-slots))]
    (mapv (fn [sk]
            [:db/add [:save/key sk] :save/kit-inicial-resgatado true])
          ensured)))

(defn build-claim-kit-tx
  [{:keys [db writer-ctx request save inventory inventory-adjust-tx kit-claimed-any?]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        operation-id (:operation-id request)
        already? (boolean (or kit-claimed-any?
                              (:save/kit-inicial-resgatado save
                                                           (:kit-inicial-resgatado save))))
        save-m {:trainer-level (long (:save/trainer-level save (:trainer-level save 1)))
                :expansoes-mochila-50 (long (:save/expansoes-mochila-50 save
                                                                        (:expansoes-mochila-50 save 0)))
                :capacidade-mochila-legada (long (:save/capacidade-mochila-legada save
                                                                                 (:capacidade-mochila-legada save 50)))}
        payload (reward/reward-payload
                 {:item {:item-id onboard/kit-item-id :qty onboard/kit-qty}})
        phash (reward/payload-hash payload)
        rev (long (:save/revision save (:revision save 0)))]
    (cond
      already?
      {:tx []
       :result {:operation-id operation-id :duplicate true
                :kit-already-claimed true
                :item-id onboard/kit-item-id :qty 0
                :payload-hash phash}}

      (not (bag/kit-espaco-ok? save-m (or inventory [])))
      (throw (err/error :bag_full
                        {:ocupacao (shop/ocupacao-mochila (or inventory []))
                         :capacidade (shop/capacidade-mochila-save save-m)
                         :required onboard/kit-qty
                         :hint :kit_needs_free_slots}))

      :else
      (let [item-tx (inventory-adjust-tx db sk onboard/kit-item-id onboard/kit-qty)]
        {:tx (into (into (vec item-tx) (kit-flag-tx db chat-id player-id))
                   [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                    {:operation-receipt/id operation-id
                     :operation-receipt/kind "bag-kit"
                     :operation-receipt/channel "gameplay"
                     :operation-receipt/payload-hash phash
                     :operation-receipt/player-key (id/player-key chat-id player-id)
                     :operation-receipt/save-id save-id
                     :operation-receipt/summary
                     (pr-str {:item-id onboard/kit-item-id :qty onboard/kit-qty})}])
         :result {:operation-id operation-id :duplicate false
                  :kit-already-claimed false
                  :item-id onboard/kit-item-id :qty onboard/kit-qty
                  :items {onboard/kit-item-id onboard/kit-qty}
                  :added {onboard/kit-item-id onboard/kit-qty}
                  :payload-hash phash}}))))

(defn build-choose-starter-tx
  [{:keys [db writer-ctx request save colecao pokemon-entity-map inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        operation-id (:operation-id request)
        choice (:choice request)
        starter (onboard/resolve-starter choice)
        species (:species starter)
        payload (reward/reward-payload
                 {:pokemon {:key (str operation-id ":starter")
                            :species species
                            :nickname (:nome starter)
                            :shiny false
                            :xp 0
                            :level 1
                            :in-party true}})
        phash (reward/payload-hash payload)
        already-flag? (boolean (:save/inicial-escolhido save
                                                       (:inicial-escolhido save)))
        kit-done? (boolean (or (:kit-claimed-any? request)
                               (:save/kit-inicial-resgatado save
                                                            (:kit-inicial-resgatado save))))
        coll-count (count colecao)
        disponivel? (onboard/starter-disponivel?
                     {:inicial-escolhido already-flag?
                      :collection-count coll-count})]
    (if-not disponivel?
      (let [atual (first colecao)]
        {:tx []
         :result {:operation-id operation-id
                  :duplicate true
                  :starter-already-chosen true
                  :species (or (:species atual) (:nome atual))
                  :nome (or (:nome atual) (:species atual))
                  :payload-hash phash}})
      (let [save-m {:expansoes-pokemon-50 (long (:save/expansoes-pokemon-50 save
                                                                            (:expansoes-pokemon-50 save 0)))
                    :capacidade-pokemon-legada (long (:save/capacidade-pokemon-legada save
                                                                                     (:capacidade-pokemon-legada save 0)))
                    :trainer-level (long (:save/trainer-level save (:trainer-level save 1)))}
            _ (when-not (capacity/cabe-pokemon? coll-count save-m)
                (throw (err/error :collection_full {:count coll-count})))
            reg (onboard/starter->registro starter)
            rev (long (:save/revision save (:revision save 0)))
            idx coll-count
            entity (pokemon-entity-map sk reg idx)
            kit-tx (when-not kit-done?
                     (inventory-adjust-tx db sk onboard/kit-item-id onboard/kit-qty))
            base-tx [entity
                     {:pokedex-entry/key (id/pokedex-entry-key sk species)
                      :pokedex-entry/save [:save/key sk]
                      :pokedex-entry/species species
                      :pokedex-entry/seen true
                      :pokedex-entry/caught true}
                     [:db/add [:save/key sk] :save/inicial-escolhido true]
                     [:db/add [:save/key sk] :save/ativo-index 0]
                     [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                     {:operation-receipt/id operation-id
                      :operation-receipt/kind "onboard-starter"
                      :operation-receipt/channel "gameplay"
                      :operation-receipt/payload-hash phash
                      :operation-receipt/player-key (id/player-key chat-id player-id)
                      :operation-receipt/save-id save-id
                      :operation-receipt/summary
                      (pr-str {:species species :id-pokemon (:id-pokemon reg)})}]
            tx (cond-> (into (vec (or kit-tx [])) base-tx)
                 (not kit-done?)
                 (into (kit-flag-tx db chat-id player-id)))]
        {:tx tx
         :result {:operation-id operation-id
                  :duplicate false
                  :species species
                  :nome (:nome starter)
                  :emoji (:emoji starter)
                  :registro (assoc reg :collection-index idx)
                  :imagem (:imagem starter)
                  :payload-hash phash
                  :kit-granted-with-starter (not kit-done?)
                  :kit-item-id (when-not kit-done? onboard/kit-item-id)
                  :kit-qty (when-not kit-done? onboard/kit-qty)}}))))
