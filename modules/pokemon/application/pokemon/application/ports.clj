(ns pokemon.application.ports
  "Ports da aplicação — domínio não depende de Datomic.")

(defprotocol TrainerStore
  "Persistência do núcleo treinador/saves + coleção + recompensas (M3.2).

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

  (list-collection [this chat-id player-id save-id]
    "Coleção ordenada por collection-index (legado equipe unificada).")

  (list-inventory [this chat-id player-id save-id]
    "Itens do inventário do save.")

  (active-save-id [this chat-id player-id]
    "Ponteiro ativo ou nil.")

  (activate-save! [this chat-id player-id save-id]
    "Alterna ponteiro A↔B com CAS de fence. Não sobrescreve o outro save.")

  (apply-gameplay-reward! [this writer-ctx request]
    "Recompensa de gameplay: CAS save ativo+fence na mesma TX;
     recibo com payload-hash; canal \"gameplay\".")

  (admin-seed-reward! [this request]
    "EXPLÍCITO: seed/harness/migração sem fence. Canal \"admin\".
     Não usar em comandos comuns.")

  (add-to-collection! [this writer-ctx registro]
    "Acrescenta Pokémon à coleção do save congelado no writer-ctx (CAS ativo+fence).")

  (update-hp-status! [this writer-ctx idx hp-atual status]
    "Atualiza HP/status no índice da coleção do save ativo congelado.")

  (grant-pokemon-xp! [this writer-ctx idx quantidade]
    "Concede XP ao Pokémon no índice (regras legado ganhar-xp-no-indice!).")

  (register-trainer-victory! [this writer-ctx]
    "Incrementa vitorias-treinador no save ativo (CAS fence).")

  (ensure-group! [this chat-id label]
    "Garante entidade de grupo separada dos saves.")

  (get-group [this chat-id]
    "Estado mínimo do grupo ou nil."))
