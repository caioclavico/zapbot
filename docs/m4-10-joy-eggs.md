# M4.10 — Enfermeira Joy e ovos (Adventure Sync)

## Escopo

Integra ao adaptador Node → JVM → Datomic os fluxos **Joy** e **ovos/incubação** do domínio M3.7.

**Não inclui:** pedras de evolução, surtos, auto-drop de ovos por captura/PvP, pontos de Adventure Sync por eventos, nem **notificações push** / agendador 60s.

## Comandos habilitados

| Comando | Ação |
| --- | --- |
| `!pk joy` / `enfermeira` / `enfermaria` / `hospital` | Status + lazy collect dos prontos |
| `!pk joy 1,2` | Envia índices (coleção, 1-based; ordem decrescente) |
| `!pk ovos` / `ovo` | Inventário + incubadoras |
| `!pk incubadoras` | Mesmo status |
| `!pk incubar <n> [permanente]` | Inicia incubação (carimba save+fence) |
| `!pk ovo nascer <n>` | Nascimento sob demanda no save carimbado |

## Bloqueados / pendentes

| Item | Motivo |
| --- | --- |
| Notificação automática de eclosão | Sem agendador durável seguro neste adaptador — só sob demanda |
| Concessão de ovo pelo WhatsApp | Harness (`allowTestRng`) apenas |
| Pedras / surtos (`aventuras.cljs`) | Fora do Adventure Sync |
| `curar` / `pocao` (itens) | Outro fluxo (loja); não é Joy |

## Contratos HTTP

| Método | Rota |
| --- | --- |
| GET | `/v1/joy` |
| POST | `/v1/joy/send` · `/v1/joy/collect` |
| GET | `/v1/adventure` |
| POST | `/v1/adventure/eggs/incubate` · `/hatch` |
| POST | `/v1/adventure/eggs/grant` — só harness |

## Divergências do legado

1. Índices Joy usam **coleção** (como outros comandos M4), não só “equipe” legada.
2. Collect lazy só no comando `joy` (não em todo `!pk`).
3. Eclosão e progresso de tempo: **sob demanda** (sem worker/outbox).
4. Incubadoras extras/especial: só se existirem na conta; default permanente.

## Garantias

- Fence + operationId nas mutações; retry não duplica cura/nascimento.
- A→B→A: leitos por save; ovo carimbado no save da incubação.
- Mídia/envio WhatsApp após mutação (falha de mídia não reexecuta TX).

## Testes

```bash
./scripts/test-m4-joy-eggs.sh
```
