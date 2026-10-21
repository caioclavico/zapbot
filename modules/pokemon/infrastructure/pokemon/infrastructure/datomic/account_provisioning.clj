(ns pokemon.infrastructure.datomic.account-provisioning
  "Inicialização idempotente sem sobrescrever economia durante upserts concorrentes."
  (:require [datomic.client.api :as d]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]))

(defn- initialize-missing! [conn lookup defaults]
  (loop [attempt 0]
    (let [entity (d/pull (d/db conn) '[*] lookup)
          tx (vec (for [[attr value] defaults :when (not (contains? entity attr))]
                    [:db/cas lookup attr nil value]))]
      (if (empty? tx) entity
        (let [result (try (d/transact conn {:tx-data tx}) :ok
                          (catch Exception ex
                            (if (err/conflict? ex) :conflict (throw ex))))]
          (cond (= :ok result) (d/pull (d/db conn) '[*] lookup)
                (< attempt 16) (recur (inc attempt))
                :else (throw (err/error :entity_initialization_conflict {:entity lookup}))))))))

(defn ensure-player! [conn chat player]
  (let [key (id/player-key chat player) lookup [:player/key key]]
    (when-not (:db/id (d/pull (d/db conn) '[:db/id] lookup))
      ;; Apenas identidade imutável: um upsert tardio nunca zera o fence/saldo.
      (d/transact conn {:tx-data [{:player/key key :player/chat-id chat :player/player-id player}]}))
    (initialize-missing! conn lookup {:player/fence-generation 0 :player/gym-defense-count 0})))

(defn ensure-save! [conn chat player slot]
  (let [key (id/save-key chat player slot) lookup [:save/key key]
        initial (model/initial-save slot)]
    (when-not (:db/id (d/pull (d/db conn) '[:db/id] lookup))
      (d/transact conn {:tx-data [{:save/key key :save/slot-id slot
                                  :save/player [:player/key (id/player-key chat player)]}]}))
    (initialize-missing!
     conn lookup {:save/coins (:coins initial) :save/trainer-xp (:trainer-xp initial)
                  :save/trainer-level (:trainer-level initial) :save/revision (:revision initial)
                  :save/ativo-index 0 :save/vitorias-treinador 0 :save/pe-ginasios 0
                  :save/pe-raids 0 :save/xp-insignias 0 :save/xp-missoes 0})))

(defn ensure-recipient! [conn chat player]
  (let [p (ensure-player! conn chat player)
        lookup [:player/key (id/player-key chat player)]]
    (doseq [slot (if-let [active (:player/active-save-id p)] [active] model/default-slots)]
      (ensure-save! conn chat player slot))
    (loop [attempt 0]
      (let [p (d/pull (d/db conn) '[*] lookup)]
        (if (:player/active-save-id p) p
          (let [result (try
                         (d/transact conn {:tx-data [[:db/cas lookup :player/active-save-id nil "a"]
                                                     [:db/cas lookup :player/fence-generation
                                                      (:player/fence-generation p)
                                                      (inc (:player/fence-generation p))]]})
                         :ok
                         (catch Exception ex (if (err/conflict? ex) :conflict (throw ex))))]
            (cond (= :ok result) (d/pull (d/db conn) '[*] lookup)
                  (< attempt 16) (recur (inc attempt))
                  :else (throw (err/error :entity_initialization_conflict {:entity lookup})))))))))
