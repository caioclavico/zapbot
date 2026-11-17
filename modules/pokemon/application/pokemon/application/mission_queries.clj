(ns pokemon.application.mission-queries
  "Fundação A4.3.1: consulta e preparação de período, sem eventos ou prêmios.")

(defprotocol MissionQueries
  (read-missions [store chat-id player-id save-id options]
    "Snapshot sem escrita. options: :instant e :timezone, apenas internos.")
  (prepare-missions! [store writer options]
    "Fixa a faixa diária no save ativo com CAS/fencing; não concede recompensas."))
