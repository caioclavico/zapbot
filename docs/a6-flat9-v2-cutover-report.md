# Odisseu A6 — Cutover flat-9-v2 (PR #86) — DEPLOY PASS

**Status:** **DEPLOY PASS**  
**GO:** usuário (deploy PR #86 + reconciliação dos 18)  
**Commit implantado:** `60dc18b633bf1538f252f1cd6ddf8dcb480e2574`  
**Janela:** `20261113T222458Z`  
**Evidência host:** `/home/caiohclavico/odisseu-recovery/a6-flat9-cutover-20261113T222458Z`

## Imagens efetivamente implantadas

| Serviço | Tag | Digest |
|---|---|---|
| JVM | `odisseu-jvm:a6-flat9-v2-20261113T222458Z-60dc18b633bf-amd64` | `sha256:6ae42c149d037bf6b789dff6858cb632cce2e598857cd0835ee11115bc46a056` |
| Node | `odisseu-node:a6-flat9-v2-20261113T222458Z-60dc18b633bf-amd64` | `sha256:77505276b1029f12ac1712009cfede9cceeb4cbaa5e89de6b65bfa2315c0ac18` |

Rollback pinado (não usado): JVM `a6-pr85-…5f3b716…` / Node `a6-pr80-…8b05886…`.

## Writer único / manutenção

- Writer Datomic live: somente `odisseu-a6-jvm`.
- Drain Node → JVM quiescent → freeze → stop (sem `-v`).
- Pós-cutover: JVM `open`, Node `open`, WhatsApp **READY**, Chromium true.

## Cold fresco

| Item | Valor |
|---|---|
| Path | `…/a6-cold-live-private/cold-flat9-cutover-20261113T222458Z` |
| Verify | OK (`logical_bytes` 44 363 495, 4 volumes) |
| `manifest.json` SHA-256 | `30d32559b88c618167318c48778a65b452e7b4d9f3612062e36e726331493451` |
| Restore isolado | `odisseu-a6-restore-flat9cut-20261113T222458Z-*` — FP Pokémon idêntico ao cold |

## Preflight dos 18

- Contagem live: **18** (fingerprint inventário `e250d392…`).
- IDs = manifesto esperado; **0 drift**; **0 UNRESOLVED**.
- Reparados #85 preservados: Blastoise **74**, Tyranitar **90**, Crobat **5**, Amaura **4**.

## Reconciliação live (volumes parados)

| Gate | Resultado |
|---|---|
| Dry-run | 18× dry-applied, level-changes **0** |
| Apply | 18× applied, level-changes **0** |
| Retry | 18× skipped (`receipt-exists`) |
| Ambíguos pós | **0** |

### Fingerprints

| Fase | poke-level | coins | receipts | unversioned≥9 | Pokémon FP |
|---|---:|---:|---:|---:|---|
| Baseline (cold/restore) | 2231 | 3691 | 4297 | 18 | `25ca62673467afd0cfde1bd104fad5df0180a0fe18914a6abad8f90eb08af0a7` |
| Após recon | 2231 | 3691 | 4315 (+18) | **0** | `dbbf973258246a3920df61372a8858a9e6690925cea147fd4276103b2ce4e711` |
| Pós-open (RO) | 2231 | 3691 | 4316* | 0 | `dbbf9732…` (Pokémon) |

\* +1 recibo operacional após reopen (não de reconciliação).

### 18 recibos

```
flat9-ambig-recon:0424fa9b-ef8a-4c9b-9fd1-6fde8784abed
flat9-ambig-recon:06b97c0b-8a36-4a73-a15a-5b8da8282e2f
flat9-ambig-recon:26c67e61-e9f4-4ff9-931a-2d87ed2232d1
flat9-ambig-recon:2a9bb4ab-0116-4411-beee-96ee4e40da3f
flat9-ambig-recon:2f8c57cc-0c5d-4e84-bebd-3f1d677b4de2
flat9-ambig-recon:39ed98d8-8e4e-4ab5-9944-3691f5a712b1
flat9-ambig-recon:4fe5edcf-dd07-488e-8060-88a8c595632f
flat9-ambig-recon:54bc5eb2-7329-452b-825f-880dac1e3fdd
flat9-ambig-recon:65b9bc87-839f-4b87-9995-dac171689749
flat9-ambig-recon:7dd125ba-ed81-4e32-97e6-2646fe3d7539
flat9-ambig-recon:84f86044-cb15-4046-9434-820d8a2d4585
flat9-ambig-recon:def03dda-19a3-4454-a948-4e3cb179e93f
flat9-ambig-recon:e68c3724-024a-4c39-94b3-b5e040bb0213
flat9-ambig-recon:ea143a44-7306-4f7c-a55d-b8d1ad360daa
flat9-ambig-recon:f1078268-35d6-46f4-8955-07c1ee18f1e9
flat9-ambig-recon:f989fba9-70bd-4943-a497-1a401ee8e7ef
flat9-ambig-recon:fae80690-a56a-4ec8-8d22-3d1a315df011
flat9-ambig-recon:fe9fd2ab-bc81-492b-b098-02648e22a98f
```

## Gates pós-reopen

| Gate | Status |
|---|---|
| JVM healthy + maintenance `open` | PASS |
| Node WhatsApp READY / chromium | PASS |
| Ambíguos unversioned residual≥9 | **0** PASS |
| Σ níveis / coins / saves / inv / eggs | inalterados PASS |
| Blastoise/Tyranitar/Crobat/Amaura | 74/90/5/4 PASS |
| Staryu live `flat-9-v2` L13 resid 6 | PASS |
| Simulação XP +3→L14/0; +1→L14/1 | PASS |
| Caps hunt/raid/evolve/battle | PASS |
| PR #84 / #83 | **não incluídos** |
| Reparo histórico #85 | **não reexecutado** |

## Notas operacionais

1. Cold inicial falhou com buildkit ativo; buildkit removido e cold refeito com sucesso antes do apply.
2. Baseline dirty-copy pré-freeze ficou 1 recibo atrás do cold (4296 vs 4297); baseline canônico = fingerprint do restore/cold.
3. Manifesto privado fora do git: `/tmp/flat9-cutover-manifest.edn` (+ durable em `a6-cold-live-private/flat9-ambig-private-cutover`).

## Veredito

**DEPLOY PASS** — flat-9-v2 em produção no tip `60dc18b`, 18 ambíguos reconciliados com recibos únicos, zero Δ de nível, economia/saves íntegros, serviços abertos com WhatsApp READY.
