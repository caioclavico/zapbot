# Odisseu A6 — Continuidade da caçada e PvP até esgotar Pokémon aptos

**Estado:** proposta de implementação, sem merge/deploy ou alterações em saves live.

## Bug confirmado na revisão

Na live A6, `modules/pokemon/domain/pokemon/domain/battle.clj`, `resolve-faint` chama `finish-pvp` ou `finish-lose` no primeiro nocaute do Pokémon ativo, mesmo quando existem outros Pokémon aptos. O PvP já mantém até três **trocas voluntárias** e cooldown de 60 s em `pokemon.domain.pvp`, mas isso não resolve substituição forçada.

## Contrato de jogo

- Em **caçada** e **PvP**, permitir **até três Pokémon distintos por treinador por batalha**, selecionados **durante** a batalha. Não obrigar a possuir três nem montar time antecipadamente.
- O primeiro Pokémon é o ativo inicial; quando ele desmaia, consultar a coleção atual (mesmo save, fence válido) e os participantes/nocautes da batalha. Se existir outro Pokémon elegível, entrar em estado **aguardando substituição** e oferecer números válidos para seleção.
- Se o jogador **não tiver outro Pokémon saudável e elegível**, mesmo que só possua um ou dois, **decretar derrota imediatamente**. Se os três participantes já foram derrotados, idem. Pokémon além dos três permitidos **não** podem ser escalados no mesmo confronto.
- A seleção após desmaio é **obrigatória**, não consome o limite de três trocas voluntárias nem sofre cooldown de 60s. A troca voluntária continua limitada a três e sujeita ao cooldown. Distinguir `pokemon-distintos-usados`, `eliminados`, `trocas-voluntarias`.
- Um Pokémon com HP zero ou já eliminado nesta batalha não volta, mesmo se o inventário for curado fora do combate. Pokémon retirado voluntariamente ainda apto pode voltar, mas não permite mais de três distintos no confronto.
- Cada lado do PvP tem sua própria lista de até três e seu próprio estado de substituição; o oponente não ganha vitória quando só o Pokémon ativo cai. Vitória ocorre quando a equipe adversária ficar sem combatentes aptos. Em nocaute simultâneo (Explosion, veneno etc), processar ambos os lados antes de definir vitória/empate.
- A faixa de compatibilidade de nível PvP (±3 do referencial estabelecido) vale ao entrar e substituir, inclusive após nocaute. Estabilizar a âncora da faixa para não variar indevidamente durante a luta.
- Em caçada, preservar Pokémon selvagem, HP e status restantes, turnos, chance de captura e evento original. Trocar não reinicia o adversário ou a caça.
- Não permitir que o lado aguardando substituição ataque, defenda ou use itens até escolher, nem permitir turno extra do rival nesse intervalo; definir inequivocamente quem age em seguida, com timeouts e retomada pós-restart.
- A UI WhatsApp menciona o treinador que precisa escolher e mostra números de Pokémon realmente aptos com mensagem clara, por exemplo: `💀 Seu Pikachu desmaiou! Escolha outro Pokémon: !pk trocar 2`. Se não houver aptos: `💀 Seus Pokémon disponíveis foram derrotados. Fim da batalha!`.

## Implementação e segurança

1. Inspecionar os fluxos e portas reais de batalha/coleção na JVM: `battle/resolve-faint`, `apply-player-turn`, `apply-pvp-turn`, serviço de caçada e persistência Datomic. Não inferir nomes de comandos; reutilizar aliases existentes.
2. Quando o nocaute ocorrer, localizar candidatos elegíveis sem mutação fora da transação. Se necessário, registrar `awaiting-replacement`, `fainted-indices`, `used-indices`, `switch-count` no estado persistido da batalha com compatibilidade retroativa.
3. O caminho de troca forçada deve validar o índice, identidade/HP/nível/save/fence e ser atômico e idempotente por recibo/event-id. Não apagar XP, níveis, itens, HP, stats ou resultados anteriores.
4. Evitar exploração trocando de save ou mudando Pokémon no inventário durante a luta; não ressuscitar um participante eliminado por updates externos.
5. Regras de recompensa PvP e caçada aplicadas **uma vez no desfecho definitivo**, nunca por nocaute intermediário. Compatível com PI e XP, sem concessão duplicada.
6. Testar com 1, 2, 3 e 4+ Pokémon na coleção; elegibilidade 0/1/2/3; nocaute simples/simultâneo; troca forçada e voluntária; cooldown; níveis incompatíveis; ausência de candidato; revive externo; timeouts; retry; concorrência; crash/restart; render/marcação WhatsApp e integridade Datomic.
7. Ensaiar em restore isolado, rodar CI e documentar `docs/a6-three-pokemon-battle-report.md` com riscos/gates.

**Sem autorização de merge/deploy/backfill:** etapa atual é desenvolver/testar e entregar PR. Nenhum save de produção pode ser alterado.