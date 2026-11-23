# Análise — `raid-defeat-rewards-fase1283` (risco residual #126)

## Escopo

Investigação pedida na homologação fase 2. **Sem alteração de produção.**  
Comparação: master `741034c` vs baseline produção `c4deb6a`.

## Reprodução

Ambiente: Datomic Local `.datomic-local/raid-defeat-1283`, host do engenheiro.

| Ref | Resultado |
|-----|-----------|
| `741034c` (master) | 15 tests · 71 pass · **6 fail · 1 error** |
| `c4deb6a` (prod base) | **Mesmo padrão** (worktree isolado) |

Conclusão: **não é regressão do hotfix #126**.

## Falhas e causa

### 1. `vitoria-preserva-recompensas-anteriores` (FAIL)

- Fixture: `mon-vitoria` (Snorlax, Body Slam **normal**) × `chefe-fraco` (Onix **rock**, hp declarado 100).
- Runtime: HP do chefe na abertura = `hp-por-jogador × n × fator` (`raid.clj`, Proposta A: **320**/jogador).
- Com 2 jogadores → HP efetivo ≈ **640**, não 100.
- Dano medido de Body Slam vs rock ≈ **45**/golpe; revide = `ceil(200/5)=40`.
- Capacidade de sobrevivência ≈ 5 golpes/mon → ~10 golpes no time ≈ 450 < 640 → **derrota**.
- Asserções esperavam `vitoria` / moedas 50 / PE 6; receberam `derrota` / PE 3.

**Classificação:** fixture de teste **desatualizada** após escala 220→320 (e STAB/tipo).  
O próprio `raid_capture_rewards_fase126_test` documenta que Body Slam vs Onix falha e usa Brick Break.

**Impacto em jogadores:** nenhum evidenciado — caminho de vitória coberto por outras suítes com golpe adequado. Recompensas de derrota (PE 3) batem com o resultado real do fixture.

### 2. `pokemon-sobe-nivel-na-derrota` (FAIL)

- Esperava `xp-desde-nivel = 1` após +3 XP oficiais a partir de `span-2`.
- Obteve `2`.
- Nível 5→6 ainda sobe (`nivel = 6` não falhou na corrida observada quando asserção de residual falha primeiro — revisar se ambas rodam).

**Classificação:** asserção de residual **desalinhada** da curva/growth atual ou do grant efetivo — precisa auditoria pontual do XP aplicado na TX de derrota, **sem** “forçar verde”.

### 3. `retry-finalizacao-nao-duplica` (ERROR)

- Exceção não capturada: `raid_not_found` em `bridge-tx/raid-attack!`.
- Sugere corrida no harness (dois ataques finais) / ponteiro de raid limpo antes do segundo attempt.

**Classificação:** harness de concorrência frágil / expectativa de erro não tratada no teste.

## Impacto operacional

| Pergunta | Resposta |
|----------|----------|
| Jogadores deixam de receber PE de derrota? | Não indicado — cenários de derrota dedicados passam |
| Vitórias reais quebradas? | Não pelo #126; fixture de vitória deste arquivo é que está obsoleta |
| Bloqueia deploy do #126? | Não como regressão do hotfix; residual de qualidade de suíte |

## Proposta (PR separado, sem merge automático)

1. Atualizar `mon-vitoria` para golpe **Lutador** (Brick Break), alinhado a `raid_capture_rewards_fase126_test`, **ou** reduzir n/HP do chefe de forma explícita documentando 320×n.
2. Recalcular asserção de `xp-desde-nivel` com a fórmula canônica atual + evidência do recibo.
3. Tornar o teste de retry assertivo sobre `:raid_not_found` / `:stale` em vez de exceção não tratada.

Não “silenciar” asserções sem ajustar o modelo de balanço documentado.

## Follow-up (PR #127 — aberto, sem merge)

Branch: `fix/a6-raid-defeat-fase1283-fixtures` @ `14773e2`  
URL: https://github.com/caioclavico/zapbot/pull/127

| Mudança | Status |
|---------|--------|
| `mon-vitoria` → Brick Break | Aplicada |
| Retry captura `Exception` / `raid_not_found` | Aplicada |
| `xp-desde-nivel = 1` mantida (honesta) | Sem green-wash |

**Reexecução pós-PR:** 15 tests · **80 pass · 1 fail · 0 error**  
Falha residual: `pokemon-sobe-nivel-na-derrota` (`expected 1`, `actual 2`).  
Auditoria growth-v1 / grant efetivo fica para PR de domínio separado se confirmado bug.
