# A6 — Balanceamento da captura selvagem e sequências longas

**Status:** investigação e proposta, não implementado. Sem merge/deploy/migração.

## Sintoma

Relato de sequência de ~50 capturas seguidas. Isso não comprova RNG quebrado: com chance efetiva 95%, a probabilidade de 50 sucessos seguidos é ~7,7% (tentativas independentes). Porém, há facilidade excessiva para comuns.

## Fórmula live observada (`modules/pokemon/domain/pokemon/domain/capture.clj`)

- Base por raridade (Pokébola): comum 78% (faixa 70–88), incomum 58 (48–68), raro 28 (18–38), épico 14 (8–22), lendário 5 (2–9), mítico 3 (1–6).
- `chance-base-pos-batalha`: status multiplica base (×1,5 a ×2); `seq-bonus=min(10, sequencia)` acrescenta até +10 pp de chance.
- Bola Grande ×1,5, Ultra ×2, Frambo ×1,5, limite final **95% por bola**.
- `attempt-capture` usa roll 0..99 e três tentativas máximas até fuga (ou antes por raridade).
- A política de **recompensas** por sequência `streak-unit-v1` existe separadamente. Preservar streak/insígnias/recompensas e histórico; mexer somente no efeito da sequência na **probabilidade**.

## Proposta de balanceamento (requer implementação/testes e aprovação)

1. **Remover bônus de chance por streak**: `seq-bonus = 0`. Continuar registrando, mostrando e recompensando sequências, sem efeito de bola de neve na taxa de sucesso.
2. Reduzir *comum* de centro 78 (70–88) para centro **62%**, faixa **55–70%**; *incomum* de 58 (48–68) para **48%**, faixa **40–58%**. Conservar demais raridades na primeira fase.
3. Reduzir cap final após bola/frambo de 95% para **90%** (não criar capturas garantidas); manter status, Grande/Ultra e Frambo úteis. **Cuidado:** testes que exigem 95 precisam ser ajustados.
4. Não alterar taxa de fuga, janela de três tentativas, drops, XP, PE, PI, economia, raids, ginásio e PvP nesta alteração. Verificar se helpers de captura são compartilhados com raid; usar ajuste somente na caça selvagem ou provar que raid mantém suas chances próprias.
5. Auditar telemetria observada por raridade/bola/status/frambo (sem expor dados pessoais). Distinguir *chance da tentativa*, *chance de capturar em até 3 tentativas* e distribuição por espécie/bioma.
6. Testes determinísticos: sem bônus de probabilidade para streak 0/1/10/50/100, cap, raridade, status, bola/frambo, seeded RNG, falha/fuga, tentativas, idempotência de recibos, preservação da contagem de sequência.
7. Simular amostras com seed fixo, comparar taxa teórica e empírica; rodar CI + ensaio isolado, sem tocar saves live.

### Observação importante

Mesmo a 70% por tentativa, até três chances independentes (se não houver fuga antes) produzem probabilidade acumulada de 97,3% em um único encontro. Portanto, a implementação precisa medir e exibir claramente *captura por tentativa* versus *captura por encontro* e garantir que a mecânica de fuga não se torne trivial.

## Gates

Entregar resultados por raridade/bola e status; sem regressões em captura de raid, badges/streak, RNG e eventos; nenhum merge/deploy sem GO separado.
