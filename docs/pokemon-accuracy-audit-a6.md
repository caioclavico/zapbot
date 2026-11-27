# Auditoria de precisão dos golpes

## Regra atual

Na caça, no PvP e no ginásio, o motor usa a regra própria do Odisseu:
para estágio `s`, `M(s) = (2+s)/2` se `s >= 0`, ou `2/(2-s)`
se `s < 0`. A chance é `clamp(1, 100, round(base × M(precisão) /
M(evasão)))`, em pontos percentuais inteiros. Um sorteio uniforme em
`[0, 100)` falha quando é maior ou igual à chance. Com base 95 e estágios
zerados, 95 dos 100 resultados acertam.

Esta curva é a regra já adotada pelo jogo, não a curva de estágios da série
principal. Um golpe sem precisão registrada usa base 100 quando há estágios,
para compatibilidade com saves legados. Golpes marcados como infalíveis ignoram
esses estágios. Golpes de status em si e proteção não usam a precisão do
atacante nem a evasão do oponente. Trovão e Furacão na chuva e Nevasca na neve/granizo também
ignoram a verificação de precisão; no sol Trovão/Furacão usam base 50.
O menu identifica a precisão como **base**; clima e estágios podem alterá-la.

As 17 precisões explícitas de `move-effects.json` e as 176 de
`move-mechanics.json` foram comparadas com o
[CSV público da PokéAPI](https://github.com/PokeAPI/pokeapi/blob/master/data/v2/csv/moves.csv):
nenhuma divergência ou slug ausente. Essa comparação não verifica valores de
saves individuais, que podem ser antigos.

Defender acrescenta uma esquiva independente após o acerto: `min(50,
floor(velocidade efetiva / 2))%`. Proteção bloqueia antes do sorteio;
imunidade, alvo inalcançável e impedimento por status são resultados distintos.
A raid não simula estágios, clima ou golpes completos do chefe; o revide é uma
regra própria. Agora também verifica precisão ao liberar golpes carregados.
O Raid Auto incorpora essa chance ao estimar o dano de golpes carregados.

As habilidades implementadas não alteram a precisão; não há suporte geral a
habilidades de precisão/evasão. Esta auditoria não afirma paridade completa com
as mecânicas da série principal.

## Correções

- O RNG de produção de `pokemon.domain.rng` usa `ThreadLocalRandom.nextLong(n)`,
  com sorteio inteiro limitado sem o viés de discretização de `rand-int` sobre
  um `double`. O mapa injetável continua disponível para testes. Não é um RNG
  criptográfico.
- As rotas de batalha, PvP e raid rejeitam explicitamente RNG injetado quando
  `ODISSEU_BRIDGE_ALLOW_TEST_RNG` está desabilitado.
  A configuração efetiva de produção não foi consultada.
- O recibo de raid registra o resultado da precisão e o identifica no retry.
  Recibos antigos sem essa informação não permitem recuperar o motivo.
- A compactação preserva a precisão explícita 100; o DTO de combate usa a
  precisão do mesmo catálogo aplicado pelo motor, inclusive quando o catálogo
  aprovado sobrepõe o valor salvo.
- Falha de golpe de nocaute por nível ou imunidade não é descrita como erro de
  precisão. A regra de nível e a imunidade não foram alteradas.
- Golpes de status em si e proteção deixaram de usar a evasão do oponente.
- Na chuva/neve/granizo, os golpes com acerto garantido pelo clima agora
  ignoram os estágios, em vez de tratar o efeito como mera base 100.

## Amostra local

Em uma execução isolada com 100.000 tentativas por cenário, obtivemos:

| Cenário | Acertos |
|---|---:|
| Base 100% | 100,000% |
| Base 95% | 94,982% |
| Base 90% | 90,121% |
| Base 85% | 84,957% |
| Base 75% | 75,047% |
| Base 50% | 49,958% |
| Precisão +1, base 75 | 100,000% |
| Precisão -1, base 75 | 50,131% |
| Evasão +1, base 95 | 62,832% |
| Evasão -1, base 95 | 100,000% |
| Trovão na chuva, com e sem evasão +6 | 100,000% |
| Nevasca na neve com evasão +6 | 100,000% |
| Trovão no sol | 49,841% |
| Infalível com evasão +6 | 100,000% |
| Sem precisão e sem modificadores | 100,000% |
| Base 95 e defesa com esquiva 20% | 76,047% |

O teste aceita até seis desvios-padrão binomiais; cenários certos exigem
100.000 acertos. Proteção bloqueou 100.000 ataques sem sortear precisão.
Em 200.000 sorteios, a distribuição por valor e a comparação de pares
consecutivos passaram os limites definidos; houve 28 ocorrências de três ou
mais erros consecutivos, incluindo sobreposições. Testes estatísticos não
provam independência absoluta ou imprevisibilidade criptográfica.

Para um grupo fixo de três tentativas independentes com 95%, a probabilidade
de três erros é `0,05³ = 0,0125%` (1 em 8.000); quatro são 1 em 160.000.
Em muitas tentativas há muitas oportunidades para essas sequências. Não há
compensação artificial de azar.

## Produção e telemetria

Nenhuma base de produção foi consultada ou alterada. Os registros históricos
não foram examinados; o estado atual guarda a última ação e os recibos antigos
de raid não registram o motivo de erro, a precisão efetiva ou os modificadores.
Portanto não se pode inferir a taxa real das reclamações a partir desta amostra.

Para investigação futura, propor métricas agregadas por modalidade, slug,
precisão base/efetiva, faixas de modificadores e resultado distinto
(`acerto`, `precisao`, `esquiva`, `protecao`, `imunidade`, `status`,
`inalcancavel`), com contadores de sequências agregados em memória. Não
registrar jogadores, chats, IDs de operação, tokens ou valores de RNG.
Definir retenção, limiar mínimo de agregação e controle de acesso antes de
ativar qualquer telemetria; esta proposta não foi implementada.

O PR #137 segue em homologação. Não fazer merge nem deploy antes da CI no HEAD
final e dos gates operacionais; preservar o hold de produção.
