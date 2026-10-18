# M4.9 — Raids no adaptador JVM

## Auditoria legado × M3.6

| Fluxo | Legado WhatsApp | JVM M3.6 | M4.9 |
| --- | --- | --- | --- |
| Abrir / auto-spawn | Removido (`abrir` só mensagem) | `open-raid!` harness | **Bloqueado** no WhatsApp; harness/teste só |
| Status / resumo | `!pk raide` | `active-raid` | **Habilitado** |
| Entrar / sair | sim | `join` / `leave` | **Habilitado** |
| Iniciar (≥2) | qualquer inscrito se sem criador | `start-raid!` | **Habilitado** |
| Atacar | fórmula própria | `raid/atacar` | **Habilitado** |
| Cancelar | exige `criador` | **ausente** | **Bloqueado** |
| Vitória / recompensas | no ataque final | TX do attack | Servidor only |
| Captura pós-raid | bolas + roll | `raid-capture!` | **Habilitado** |
| Elegibilidade de liga | `treinador/elegivel?` | gap M3.6 | **Adicionada** no join |
| `gin desafiar` com raid | → `entrar auto` | — | Redireciona para join |

Motor de dano: **fórmula de raid** (não 1×1 M3.4). Sem Cassandra no gameplay JVM.

## Comandos

| Comando | Status |
| --- | --- |
| `!pk raide` / `raid` | Status da raid ativa |
| `!pk raide entrar [n\|auto]` | Entrar (índice da coleção) |
| `!pk raide sair` | Sair (só inscrições) |
| `!pk raide iniciar` | Iniciar combate |
| `!pk raide atacar <1-4>` | Turno |
| `!pk raide capturar <bola>` | Pós-vitória |
| `!pk gin entrar/iniciar/atacar/sair/capturar` | Mesmos fluxos |
| `!pk raide abrir` / `cancelar` | Bloqueados (sem suporte seguro) |
| Abertura admin | Fora do WhatsApp |

## Contratos HTTP

| Método | Rota |
| --- | --- |
| GET | `/v1/raids/active?chatId=` |
| POST | `/v1/raids/join` · `/leave` · `/start` · `/attack` · `/capture` |
| POST | `/v1/raids/open` — **só** se `allowTestRng` (harness) |

## Divergências aceitas

1. Sem cancelar (domínio não modela).
2. Sem SVG dedicado de raid (texto + mídia genérica se houver).
3. Sem auto-aparicao (igual legado atual).
4. `time` / lista visual de aptos: texto a partir da coleção + liga da raid (sem persistir escalação).
5. Ponteiro `chat-active-raid` no store não filtra por relógio; a bridge aplica `agora` (visível só em inscrição/combate válidos ou janela de captura).

## Testes

```bash
./scripts/test-m4-raid.sh
```
