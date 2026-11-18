# Odisseu A6 — PvP com até 3 Pokémon e substituição após nocaute

**Estado:** especificação de implementação. Sem merge/deploy ou alterações nos saves live.

## Escopo explícito

**Somente PvP.** Na caçada selvagem permanece a mecânica atual **1 Pokémon contra o selvagem**: se o Pokémon ativo desmaiar, ocorre derrota e a caçada termina. **Não permitir troca na caçada**, nem alterar seu fluxo de HP, captura, recompensa ou persistência.

## Problema confirmado

Na live A6, `modules/pokemon/domain/pokemon/domain/battle.clj`, `resolve-faint` termina o PvP imediatamente no primeiro nocaute com `finish-pvp`. O PvP já oferece até 3 trocas voluntárias e intervalo de 60 segundos, mas o nocaute não conduz a uma substituição obrigatória. Corrigir apenas o ramo `:pvp` e os fluxos relacionados, mantendo o ramo de caça `:wild` intacto.

## Contrato de PvP

- **Até 3 Pokémon distintos por treinador por batalha**, escolhidos **durante** o confronto. Não é necessário possuir 3 nem montar escalação antecipadamente.
- Início usa o Pokémon ativo. Ao desmaiar, se restar outro Pokémon apto e elegível entre até três participantes permitidos, o combate entra em estado **aguardando substituição obrigatória**. A vitória/derrota não ocorre nesse primeiro nocaute.
- Quando não houver outro Pokémon apto dentro do limite de 3 distintos, ocorre derrota imediatamente; um jogador com apenas 1 ou 2 Pokémon aptos pode lutar normalmente com essa quantidade.
- Substituição **obrigatória** após nocaute não consome o limite de 3 trocas **voluntárias** nem sofre o intervalo de 60 segundos. Trocas voluntárias continuam limitadas e sujeitas a cooldown.
- Pokémon com HP 0 ou eliminado não pode voltar na mesma batalha, mesmo após cura fora do combate. Pokémon retirado voluntariamente, ainda apto, pode retornar; nunca permitir 4º Pokémon distinto por partida.
- Cada participante PvP controla independentemente seus Pokémon usados, eliminados e sua janela de substituição. O adversário não recebe vitória por um único nocaute.
- Nocaute simultâneo (Explosion/status) deve avaliar os dois lados e suas reservas aptas antes de vitória, derrota ou empate.
- Compatibilidade de nível **±3** em relação à âncora do desafio original, também para substituição; não redefinir a âncora depois de trocar de Pokémon.
- Enquanto aguarda escolha obrigatória, bloquear ataques/itens/defesa do lado desmaiado; impedir turno extra adversário. Persistir estado e reconstituí-lo após restart; cuidar dos timeouts.
- Apresentação WhatsApp menciona o jogador e lista opções reais por número, usando comando/aliases existentes (confirmar roteador antes de escolher sintaxe). Exemplo: `💀 Seu Pikachu desmaiou! Escolha outro Pokémon para continuar.`
- Apenas o resultado **definitivo** concede vitória, XP, PI, PE ou moedas; preservar idempotência e recibos.

## Implementação

1. Auditar `pokemon.domain.battle/resolve-faint`, `apply-pvp-turn`, `trocar-pokemon-pvp`, integração com coleção e persistência Datomic. Verificar como informar opções de reservas na hora do nocaute sem escolher previamente.
2. Persistir quando necessário índices e identidades dos até 3 Pokémon distintos, eliminados e estado `awaiting-replacement`, sem sobrescrever equipe ou saves.
3. Validar substituição sob lock/CAS com request-id idempotente, save/fence, HP, faixa de nível e limite de 3.
4. Manter inalterados `create-wild-battle`, `apply-player-turn` e a finalização `:wild` (salvo refatoração comprovadamente neutra); sem fallback de substituição na caça.
5. Testes: PvP com 1, 2, 3 e 4+ disponíveis, nocaute de 1º/2º/3º, ausência de aptos, voluntária vs obrigatória, cooldown, nível incompatível, mesmo Pokémon desmaiado, troca/revive externo, nocaute simultâneo, retries e restart.
6. Testes explícitos de regressão **caçada continua 1×1 e desmaio termina a caça sem trocar**; resultados e recompensas invariantes.
7. CI, ensaio isolado e relatório `docs/a6-three-pokemon-battle-report.md`. Sem mutação live.

**Sem autorização de merge/deploy/backfill.**