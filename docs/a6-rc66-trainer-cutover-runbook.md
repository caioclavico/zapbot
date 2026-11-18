# Odisseu A6 — Runbook de cutover RC #66 (PE do treinador)

**Escopo:** aplicar progressão progressiva do treinador + schema 036 aditivo sobre a live com drain JVM (#74).  
**Não executa** cutover por si só — exige autorização explícita de produção.

## Pré-condições

1. Live tip contendo drain completo (`open → draining → quiescent → frozen` + resume).
2. Node live saudável (PR #71 ou sucessor autorizado); WhatsApp READY.
3. Announcer `suspended`; deploy hold `true`; CI/CD `disabled_manually`.
4. Backup cold real validado dos volumes `odisseu-a6-cutover-{datomic,journal,maint,data}` com fingerprint MATCH e restore isolado PASS.
5. Se os saves mudaram desde o cold anterior, capturar **novo** cold na janela (não reutilizar cegamente um backup antigo como T0).
6. Imagem JVM candidata `linux/amd64` com: PE + 036 + drain; digest/tar registrados.
7. Imagem de rollback **compatível com 036** (nunca a JVM 035-only após a primeira escrita 036).
8. LocalAuth permanece no bind host; fora do cold de 4 volumes.

## Ordem canônica da janela

1. Preflight RO (digests, health, WA READY, Announcer suspended, hold/CI, volumes, disco).
2. Baseline canônico RO dos saves.
3. Node `enter` → `quiescent`.
4. JVM `enter` → `draining` → `quiescent` (provas de contadores; ticks 503).
5. Freeze JVM → `frozen` (leituras e mutações bloqueadas).
6. Stop controlado Node → JVM (`docker stop`, sem `-v`, restart=`no`).
7. Cold backup atualizado da janela (helper `a6_cutover_backup.py`) + verify/checksums.
8. Start JVM candidata (ainda `frozen` ou quiescent conforme marker).
9. Aplicar migration **036** de forma explícita e idempotente (`pokemon.infrastructure.datomic.schema/install!` no writer único). Leituras HTTP **não** garantem o apply — confirmar marcador `pokemon/036-trainer-level-rewards` e atributos `:save/trainer-xp-level-progress` / `:trainer-level-reward/*`. Sem backfill: entidades de progresso residual devem permanecer 0 até o primeiro grant de PE.
10. Validar schema 036 + health bridge. Separar invariantes canônicos (jogadores/saves/Pokémon/moedas/XP armazenado) de campos **derivados** na apresentação PE (nível/capacidade exibidos podem divergir do fingerprint clássico 035 até o residual ser persistido por gameplay).
11. **Resume JVM → `open`**.
12. Start/restart Node (Node **não** sobe com JVM `frozen` — platform hydrate precisa de leitura).
13. Confirmar WhatsApp READY; Node ainda pode estar `quiescent`.
14. **Resume Node → `open`**.
15. Testes de XP/recompensas em piloto; raids enabled; Announcer permanece suspended.
16. Abortar e usar rollback 036-compatível se qualquer gate falhar após a migration.

## Critérios de aborto

- Drain timeout / writes tardios / writers externos.
- Freeze não persistente.
- Checksum/manifesto de backup inválido.
- Migration 036 não idempotente ou divergência canônica inexplicada.
- Node ERROR por manutenção (JVM ainda frozen) sem correção ordenada.
- Duplicação de recompensas / ledger inconsistente.

## Rollback

- **Antes de 036:** retornar à JVM 035+drain live anterior.
- **Depois de 036:** somente JVM que entende 036 (candidata anterior da mesma linhagem PE+drain). Proibido `670a75a4` (035-only) após escrita 036.
- Não remover markers manualmente; usar endpoints `resume`/`enter`.
- Não restaurar cold antigo sobre volumes live sem autorização destrutiva explícita.

## Retomada de emergência (aprendizado da produção)

Se Node boot falhar com `maintenance_active` / `frozen`:

1. Confirmar JVM healthy.
2. `POST /ops/maintenance/resume` na JVM.
3. `docker restart` do Node.
4. Aguardar READY.
5. Resume Node se ainda quiescent.
