(ns pokemon.application.trainer
  "Casos de uso do núcleo treinador/saves.
  Orquestra ports; regras puras em pokemon.domain.*"
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]))

(defn bootstrap!
  [store]
  (ports/ensure-schema! store)
  store)

(defn create-trainer!
  "Cria jogador + saves A e B; ativa A por padrão."
  [store {:keys [chat-id player-id]}]
  (model/validate-create-player! {:chat-id chat-id :player-id player-id})
  (ports/create-player! store chat-id player-id)
  (doseq [slot model/default-slots]
    (when-not (ports/get-save store chat-id player-id slot)
      (ports/create-save! store chat-id player-id slot)))
  (when-not (ports/active-save-id store chat-id player-id)
    (ports/activate-save! store chat-id player-id "a"))
  (ports/get-player store chat-id player-id))

(defn create-save!
  [store chat-id player-id save-id]
  (model/validate-create-player! {:chat-id chat-id :player-id player-id})
  (model/validate-save-slot! save-id)
  (when (ports/get-save store chat-id player-id save-id)
    (throw (err/error :save_already_exists {:save-id save-id})))
  (ports/create-save! store chat-id player-id save-id)
  (ports/get-save store chat-id player-id save-id))

(defn active-save
  "Retorna {:player … :save …} do save ativo."
  [store chat-id player-id]
  (let [player (ports/get-player store chat-id player-id)
        slot (ports/active-save-id store chat-id player-id)]
    (when-not player
      (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
    (when-not slot
      (throw (err/error :no_active_save {:chat-id chat-id :player-id player-id})))
    {:player player
     :save (ports/get-save store chat-id player-id slot)
     :party (ports/list-party store chat-id player-id slot)
     :inventory (ports/list-inventory store chat-id player-id slot)}))

(defn switch-save!
  "Alternância atômica A↔B (ou outro slot). Só move o ponteiro + fence."
  [store chat-id player-id target-save-id]
  (when-not (ports/get-save store chat-id player-id target-save-id)
    (throw (err/error :save_not_found {:save-id target-save-id})))
  (ports/activate-save! store chat-id player-id target-save-id)
  (active-save store chat-id player-id))

(defn grant-reward!
  "Removido o bypass silencioso (M3.2).
  Use `pokemon.application.reward/apply-gameplay-reward!` (comandos)
  ou `admin-seed-reward!` (harness/migração)."
  [& _args]
  (throw (err/error :use_explicit_reward_api
                    {:gameplay 'pokemon.application.reward/apply-gameplay-reward!
                     :admin 'pokemon.application.reward/admin-seed-reward!})))

(defn admin-seed-reward!
  "Delegação explícita ao caminho admin (sem fence)."
  [store request]
  (reward-app/admin-seed-reward! store request))

(defn snapshot-save
  [store chat-id player-id save-id]
  {:save (ports/get-save store chat-id player-id save-id)
   :party (ports/list-party store chat-id player-id save-id)
   :inventory (ports/list-inventory store chat-id player-id save-id)
   :player-key (id/player-key chat-id player-id)
   :save-key (id/save-key chat-id player-id save-id)})
