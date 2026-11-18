(ns pokemon.application.collection
  "Casos de uso M3.1/M3.2: consulta/coleção, HP/status, XP, moedas/inventário
  no save ativo — com writer fence (save-id + fence-generation).
  Moedas/inventário passam pelo caminho único de recompensa de gameplay."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.xp :as xp]))

(def freeze-writer
  "Reexporta o congelamento do save ativo."
  writer/freeze-writer)

(defn consultar-treinador
  "Perfil do treinador + coleção + economia do save ativo."
  [store chat-id player-id]
  (let [w (writer/freeze-writer store chat-id player-id)
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
  (let [w (writer/freeze-writer store chat-id player-id)
        registro (coll/pokemon->registro pokemon hp-atual status)]
    (ports/add-to-collection! store w registro)))

(defn atualizar-hp-status!
  "Espelha atualizar-no-indice! no save ativo."
  [store chat-id player-id idx hp-atual status]
  (let [w (writer/freeze-writer store chat-id player-id)]
    (ports/update-hp-status! store w idx hp-atual status)))

(defn ganhar-xp-pokemon!
  "Espelha ganhar-xp-no-indice!."
  [store chat-id player-id idx quantidade]
  (let [w (writer/freeze-writer store chat-id player-id)]
    (ports/grant-pokemon-xp! store w idx quantidade)))

(defn registrar-vitoria-treinador!
  "Espelha registrar-vitoria-treinador! (só o contador de vitórias neste recorte)."
  [store chat-id player-id]
  (let [w (writer/freeze-writer store chat-id player-id)
        antes (xp/perfil-treinador (ports/get-save store chat-id player-id (:save-id w)))
        result (ports/register-trainer-victory! store w)
        depois (xp/perfil-treinador (ports/get-save store chat-id player-id (:save-id w)))]
    (assoc result :nivel-anterior (:nivel antes) :nivel-novo (:nivel depois))))

(defn creditar-moedas!
  "Credita moedas via apply-gameplay-reward! (fence + payload-hash)."
  [store chat-id player-id amount operation-id]
  (let [w (writer/freeze-writer store chat-id player-id)]
    (ports/apply-gameplay-reward!
     store w {:operation-id operation-id
              :coins-delta amount})))

(defn ajustar-inventario!
  "Ajusta inventário via apply-gameplay-reward! (fence + payload-hash)."
  [store chat-id player-id item-id delta operation-id]
  (let [w (writer/freeze-writer store chat-id player-id)]
    (ports/apply-gameplay-reward!
     store w {:operation-id operation-id
              :item {:item-id item-id :qty delta}})))
