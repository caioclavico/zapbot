(ns pokemon.application.bridge-tx
  "Casos de uso M4.2 da bridge: ensure, saves, activate, mutação pequena.
  Sem seed admin; save-id + fence + operation-id explícitos nas escritas."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.model :as model]))

(defn- save-summary
  [store chat-id player-id slot]
  (when-let [save (ports/get-save store chat-id player-id slot)]
    {:save-id slot
     :coins (:coins save 0)
     :revision (:revision save 0)
     :trainer-xp (:trainer-xp save 0)
     :trainer-level (:trainer-level save 1)
     :collection-count (count (ports/list-collection store chat-id player-id slot))
     :party-count (count (ports/list-party store chat-id player-id slot))
     :inventory (ports/list-inventory store chat-id player-id slot)}))

(defn ensure-trainer!
  "Cria treinador+saves A/B se ainda não existir (idempotente)."
  [store {:keys [chat-id player-id]}]
  (model/validate-create-player! {:chat-id chat-id :player-id player-id})
  (let [existing (ports/get-player store chat-id player-id)
        player (if existing
                 existing
                 (trainer/create-trainer! store {:chat-id chat-id :player-id player-id}))]
    {:created (nil? existing)
     :chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)}))

(defn list-saves
  [store chat-id player-id]
  (let [player (or (ports/get-player store chat-id player-id)
                   (throw (err/error :player_not_found
                                     {:chat-id chat-id :player-id player-id})))
        slots (vec model/default-slots)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)
     :saves (vec (keep #(save-summary store chat-id player-id %) slots))}))

(defn activate-save!
  "Ativa save via TX Datomic (fence sobe). Opcionalmente exige fence esperado."
  [store {:keys [chat-id player-id save-id expected-fence-generation]}]
  (model/validate-save-slot! save-id)
  (let [player (or (ports/get-player store chat-id player-id)
                   (throw (err/error :player_not_found
                                     {:chat-id chat-id :player-id player-id})))
        current-fence (:fence-generation player 0)]
    (when (and (some? expected-fence-generation)
               (not= (long expected-fence-generation) (long current-fence)))
      (throw (err/error :stale_writer_fence
                        {:expected expected-fence-generation
                         :fence current-fence
                         :active (:active-save-id player)})))
    (let [snap (trainer/switch-save! store chat-id player-id save-id)
          p (:player snap)]
      {:chat-id chat-id
       :player-id player-id
       :active-save-id (:active-save-id p)
       :fence-generation (:fence-generation p)
       :save (select-keys (:save snap)
                          [:slot-id :coins :revision :trainer-xp :trainer-level])})))

(defn credit-coins!
  "Mutação representativa: credita moedas no save congelado (fence + recibo).

  Exige save-id, fence-generation e operation-id do cliente — não recongela
  o ativo automaticamente a partir do pedido."
  [store {:keys [chat-id player-id save-id fence-generation operation-id coins-delta]
          :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (when-not (integer? coins-delta)
    (throw (err/error :invalid_coins_delta {:coins-delta coins-delta})))
  (model/validate-save-slot! save-id)
  (let [writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        result (reward-app/apply-gameplay-reward!
                store writer-ctx
                {:operation-id (str operation-id)
                 :coins-delta (long coins-delta)})
        save (ports/get-save store chat-id player-id save-id)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :channel (:channel result)
     :payload-hash (:payload-hash result)
     :save-id save-id
     :fence-generation (long fence-generation)
     :coins (:coins save 0)
     :summary (:summary result)}))

(defn get-operation
  "Reconciliação pós-timeout: recibo ou nil."
  [store operation-id]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (or (ports/get-receipt store (str operation-id))
      (throw (err/error :operation_not_found {:operation-id operation-id}))))
