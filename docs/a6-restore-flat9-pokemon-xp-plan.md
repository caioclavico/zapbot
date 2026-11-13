# Odisseu A6 — Retorno controlado à progressão original flat-9

**Estado:** PROPOSTA / DRAFT — sem implementação, merge, deploy ou migração autorizada.
**Base:** live pós-hotfix #85 e reparação dos quatro Pokémon comprovadamente afetados.
**Exclui:** PR #84 (growth-v2), PR #83 e mudança de PE do treinador, PI dos ovos ou economia.

## Contrato funcional

- Cada Pokémon requer **9 XP residuais** para subir de nível, independentemente de espécie ou nível.
- Vitória comum **3 XP**; derrota **1 XP** se compatível com o fluxo histórico, verificando origem e efeitos.
- O restante é preservado entre ganhos; no nível 100 não ganha níveis adicionais.
- Não aplicar escala de curvas PokéAPI, razão nível-adversário, multiplicadores de raid/gym ou cap de 60% **sem** decisão explícita sobre como pontos de atividade se convertem em XP; preservar atividades especiais existentes em tabela de mapeamento auditada.
- Uma operação de conversão ou leitura **jamais** altera nível, espécie, golpes, stats ou amizades.
- Só eventos reais de gameplay concedem níveis e atualizam stats.
- A conversão lazy ocorre somente dentro de transação idempotente do evento confirmado.
- A UI deve apresentar `XP: n/9`, nunca total oficial acumulado.

## Modelo canônico proposto

- Introduzir `xp-progress-version = "flat-9-v2"`, **sem reutilizar** os significados `growth-v1` e `growth-v2`.
- `xp-desde-nivel` e `xp`: residual canônico no intervalo 0..8 para níveis 1..99; no 100, zero.
- `xp-total`: evitar usar como XP oficial; decidir de forma consistente entre retirar do caminho operativo e gravar um total sintético `(nivel-1)*9+residual` para compatibilidade, sempre protegido por versionamento.
- Preservar `:xp-legacy-residual`, `:xp-legacy-nivel` e o histórico de Datomic, sem reescrever datoms antigos.
- XP desconhecido permanece desconhecido; primeiro ganho positivo inicia residual 0 no nível persistido, **sem** declarar progresso histórico inventado.

## Migração dos Pokémon existentes — NO-GO até ensaio

Inventariar por versão e origem, com **níveis já reparados** como baseline:

1. **`growth-v1` confiável**: calcular fração `xp-desde-nivel / span-oficial(nível)`; novo residual = `floor(fração*9)`, limitada a 0..8; **nunca** inferir o nível a partir de XP acumulado.
2. **Legado flat-9 comprovado**: preservar residual 0..8.
3. **Sem versão, residual >=9**: **ambíguo** (origem pode ser XP oficial armazenado sem versão, como no incidente #85); não fazer `quot(residual,9)` como subida, nem `mod 9` como progresso legítimo sem evidência. Falhar fechado, documentar e decidir individualmente com auditoria.
4. **`growth-v2`** (se surgirem no ambiente): converter fração `xp-desde-nivel / span-simple(nível)`; preservar nível.
5. **XP desconhecido**: não transformar em 0 na leitura ou manutenção.

Todos os writes devem usar versionamento explícito, CAS/recibo idempotente e controle de concorrência. Identidades e campos não-XP devem ser preservados; migrar apenas no restore antes de liberar lazy live.

## Auditoria / testes obrigatórios

- Verificar que a versão atual da JVM em produção é o hotfix #85; fazer inventário live somente leitura.
- Reproduzir casos Blastoise, Tyranitar, Crobat, Amaura e Staryu sem salto por conversão.
- Testar 0,1,3,8,9,10,18 XP, subida múltipla legítima, excedente e nível 100.
- Testar status, apresentação WhatsApp, evolução, MT, caçada, ginásio, PvP e raids.
- Testar operações de zero XP, retry, duplicidade de recibo, concorrência, restart e frota mista.
- Comparar contagem e hashes de jogadores/saves/Pokémon/moedas/itens/ovos/recibos e níveis antes/depois em restore isolado.
- Snapshot cold **novo** da janela, restore offline, builder JVM AMD64, único writer, rollback compatível com formatos mistos.
- Os quatro reparos concluídos não devem ser revertidos.

## Gates de liberação

1. Implementação e testes CI **verdes**.
2. Ensaio isolado com backup cold coerente e dados recentes.
3. Zero alteração de nível na conversão e nos reads; zero perda de saves.
4. Tratamento explícito para **todos** os unversioned com XP ambíguo, sem promover ou apagar progresso.
5. Saúde JVM/Node/WA READY, rollback e recibos verificáveis.
6. GO separado para merge/deploy e para qualquer reparo/backfill.

**Este documento não é implementação:** proíbe interpretar abertura do PR como aprovação de produção.
