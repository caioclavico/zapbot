(ns pokemon.application.ports
  "Ports da aplicação — domínio não depende de Datomic.")

(defprotocol TrainerStore
  "Persistência do núcleo treinador/saves.

  Implementações (ex.: Datomic Local) vivem em infrastructure."

  (ensure-schema! [this]
    "Instala schemas versionados ainda não aplicados.")

  (create-player! [this chat-id player-id]
    "Cria conta do jogador. Idempotente se já existir.")

  (create-save! [this chat-id player-id save-id]
    "Cria save vazio no slot. Falha se já existir.")

  (get-player [this chat-id player-id]
    "Retorna mapa da conta ou nil.")

  (get-save [this chat-id player-id save-id]
    "Retorna mapa do save (economia, progresso) ou nil.")

  (list-party [this chat-id player-id save-id]
    "Pokémon com in-party=true do save.")

  (list-inventory [this chat-id player-id save-id]
    "Itens do inventário do save.")

  (active-save-id [this chat-id player-id]
    "Ponteiro ativo ou nil.")

  (activate-save! [this chat-id player-id save-id]
    "Alterna ponteiro A↔B com CAS de fence. Não sobrescreve o outro save.")

  (apply-reward! [this request]
    "Aplica recompensa idempotente no save indicado (operation-id único).")

  (ensure-group! [this chat-id label]
    "Garante entidade de grupo separada dos saves.")

  (get-group [this chat-id]
    "Estado mínimo do grupo ou nil."))
