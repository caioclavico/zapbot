# Arquitetura — monólito modular + Datomic Local

**Status:** M0–M1 feitos; M2 núcleo treinador/saves Datomic implementado (dev)  
**Branch:** `refactor/modular-monolith-datomic`  
**Data:** 2026-10-17  
**Escopo M2:** schema versionado + API aplicação + testes fictícios. Sem migração Cassandra, sem WhatsApp/deploy/flags. Ver `docs/m2-pokemon-datomic.md`.

---

## 1. Mapa do repositório atual

### 1.1 Processos e entradas

| Processo | Runtime | Entrada | Persistência |
|---|---|---|---|
| Odisseu (bot WhatsApp) | Node.js + ClojureScript (`shadow-cljs` → `target/main.js`) | `zapbot.core/main` + `whatsapp-web.js` / Puppeteer / Chromium | Cassandra via `zapbot.armazenamento` (módulos não-Pokémon + adaptador HTTP Pokémon) |
| Pokémon service | Node.js + ClojureScript (`pokemon-service` → `target/domain.cjs` / HTTP) | `pokemon-service.entry` | Cassandra + runtime CJS canônico (trainer fence, saves, intents) |
| Scripts one-shot | Node/CLJS | `migrar-estado`, `resetar-pokemon`, harnesses Cassandra | Cassandra descartável ou legado |

Não há Clojure/JVM de runtime hoje. Java aparece só no estágio de build (`default-jre-headless` no Dockerfile para compilar shadow-cljs).

### 1.2 Fronteira WhatsApp ↔ Pokémon (já existente)

Hoje o bot **não** embute o domínio Pokémon completo no processo Chromium quando o HTTP está configurado:

- `zapbot.router` despacha `pokemon` / `loja` / `mochila` / … para `zapbot.pokemon-http`.
- `pokemon-http` monta contexto neutro (chatId, playerId, menções, citação) e chama o serviço via `scripts/lib/pokemon-http-client.cjs`.
- O serviço responde texto/mídia; o bot entrega via `whatsapp-web.js`.

Isso é o padrão a preservar: **transporte WhatsApp em Node**, **domínio/persistência fora do processo Chromium** — trocando Cassandra/CLJS do serviço por JVM + Datomic Local, sem microserviço público novo.

### 1.3 Módulos de comando (router)

| Domínio proposto | Comandos / namespaces atuais | Persistência |
|---|---|---|
| `shared/whatsapp` | `core`, `whatsapp_reply`, `whatsapp_saude`, patch de mídia | sessão `.wwebjs_auth/` |
| `shared/config` + `observability` | `config`, `desempenho`, `recursos`, `status` | env / métricas |
| `forca` / utilitários | piadas, curiosidades, notícias, cotação, previsão, horóscopo, filme, dicionário, traduzir, resumo, apuração, bola8, sorteio, música, moderação | em geral sem estado Cassandra (APIs externas) |
| `ia` | `pergunta`, `gemini`, `ia_identidade`, `traducao` | chaves de API; sem banco de domínio |
| `lembretes` | `lembretes` | Cassandra `lembretes` |
| `quiz` | `quiz` | memória + Cassandra `quiz-historico`; rank |
| `velha` | `velha` | memória; rank |
| `batalha-naval` | `naval` | memória; rank |
| `adedonha` | `adedonha` | memória; rank |
| `pokemon` | `pokemon-service` + stubs locais | Cassandra + canônico (trainer/saves/loja/raids/gym/adventure) |
| compartilhado | `rank`, `bloqueio`, `admins`, `bugs`, `historico`/`participantes`, `enquetes` | Cassandra |

### 1.4 Acoplamentos críticos

1. **`rank`** — placar compartilhado entre velha, naval, quiz, adedonha e PvP Pokémon. Não pode ser “dois writers” em processos distintos sem coordenação.
2. **`bugs` / `historico` / `bloqueio`** — atravessam WhatsApp e Pokémon (`pk bug`).
3. **Pokémon canônico** — fencing de save A/B, `LateralIntent`, loja por save, raids carimbados, ginásios, Adventure: trabalho local recente ainda ancorado em Cassandra LWT. Qualquer migração Datomic deve preservar essas garantias ou substituí-las por transações Datomic equivalentes **antes** de desligar Cassandra.
4. **Duplicação de código** — `src/zapbot/pokemon/*` ainda existe no monólito raiz além de `pokemon-service/` (legado / fallback). Planejar deprecação após o adaptador HTTP ser o único caminho.

---

## 2. Separação domínio / aplicação / infraestrutura

```
modules/<nome>/
  domain/         ; regras puras, entidades, invariantes (sem I/O)
  application/    ; casos de uso, orquestração, ports
  infrastructure/ ; Datomic adapters, HTTP, timers
  tests/

apps/odisseu/     ; composição: WhatsApp Node bridge + chamada aos módulos
shared/
  whatsapp/       ; cliente, entrega, sessão (Node)
  persistence/    ; API Datomic / ports
  observability/
  config/
```

