(ns pokemon.application.collection
  "Casos de uso M3.1: consulta/coleção, HP/status, XP, moedas/inventário
  no save ativo — com writer fence (save-id + fence-generation)."
  (:require [pokemon.application.ports :as ports]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.xp :as xp]))

(defn freeze-writer
  "Congela identidade do save ativo no início da operação.
  Mutações posteriores CAS-am esses valores; após A→B, escritas de A falham."
  [store chat-id player-id]
  (let [player (ports/get-player store chat-id player-id)]
    (when-not player
      (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
    (when-not (:active-save-id player)
      (throw (err/error :no_active_save {:chat-id chat-id :player-id player-id})))
    {:chat-id chat-id
     :player-id player-id
     :save-id (:active-save-id player)
     :fence-generation (:fence-generation player)}))

(defn consultar-treinador
  "Perfil do treinador + coleção + economia do save ativo."
  [store chat-id player-id]
  (let [w (freeze-writer store chat-id player-id)
        save (ports/get-save store chat-id player-id (:save-id w))
        colecao (ports/list-collection store chat-id player-id (:save-id w))
        inventário (ports/list-inventory store chat-id player-id (:save-id w))
        perfil (xp/perfil-treinador save)]
    {:writer w
     :player (ports/get-player store chat-id player-id)
     :save save
     :perfil perfil
     :colecao colecao
     :inventario inventário
     :ativo-index (:ativo-index save 0)}))

(defn adicionar-pokemon!
  "Espelha treinador/adicionar-pokemon! no save ativo."
  [store chat-id player-id pokemon hp-atual status]
  (let [w (freeze-writer store chat-id player-id)
        registro (coll/pokemon->registro pokemon hp-atual status)]
    (ports/add-to-collection! store w registro)))

(defn atualizar-hp-status!
  "Espelha atualizar-no-indice! no save ativo."
  [store chat-id player-id idx hp-atual status]
  (let [w (freeze-writer store chat-id player-id)]
    (ports/update-hp-status! store w idx hp-atual status)))

(defn ganhar-xp-pokemon!
  "Espelha ganhar-xp-no-indice!."
  [store chat-id player-id idx quantidade]
  (let [w (freeze-writer store chat-id player-id)]
    (ports/grant-pokemon-xp! store w idx quantidade)))

(defn registrar-vitoria-treinador!
  "Espelha registrar-vitoria-treinador! (só o contador de vitórias neste recorte)."
  [store chat-id player-id]
  (let [w (freeze-writer store chat-id player-id)
        antes (xp/perfil-treinador (ports/get-save store chat-id player-id (:save-id w)))
        result (ports/register-trainer-victory! store w)
        depois (xp/perfil-treinador (ports/get-save store chat-id player-id (:save-id w)))]
    (assoc result :nivel-anterior (:nivel antes) :nivel-novo (:nivel depois))))

(defn creditar-moedas!
  [store chat-id player-id amount operation-id]
  (let [w (freeze-writer store chat-id player-id)]
    (ports/credit-coins-on-active! store w amount operation-id)))

(defn ajustar-inventario!
  [store chat-id player-id item-id delta operation-id]
  (let [w (freeze-writer store chat-id player-id)]
    (ports/adjust-inventory-on-active! store w item-id delta operation-id)))
