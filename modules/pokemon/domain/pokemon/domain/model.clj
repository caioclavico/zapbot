(ns pokemon.domain.model
  "Regras puras do núcleo treinador/saves (sem I/O, sem Datomic)."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]))

(def default-slots ["a" "b"])

(defn validate-create-player!
  [{:keys [chat-id player-id]}]
  (when-not (and (id/valid-identity-part? chat-id)
                 (id/valid-identity-part? player-id))
    (throw (err/error :invalid_identity {:chat-id chat-id :player-id player-id})))
  {:chat-id chat-id :player-id player-id :player-key (id/player-key chat-id player-id)})

(defn validate-save-slot!
  [save-id]
  (when-not (id/valid-slot-id? save-id)
    (throw (err/error :invalid_slot_id {:save-id save-id})))
  save-id)

(defn initial-save
  "Estado inicial de um save vazio (moedas/xp/nível)."
  [save-id]
  (validate-save-slot! save-id)
  {:slot-id save-id
   :coins 0
   :trainer-xp 0
   :trainer-level 1
   :revision 0})

(defn assert-activate
  "Decide se a alternância é no-op ou deve avançar o fence.
  Nunca muta dados do outro save — só o ponteiro ativo."
  [{:keys [active-save-id fence-generation]} target-save-id]
  (validate-save-slot! target-save-id)
  (cond
    (nil? active-save-id)
    {:action :set-initial :target target-save-id :next-fence 1}

    (= active-save-id target-save-id)
    {:action :noop :target target-save-id :fence-generation fence-generation}

    :else
    {:action :switch
     :from active-save-id
     :target target-save-id
     :expected-fence fence-generation
     :next-fence (inc (long fence-generation))}))

(defn apply-coin-delta
  [coins delta]
  (let [next (+ (long coins) (long delta))]
    (when (neg? next)
      (throw (err/error :insufficient_coins {:coins coins :delta delta})))
    next))

(defn reward-summary
  "Resumo serializável do efeito (vai no recibo; sem I/O)."
  [{:keys [coins-delta pokemon-key species shiny item-id item-qty]}]
  (cond-> {:coins-delta (or coins-delta 0)}
    pokemon-key (assoc :pokemon-key pokemon-key :species species :shiny (boolean shiny))
    item-id (assoc :item-id item-id :item-qty (or item-qty 0))))
