# Odisseu A6 — XP progressivo v3: gate anti-incidente #85

## Escopo (opt-in)
- Progressão de nível do Pokémon: L1=9 XP → L50=30 XP, L50–99=30.
- XP por sequência de capturas, independentemente do nível: [2,3,4,6,8,10] para faixas 1–2, 3–4, 5–9, 10–19, 20–29, 30+.
- PE e moedas não mudam. Vitórias de batalha e derrotas continuam com as regras existentes.
- A versão **v3 só é ativada** por \`POKEMON_PROGRESSIVE_XP_V3=true\` no processo **JVM**. Default = false, evitando ativação acidental.

## Proteções anti-jump
- Conversão de \`flat-9-v2\` para \`progressive-xp-v3\` é **lazy** no primeiro grant XP válido; não há backfill global.
- Nível atual é lido de \`:nivel\`, **nunca** calculado a partir de \`:xp-total\`. O total antigo é ignorado durante a conversão.
- XP residual 0..8 é convertido proporcionalmente: \`floor((residual/9)*xp-necessario(nivel))\`. Nunca sobe nível, cura HP ou aumenta atributos na conversão.
- Na versão v3 a soma sintética é apenas para relatório; o próximo nível é calculado pelo residual e a curva.
- Entradas sem versão com residual >=9, versões desconhecidas, nível fora de 1..100 e progressos inconsistentes **falham fechados**. Não inventar, arredondar incorretamente ou zerar o XP.
- \`xp/progresso-pokemon\` pode projetar a nova barra em modo leitura, sem transação Datomic.
- \`growth/after-evolution\` preserva nível/progresso; a evolução de espécie não é um grant de XP.
- Idempotência, recibos, CAS, writer e manutenção continuam os existentes.

## Nota operacional CRÍTICA: rollback

Depois que **qualquer save** tiver \`xp-progress-version=progressive-xp-v3\`, a imagem JVM antiga do PR #133 (\`a4a7807\`) **não é um alvo seguro de rollback**: não conhece o novo formato. A flag OFF no código novo preserva a interpretação v3 de Pokémon já convertidos, mas voltar para o código antigo pode descartar progresso.

Antes de ativar a flag em produção:
1. Revisar todos os caminhos que escrevem XP, evolução, captura, raid, ginásio, PvP, Doce e chocagem.
2. Rodar a CI, testes v3 e regressão do incidente #85.
3. Realizar um **ensaio isolado** com cópia do cold backup atual: validar todos os saves e Pokémon existentes com v2→v3, **sem conceder XP**. Bloquear ativação se qualquer registro tiver progressão ambígua ou regressão.
4. Comparar manifesto pré/pós: ID, save, espécie, nível, HP, atributos, inventário e economia sem qualquer alteração na conversão pura.
5. Testar transação de grant e rollback/retry com Datomic descartável, sem XP duplicado.
6. Preparar imagem de rollback **v3-aware** ou processo de reversão formal, nunca restaurar backup antigo sobre escritas legítimas.
7. Obter GO explícito de uma nova janela de deploy/configuração, com maintenance, drain e backup cold; hold de deploy volta a \`true\`.
8. Liberar igualmente para todos os chats habilitados; não ativar só em um \`chatId\`.

## Testes obrigatórios e decisão
- \`pokemon.tests.progressive-xp-v3-a6-test\`: curva, conversão, idempotência, erros, L100, preservação de atributos, streak.
- \`test/pokemon-a6-progressive-xp-v3.test.cjs\`: barra v3, legado v2 e desconhecidos.
- Suítes existentes JVM/Node, gráficos e regressão de captura/raids.
- Resultado desta branch enquanto não for homologada: \`MERGE_NO_GO / DEPLOY_NO_GO / FEATURE_OFF\`.

Nenhuma alteração deste PR faz deploy, migração de saves ou ativação da flag por padrão.
