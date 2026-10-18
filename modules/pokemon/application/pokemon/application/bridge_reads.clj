(ns pokemon.application.bridge-reads
  "Leituras expostas pela bridge HTTP (M4.1/M4.3).
  Sem escritas; sem operações administrativas; DTOs neutros (sem WhatsApp)."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.xp :as xp]))

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

(defn- pokemon-dto
  [p]
  (select-keys p [:collection-index :id-pokemon :species :nome :nickname
                  :shiny :nivel :hp :hp-atual :status :in-party :tipos
                  :raridade :xp-desde-nivel :ataque :defesa :atq-esp :def-esp
                  :veloc :amizade]))

(defn- save-profile
  [save]
  (when save
    (let [base (select-keys save [:slot-id :coins :revision :trainer-xp :trainer-level
                                  :xp-atual :xp-necessario :ativo-index
                                  :vitorias-treinador :pe-ginasios :pe-raids
                                  :xp-insignias :xp-missoes :sequencia-capturas
                                  :maior-sequencia-capturas])
          por-componentes (xp/perfil-treinador save)
          xp-stored (long (:trainer-xp save 0))
          xp (max (long (:xp por-componentes 0)) xp-stored)
          prog (if (pos? (long (:xp por-componentes 0)))
                 por-componentes
                 (xp/progresso-treinador xp))]
      (merge base
             {:nivel (:nivel prog (:trainer-level save 1))
              :xp xp
              :xp-atual (:xp-atual prog (:xp-atual save 0))
              :xp-necessario (:xp-necessario prog (:xp-necessario save 5))}))))

(defn trainer
  "Perfil do treinador + save ativo (DTO neutro para apresentação no Node)."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        ativo-idx (long (:ativo-index save 0))
        ativo (when (and colecao (seq colecao) (< ativo-idx (count colecao)))
                (nth colecao ativo-idx))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player)
     :active-battle-id (:active-battle-id player)
     :gym-defense-count (:gym-defense-count player 0)
     :profile (save-profile save)
     :ativo-index ativo-idx
     :ativo (when ativo (pokemon-dto ativo))
     :collection-count (count colecao)}))

(defn collection
  "Coleção de um save (ativo se save-id omitido)."
  [store chat-id player-id save-id]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        save (ports/get-save store chat-id player-id slot)
        items (ports/list-collection store chat-id player-id slot)]
    {:chat-id chat-id
     :player-id player-id
     :save-id slot
     :fence-generation (:fence-generation (require-player! store chat-id player-id))
     :ativo-index (long (:ativo-index save 0))
     :trainer-level (:trainer-level save 1)
     :count (count items)
     :collection (mapv pokemon-dto items)}))

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
     :save (save-profile save)
     :party (mapv pokemon-dto (:party snap))
     :inventory (:inventory snap)}))
