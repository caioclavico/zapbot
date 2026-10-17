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

  (attempt-capture! [this writer-ctx request]
    "Captura: consome bola, resolve chance, adiciona Pokémon se sucesso.
     CAS ativo+fence; recibo operation-id + payload-hash.")

  (evolve-pokemon! [this writer-ctx request]
    "Evolui Pokémon no índice; consome item se :item-id; fence + recibo.")

  (learn-move! [this writer-ctx request]
    "Aprende/substitui golpe no índice; fence + recibo.")

  (start-wild-battle! [this writer-ctx request]
    "Inicia batalha selvagem no save ativo; bloqueia se já houver ativa.")

  (confirm-battle-turn! [this writer-ctx request]
    "Persiste turno confirmado (CAS turn-index + fence); recompensas via M3.2.")

  (get-battle [this battle-id]
    "Snapshot da batalha ou nil.")

  (active-battle [this chat-id player-id]
    "Batalha ativa do jogador ou nil.")

  (get-gym-occupation [this chat-id gym-id]
    "Ocupação atual do ginásio no grupo ou nil (NPC).")

  (list-gym-occupations-for-player [this chat-id player-id]
    "Ginásios que o jogador ocupa (qualquer save).")

  (set-gym-team! [this writer-ctx indices]
    "Salva escalação de 3 índices no save ativo.")

  (resolve-gym-challenge! [this writer-ctx request]
    "Aplica derrota/vitória: PE/XP/moedas/insígnia/ocupação com fence+recibo.")

  (heal-gym-motivation! [this writer-ctx request]
    "Recupera motivação do defensor (líder do save ativo); consome item.")

  (get-raid [this raid-id]
    "Snapshot da raid ou nil.")

  (active-raid [this chat-id]
    "Raid ativa (inscrições/combate e não expirada) do chat ou nil.")

  (open-raid! [this writer-ctx request]
    "Abre inscrições de raid no chat (criação manual/harness).")

  (join-raid! [this writer-ctx request]
    "Inscreve com save-id+fence carimbados; snapshot do Pokémon.")

  (leave-raid! [this writer-ctx request]
    "Sai durante inscrições.")

  (start-raid! [this writer-ctx request]
    "Inicia combate (≥2); define HP compartilhado.")

  (raid-attack! [this writer-ctx request]
    "Turno de ataque com CAS turn-index; recompensas na transição vitória.")

  (raid-capture! [this writer-ctx request]
    "Tentativa de captura pós-vitória (bola + roll).")

  (ensure-group! [this chat-id label]
    "Garante entidade de grupo separada dos saves.")

  (get-group [this chat-id]
    "Estado mínimo do grupo ou nil."))
