# A6.4 — Relatório de acesso SSH Core + ensaio migration 025

Branch: `a6-save-slots` (commits preservados: `32fcaf4`, `20786d2`).
Data: 2026-10-27. **Sem import V1/V2, sem deploy, sem escrita Cassandra.**

## 1. Evidência SSH (somente leitura)

Alvo documentado: `caiohclavico@35.192.113.146` (`odisseu-core`).

| Probe | Resultado |
|-------|-----------|
| ICMP ping | OK (~172–198 ms, 0% loss) |
| TCP :22 | Aberto |
| `~/.ssh/odisseu_core` → `caiohclavico` | `Permission denied (publickey)` |
| `~/.ssh/google_pokemon` → `caiohclavico` | `Permission denied (publickey)` |
| Demais chaves locais (`odisseu_github_deploy`, `oracle_recovery`, `zapbot-actions-odisseu`, `github_odisseu`) | Negadas |
| `ubuntu@` + `odisseu_core` / `google_pokemon` | Negadas |

Fingerprints locais (públicos):

- `odisseu_core.pub` → `SHA256:oLGhQh2ZL+epaseTrLu020Hd915jUrdJS4W3CyUwx6Q` (ED25519, comment `odisseu-core-mac`)
- `google_pokemon.pub` → `SHA256:71z8+USNQN7wXjGHYfNuo+VEJcAH9s2GJsPTdl6DG7s` (ED25519, comment `caiohclavico`)

**Causa mais provável (ainda não confirmada no metadata GCP):** a VM aceita publickey, mas **nenhuma chave deste Mac está autorizada** em `~/.ssh/authorized_keys`, OS Login ou `metadata.ssh-keys`. Nenhuma alteração foi feita no servidor.

## 2. Google Cloud CLI

- `gcloud` instalado via Homebrew cask (`Google Cloud SDK 591.0.0`).
- **Sem conta autenticada** (`No credentialed accounts`).
- `gcloud auth login --no-launch-browser` inicia o fluxo OAuth, mas o shell do agente não consegue receber o código interativo.

Sem login GCP não foi possível listar instância/zona/IP atual nem inspecionar `metadata.ssh-keys` / OS Login.

## 3. Volumes Datomic esperados (código/docs)

Compose base (`docker-compose.odisseu-core.yml`):

- `odisseu-core-datomic` → `/data/datomic-local`
- `odisseu-core-journal`, `odisseu-core-maint`, `odisseu-core-data`

Contexto A5.6 (implantação `2cab639`): volumes nomeados
`odisseu-core-a56-datomic|journal|maint|data` (overlay de release na VM — **não confirmável sem SSH**).

Backup consistente suportado pelo projeto (`scripts/odisseu-core-backup.sh`):

1. Parar JVM (liberar FileLock; health `:18090` deve falhar).
2. Copiar volumes via container auxiliar `alpine` (`cp -a` read-only do volume).
3. Nunca copiar base com writer ativo.

## 4. Ensaio migration 025 já realizado (cópia local)

Fonte: backup a frio `~/odisseu-recovery/a455-20261022T172313Z`
(`host=odisseu-core`, `system=odisseu-m90-core`, git `6f62a67`).

Cópia descartável: `~/odisseu-recovery/a64-preflight/datomic-025-rehearsal`
(original `a455` não modificado).

| Check | Resultado |
|-------|-----------|
| Attrs 025 antes | ausentes |
| `schema/install!` | `pokemon/025-save-slot-metadata` aplicada |
| Attrs `:save/display-name` … `:save/last-rename-name` | presentes |
| Players / saves | 3 / 6 preservados |
| Slots por jogador | `a`+`b` em todos |
| Active save | `a` (inalterado) |
| Coleções | contagens preservadas (ex.: um save ativo com 6 Pokémon) |
| Migrations | …024 → **025** no final da cadeia |

**Limitação crítica:** o snapshot `a455` é um Core **pequeno/parcial** (2 jogadores `@lid` + 1 sintético de teste). **Não** representa o inventário live A5.6 (`2cab639` / volumes `odisseu-core-a56-*`). Serve apenas como prova de que a migration 025 é aditiva nesse dataset.

## 5. Riscos

1. **Acesso SSH quebrado** bloqueia backup frio do volume A5.6 live e o ensaio 025 definitivo.
2. Copiar Datomic com JVM up corrompe consistência (FileLock/writer) — proibido.
3. Confundir `odisseu-core-datomic` vs `odisseu-core-a56-datomic` no restore.
4. Reautorizar chave errada no metadata sem diagnóstico pode expulsar o operador atual (se ainda houver outra chave válida em outra máquina).

## 6. Próximos passos (ordem)

1. **Autenticar `gcloud`** neste Mac (fluxo OAuth interativo) e inspecionar:
   - `gcloud compute instances list --filter='name~odisseu'`
   - IP externo/zona da VM
   - `get-serial-port-output` / `describe` → `metadata.items[ssh-keys]` e `enable-oslogin`
2. **Sem alterar a VM**, comparar fingerprints autorizados com `odisseu_core.pub` / `google_pokemon.pub`.
3. Só então decidir: restaurar a chave correta no metadata **ou** usar a máquina que ainda tem a chave válida.
4. Com SSH: identificar volume montado no `odisseu-core-jvm`; `./scripts/odisseu-core-backup.sh` (JVM down); restaurar em ambiente descartável; rodar 025; validar contagens reais (treinadores/saves/coleções).
5. Manter: sem import V1/V2, sem deploy, sem escrita Cassandra.

## 7. Ação necessária do operador

Completar no terminal local:

```bash
export PATH="/opt/homebrew/bin:/opt/homebrew/share/google-cloud-sdk/bin:$PATH"
gcloud auth login
gcloud config set project <PROJECT_ID>
gcloud compute instances list --filter='name~odisseu OR networkInterfaces.accessConfigs.natIP=35.192.113.146'
```

Ou, se o acesso for via Console GCP: confirmar qual chave pública está em
`SSH Keys` / OS Login da instância `odisseu-core` e se o IP externo ainda é
`35.192.113.146`.