**Regra:** domínio nunca importa `whatsapp-web.js`, Cassandra driver, nem Chromium.  
**Regra:** aplicação fala com ports (`Pedido`, `Resposta`, `Store`).  
**Regra:** infraestrutura implementa ports (Datomic Local no JVM; bridge Node só no transporte).

---

## 3. Manter whatsapp-web.js sem microserviço público

### Desenho alvo (mesmo repositório, deploy coordenado)

```
┌─────────────────────────────────────────────────────────┐
│  VM / Compose                                           │
│  ┌──────────────────────┐   localhost IPC/HTTP interno  │
│  │ odisseu-node         │◄────────────────────────────► │
│  │ Chromium + wwebjs    │   ┌────────────────────────┐  │
│  │ router fino          │   │ odisseu-jvm            │  │
│  │ entrega de mídia     │   │ módulos + Datomic Local│  │
│  └──────────────────────┘   └────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

- **Sem porta pública** para o núcleo JVM (bind `127.0.0.1` ou socket Unix).
- Node continua dono da sessão WhatsApp e do QR.
- Comandos de domínio (Pokémon primeiro; depois quiz/lembretes/…) vão ao JVM.
- Comandos “thin” (piada, cotação) podem permanecer no Node temporariamente.

Isso é a evolução natural de `pokemon-http` → bridge genérico `domain-http` / EDN over HTTP interno.

---

## 4. Datomic Local no núcleo JVM

### 4.1 Papel

- Banco inicial **embutido** (sem cluster Cassandra na mesma VM a médio prazo).
- Transações ACID para saves A/B, economia, raids, ginásios e Adventure.
- Histórico / as-of útil para auditoria e recuperação (substitui parte dos journals manuais).

### 4.2 Integração

1. `deps.edn` na raiz (ou `apps/odisseu-jvm`) com `com.datomic/peer` + Datomic Local.
2. Processo JVM iniciado pelo Compose ao lado do Node.
3. Schema em `migrations/` (edn/tx-data versionado).
4. Ports Clojure: `(defprotocol DomainStore …)` implementado por `shared.persistence.datomic`.

### 4.3 O que **não** fazer no Datomic Local

- Não expor Datomic na internet.
- Não misturar peer embutido com tentativa de “cluster” sem redesenhar memória.
- Não migrar writers canônicos Cassandra em produção enquanto o dual-write não estiver validado em harness descartável.

---

## 5. Memória na VM — risco principal

Perfil documentado (~2 vCPU, ~1–2 GB RAM, swap, Chromium + Cassandra heap 1 G):

| Componente | Ordem de grandeza | Nota |
|---|---|---|
| Chromium / Puppeteer | dominante | já é o gargalo (`docs/recursos-chromium.md`) |
| Node Odisseu | moderado | |
| Cassandra 5 (hoje) | ~1 G heap configurado | concorrente direto com Chromium |
| JVM + Datomic Local | **novo** | heap + index + object cache |

**Risco:** Node + Chromium + JVM + Datomic **na mesma VM pequena** pode esgotar RAM e matar o Chromium (perda de sessão WhatsApp).

**Mitigações obrigatórias no plano de deploy:**

1. **Não** rodar Cassandra e Datomic simultaneamente em produção por longo período (janela curta de dual-write só com RAM medida).
2. Heap JVM conservador (ex.: `-Xmx256m`–`512m`) com smoke test de carga Pokémon.
3. Preferir **desligar Cassandra** após cutover, não empilhar os dois.
4. Medir RSS antes/depois com `ODISSEU_RESOURCE_METRICS` / status.
5. Se a VM for ≤2 GB efetivos, considerar: (a) JVM só para Pokémon e Node thin; ou (b) VM maior antes do cutover.

---

## 6. Modelo transacional alvo (saves A/B e afins)

### 6.1 Partição lógica de dados

| Classe | Exemplos | Escopo de identidade |
|---|---|---|
| **Global** | espécies, catálogo Adventure, regras de golpe | imutável / raro |
| **Por jogador × save** | equipe, PC, moedas/inventário do save, ovos do save, fence | `[chat-id player-id save-id]` |
| **Por jogador (conta)** | ponteiro `active-save`, geração/fence, intents laterais | `[chat-id player-id]` |
| **Compartilhado pelo grupo** | raid ativo do chat, ocupação de ginásio, rank do chat | `[chat-id]` (+ ids de entidade) |
| **Sessão / efêmero** | batalha PvP em memória, quiz aberto | processo; regenerável |

### 6.2 Transação Datomic (substituto do LWT Cassandra)

Uma transação por caso de uso atômico, por exemplo:

- **Ativar save B:** assert `active-save=A` + sem intent bloqueante + sem raid carimbado A → set `active-save=B`, bump fence.
- **Compra na loja:** assert fence/save + debitar economia do save + gravar recibo idempotente (`operation-id`).
- **Entrar em raid:** assert save ativo + carimbar participação com `save-id`/`fence`.

O protocolo `LateralIntent` / `write_lease` Cassandra vira **atributos + predicados na mesma transação Datomic** (não precisa de LWT cross-partition se o efeito e o fence vivem no mesmo DB).

### 6.3 Idempotência

Manter `operation-id` / effect receipts como entidades com unique identity — espelha `operation_receipts` e intents atuais.

---

## 7. Migração Cassandra → Datomic

### 7.1 Princípios

- **Preservação integral:** nenhum delete de Cassandra até validação + retenção de backup.
- **Harness descartável** primeiro (padrão já usado nos testes `*-cassandra.sh`).
- **Sem dual-write longo** na VM pequena.
- Testes Cassandra existentes **permanecem** até o cutover e por um período de regressão.

### 7.2 Fases

| Fase | Ação | Critério de saída |
|---|---|---|
| M0 | Este documento + aprovação | Go/no-go |
| M1 | Scaffold `deps.edn` + dirs + JVM hello (sem domínio) | Boot JVM < limite de RAM |
| M2 | Schema Datomic mínimo + import dry-run de um módulo pequeno (`lembretes` ou `bloqueio`) | Round-trip idêntico |
| M3 | Ports + bridge Node↔JVM (espelho de `pokemon-http`) | Comando thin via JVM |
| M4 | Pokémon: import trainer/loja read-only + shadow writes em harness | Diff zero nos snapshots |
| M5 | Cutover Pokémon (JVM writer único) | Flags off até certificação |
| M6 | Demais módulos; desligar Cassandra | Backup retido |

### 7.3 Backup / validação / rollback

1. **Backup:** snapshot CQL (`nodetool snapshot` / export JSON por módulo) + cópia do volume `cassandra-data` antes de qualquer cutover.
2. **Validação:** hash canônico por entidade (ex.: `state_hash` atual) comparado pós-import; harness de A→B, loja, raid.
3. **Rollback:** Compose volta a apontar Node/serviço para Cassandra; Datomic Local fica offline. **Não** apagar volume Cassandra na mesma janela.
4. **Flags:** nenhuma flag de cutover ligada sem aprovação explícita (alinhado às restrições atuais do trabalho Pokémon).

---

## 8. Sequência incremental de módulos (sem quebrar comandos)

1. **Scaffold** (reversível, zero comportamento).
2. **Bridge genérico** Node → JVM (ainda pode fazer proxy para pokemon-service atual).
3. **`lembretes` ou `bloqueio`** — domínio pequeno, bom piloto Datomic.
4. **`quiz` / `velha` / `naval`** — estado em memória; só rank precisa de cuidado.
5. **`pokemon`** — maior risco; só após schema de save/fence e import validado.
6. **`ia` / utilitários** — podem ficar no Node por mais tempo.
7. Remover duplicatas `src/zapbot/pokemon/*` quando HTTP/JVM for exclusivo.

---

## 9. Riscos Node + Datomic Local no mesmo deploy

| Risco | Impacto | Mitigação |
|---|---|---|
| Pressão de RAM (Chromium + JVM) | Queda do WhatsApp / OOM | Heap baixo; não empilhar Cassandra; medir |
| Dois writers (Cassandra + Datomic) | Corrupção / perda | Writer único por módulo; dual-write só em harness |
| Ordem de boot | Comandos falhando no warm-up | Healthcheck JVM antes de aceitar domínio |
| Sessão WhatsApp vs restart JVM | Reinício desnecessário do Node | Processos separados; reiniciar só JVM |
| Complexidade operacional | Deploy frágil | Um Compose, dois serviços, rede interna |
| Paridade de testes | Regressão silenciosa | Manter testes Cassandra até M6; adicionar suite Datomic paralela |

---

## 10. Primeira mudança pequena e reversível (recomendação)

**Não migrar dados ainda.**

### Mudança M1 (após aprovação)

1. Criar árvore vazia versionada:
   - `apps/odisseu/` (README de composição)
   - `modules/pokemon/{domain,application,infrastructure,tests}/`
   - `shared/{whatsapp,persistence,observability,config}/`
   - `migrations/`, `deploy/`
2. Adicionar `deps.edn` mínimo com Datomic Local **sem** ligar ao bot.
3. Script `scripts/smoke-datomic-local.sh` que sobe um peer embutido, faz uma transação trivial e encerra (mede RSS).
4. **Não** alterar `Dockerfile` de produção, `docker-compose.production.yml`, flags, nem `pokemon-service` writers.

**Rollback:** apagar os diretórios/scripts; nenhum caminho de runtime depende deles.

**Por que esta primeiro:** valida JVM+Datomic na máquina de desenvolvimento e estima memória **antes** de tocar domínio ou Cassandra.

---

## 11. O que fica fora até aprovação

- Rewrite de módulos
- Migração real Cassandra → Datomic
- Habilitar flags / certificações
- Remover testes Cassandra
- Commit, push, deploy
- Descartar alterações locais (fencing/loja/intents)

---

## 12. Decisão solicitada

Aprovar **M1 (scaffold + smoke Datomic Local)** para a próxima execução?  
Alternativa mais conservadora: apenas este documento, sem criar arquivos de scaffold ainda.
