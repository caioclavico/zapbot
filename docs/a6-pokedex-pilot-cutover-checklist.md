# Checklist — cutover Pokédex RC + smoke no piloto

**NÃO EXECUTAR** sem GO operacional explícito.  
Este documento prepara; não reinicia Node/JVM, não envia WhatsApp, não altera Datomic/saves.

**Status de prontidão do RC (código):** pronto para cutover **somente após** imagem com gate de piloto (ver §2).  
**Status operacional agora:** **AGUARDANDO GO** — e **ABORT** se cutover cair na janela de spawn de raid.

---

## 1. Estado lido em produção (2026-11-05 ~18:07 UTC) — só leitura

| Item | Valor |
| --- | --- |
| Host | `odisseu-core` · `35.192.113.146` |
| Compose | `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z` |
| Node | `odisseu-node:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64` · `sha256:1dcb24f7…` · **healthy** · WA **READY** |
| JVM | `odisseu-jvm:a6-announcer-drain-…-dc56a993cb3b-amd64` · **healthy** · `raid-auto-tick=true` |
| Bridge | `/health` OK · `POKEMON_ADAPTER=jvm` · `APP_ENV=staging` |
| Maintenance | marker `phase=open` · tokens Node/JVM presentes |
| Announcer | `RELEASE_ANNOUNCER_ENABLED=false` · piloto `120363427684548487@g.us` |
| Raids auto | **ON** (`POKEMON_RAID_AUTO_TICK=true` / bridge tick) |
| Agenda piloto | `nextAt=1793903400000` → **2026-11-05 18:30 UTC** (~15:30 America/Sao_Paulo) · cat. `epico` · rev **31** · `enabled=true` |
| Último spawn | `raid-auto:…:1793898900000` (17:15 UTC) |
| GHA deploy hold | **confirmar no momento do GO** (`ODISSEU_PRODUCTION_DEPLOY_HOLD=true`; CLI `gh` indisponível nesta sessão) |

### Impacto nos outros grupos

Sem gate, `!pokedex`/`!dex`/`!pdx` ficariam **globais**.  
Com `POKEDEX_SPECIES_PILOT_ONLY=true` (default) + chat piloto: **só o piloto** recebe a ficha de espécie; demais grupos veem aviso curto.  
`!pk pokedex` (coleção) **não** é afetado em nenhum grupo.

---

## 2. Imagem candidata (atualizar após rebuild com gate)

### RC anterior (sem gate de piloto) — **NÃO usar para cutover**

| Campo | Valor |
| --- | --- |
| Tag | `odisseu-node:a6-pokedex-20261105T180125Z-77e347e73fdc-amd64` |
| ID | `sha256:bc6c73c117840405f635563189df2b3adb35e1c38dcfa83f1098f77cdd2c89ad` |
| Motivo | Expõe espécie a **todos** os grupos |

### RC com gate (usar esta para o GO)

Tip `5e6f9ca` · pacote `/Users/caioclavico/odisseu-recovery/a6-pokedex-rc-20261105T180900Z/` (`BUILD_OK`)

| Campo | Valor |
| --- | --- |
| Tag | `odisseu-node:a6-pokedex-20261105T180900Z-5e6f9caea073-amd64` |
| Image ID | `sha256:6c22cc067d39bc0336715c58d06061f0fe0bf626897728f6924cb181c35a8f4f` |
| Tar SHA-256 | `c009f8c149b6a843e94e921fb5563a6d4e7d940c28881402694687ffa1dcf6f2` |
| Smoke isolado | `GATE_OK` + `SMOKE_OK` (`--network none`) |

### Env no recreate (Node)

```
POKEDEX_SPECIES_PILOT_ONLY=true
# opcional — se vazio, usa RELEASE_PILOT_CHAT_ID já presente:
# POKEDEX_SPECIES_PILOT_CHAT_ID=120363427684548487@g.us
RELEASE_ANNOUNCER_ENABLED=false
RELEASE_PILOT_CHAT_ID=120363427684548487@g.us
```

JVM/Datomic: **sem mudança de imagem**.

---

## 3. Grupo de destino (smoke)

| Campo | Valor |
| --- | --- |
| Grupo piloto | `120363427684548487@g.us` |
| Comandos | `!pokedex pikachu` · `!dex 150` · `!pdx gengar` |
| Negativo (outro grupo) | `!pokedex pikachu` → aviso de homologação (sem arte) |
| Coleção | `!pk pokedex` inalterado |

---

## 4. Janela operacional

| Preferência | Motivo |
| --- | --- |
| **Depois** de `nextAt` (18:30 UTC / ~15:30 SP) + raid do slot resolvida/expirada | Evitar recreate no meio de spawn/lobby |
| Baixo tráfego no piloto | Menos risco de mensagens duplicadas na janela Node down |
| Drain Node (`/ops/maintenance`) antes do recreate | Já implantado (`phase=open` agora) |

**Duração estimada:** 2–5 min Node indisponível (JVM sobe; ticks de raid no Node pausam até READY).

---

## 5. Procedimento (só com GO)

1. Revalidar hold GHA, health Node/JVM, WA READY, `phase=open`, `nextAt` futuro, **sem** raid/troca ativa no piloto.  
2. `docker load` da tar RC com gate.  
3. Drain Node → recreate **somente** `odisseu-a6-node` com nova tag + env piloto.  
4. Validar: healthy, WA READY, maintenance `open`, raids ticks ON, `nextAt`/rev preservados ou coerentes.  
5. Smoke **apenas** no piloto (§3).  
6. Confirmar outro grupo (se houver acesso) recebe só o aviso.  
7. Announcer continua `false`.  

### Abortar se

- Hold GHA ausente / workflow de deploy ativo  
- Raid lobby/batalha ativa ou `nextAt` em &lt; 10 min  
- Troca aberta relevante no piloto  
- WA não READY / health falho pós-recreate  
- `reconciliationRequired` / marker estranho  
- Ticks de raid OFF sem intenção  
- Mensagens duplicadas ou caixa preta na arte  

### Rollback Node

```text
Tag:  odisseu-node:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64
ID:   sha256:1dcb24f7b2fcc3c692c07eba306f67e091b959f1f3959dc405ab2de48462800d
```

Recreate Node com tag acima; mesmos volumes/sessão; **não** reiniciar JVM. Remover/ignorar envs `POKEDEX_SPECIES_*` se necessário.

---

## 6. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Janela sem Node | Drain + janela longe do `nextAt` |
| Gate mal configurado (chat vazio) | Default pilot-only + `RELEASE_PILOT_CHAT_ID` já setado em prod |
| Tradução/PokeAPI lenta | Caption ainda sai; falha → mensagem amigável |
| Falhas de teste trade/saves pré-existentes | Fora do escopo Pokédex; não bloqueiam este RC |
| Sem staging WA isolado | Smoke só no piloto com GO; não conectar 2ª sessão |

---

## 7. Decisão

| Pergunta | Resposta |
| --- | --- |
| Cutover nesta execução? | **Não** |
| RC sem gate pronto para piloto? | **Não** — rebuild com gate obrigatório |
| Aguardando? | **GO operacional explícito** + imagem com gate + janela pós-`nextAt` |
