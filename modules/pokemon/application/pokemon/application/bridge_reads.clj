(ns pokemon.application.bridge-reads
  "Leituras expostas pela bridge HTTP M4.1 (somente consulta).
  Sem escritas; sem operações administrativas."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]))

(defn- require-player!
  [store chat-id player-id]
  (or (ports/get-player store chat-id player-id)
      (throw (err/error :player_not_found
                        {:chat-id chat-id :player-id player-id}))))

(defn- resolve-save-id!
  [store chat-id player-id save-id]
  (let [slot (or save-id (ports/active-save-id store chat-id player-id))]
    (when-not slot
      (throw (err/error :no_active_save
                        {:chat-id chat-id :player-id player-id})))
    (when-not (ports/get-save store chat-id player-id slot)
      (throw (err/error :save_not_found
                        {:chat-id chat-id :player-id player-id :save-id slot})))
    slot))

(defn trainer
  "Conta do treinador (sem inventário completo)."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)]
    {:chat-id (:chat-id player)
     :player-id (:player-id player)
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player)
     :active-battle-id (:active-battle-id player)
     :gym-defense-count (:gym-defense-count player 0)}))

(defn collection
  "Coleção de um save (ativo se save-id omitido)."
  [store chat-id player-id save-id]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        items (ports/list-collection store chat-id player-id slot)]
    {:chat-id chat-id
     :player-id player-id
     :save-id slot
     :count (count items)
     :collection (mapv (fn [p]
                         (select-keys p [:collection-index :id-pokemon :species :nome
                                         :nickname :shiny :nivel :hp :hp-atual
                                         :status :in-party :tipos :raridade]))
                       items)}))

(defn active-save
  "Save ativo + party + inventário (snapshot de leitura)."
  [store chat-id player-id]
  (let [snap (trainer/active-save store chat-id player-id)
        save (:save snap)
        player (:player snap)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player)
     :save (select-keys save [:slot-id :coins :revision :trainer-xp :trainer-level
                              :xp-atual :xp-necessario :ativo-index
                              :vitorias-treinador :pe-ginasios :pe-raids])
     :party (mapv #(select-keys % [:collection-index :id-pokemon :species :nome
                                   :shiny :nivel :hp :hp-atual :in-party])
                  (:party snap))
     :inventory (:inventory snap)}))
