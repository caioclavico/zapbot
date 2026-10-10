# Riscos, segurança e privacidade

Análise atualizada na Fase 2 em 10/10/2026. Domínio e persistência foram
implementados e testados localmente, sem consulta de produção, leitura de
segredos, commit ou deploy. Mitigações de autenticação/mobile permanecem futuras.

## Decisões e limites da implementação da Fase 2

Não foram introduzidos IVs, coleta de saúde, endpoints ou comandos. A distância
é uma entrada **interna confiável já validada**, não evidência de sensores.
Receipts impedem replay do mesmo ID; não provam que dois IDs distintos
representem caminhadas distintas. Essa validação pertence à futura integração.

Na Etapa A, a distância foi retirada da mecânica de produto: o núcleo puro usa
tempo real e pontos de atividades. A integração com captura, PvP, comandos,
notificações e Cassandra ainda não foi alterada e continua sujeita às mesmas
barreiras de flag, CAS e único writer.

O recurso fica desabilitado por padrão. Habilitá-lo futuramente exige instalar
e verificar o schema aditivo, analisar RF/DC e concluir pendências antes de
readiness. Não se executou nenhuma instalação/migração em produção.

O estado limita payload a 512 KiB e preserva receipts de concessão/incubação;
uma conta muito antiga pode atingir backpressure e exigir uma estratégia
aprovada de compactação. Receipts de entrega não expiram, inclusive após
doação/remoção do Pokémon. O histórico é paginado e não contém IVs novos.

A garantia sobre coleção legada pressupõe um escritor Pokémon e uso da fila
autoritativa; não autoriza escala horizontal. Rollback binário depois de
habilitar o recurso exige imagem compatível com receipts/gates. Desabilitar a
flag não substitui reconciliação de CAS incerto. Testes locais não atestam
replicação, capacidade, disponibilidade ou autenticação da produção.


## 1. Fronteiras de confiança

| Entrada/ativo | Ameaça | Mitigação proposta |
|---|---|---|
| Token interno Odisseu → Pokémon | Extração de segredo dá acesso a comandos de outros jogadores | Nunca embarcar `API_TOKEN` no app; rotas e credenciais separadas; API interna privada |
| Código temporário | Adivinhação, foto/código vazado ou vínculo com grupo errado | 8 caracteres aleatórios, HMAC no banco, TTL/prazo de 10 min, rate limit e confirmação privada do autor/contexto original |
| Código de app conhecido pelo atacante | Tomada de conta antes da aprovação | deviceCode de alta entropia + PKCE, consumo CAS e confirmação WhatsApp de fingerprint; número não autentica |
| Token móvel | Celular perdido, replay, backup/log vazando token | Access curto, refresh rotativo, armazenamento protegido, revogação e epoch no commit; sem Bearer em URL |
| Intervalos de saúde | Cliente modificado inventa passos/metros/origem | Validar formato/origem/limites; servidor calcula crédito; sem promessa de antifraude completo |
| Várias fontes/dispositivos | Duplicação de caminhada | Agregação/proveniência, coletor único, epoch sem sobreposição, intervalos substituíveis e watermarks duráveis |
| Cassandra timeout/crash | LWT pode ter aplicado apesar do erro | Resultado incerto, diário e releitura; sem retry aditivo/nova seed |
| Coleção legada | Snapshot paralelo perde dados ou hatch duplica após doação | Fila existente, escritor único, receipt no mesmo snapshot e guarda de persistência incerta |
| Notificação WhatsApp | Falha após envio e antes de receipt | Evento determinístico e dedupe atual; distinguir mensagem repetida de prêmio duplicado |
| API pública | Abuso de RAM/CPU, login e varredura de IDs | HTTPS, limites de corpo/itens, concorrência/custo, rate limit, dados próprios por token, erros genéricos |

Não tomar conta de outro grupo por `isAdmin`. Revogar/associar somente o próprio
vínculo; qualquer intervenção administrativa futura precisa política e trilha
explícitas, sem usar o admin de chat como administrador de dados de saúde.

## 2. Limites de antifraude

HealthKit/Health Connect são repositórios locais, não comprovantes assinados
destinados a um servidor de jogo. UUID, hash, assinatura do app ou origem
declarada não provam caminhada. Um telefone comprometido pode fabricar dados.
Attestation futura ajuda a identificar app/dispositivo, mas não certifica a
verdade de cada passo. Não bloquear permanentemente jogador só por anomalia.

MVP: recusar dados explicitamente manuais quando identificáveis, validar faixas,
limitar recompensa, impedir replay e mostrar rejeições privadamente. Produtores
podem marcar entradas manualmente criadas como método desconhecido; até a
política de origem ser demonstrada, preferir modo passos de origens permitidas.
Não assumir que `includeManualEntries: false` elimina toda fraude.
[Limitação descrita pelo mantenedor de health](https://pub.dev/packages/health).

Velocidade equivalente/teto diário não é orientação de exercício nem motivo
para sugerir esforço excessivo. Permitir atividade normal sem ranking público
de saúde. Não incentivar perseguição a metas médicas ou fazer alegações clínicas.

## 3. Consentimento e minimização

- Opt-in independente do jogo; recusar/desvincular não remove Pokémon nem
  desabilita comandos existentes. Explicar tipos lidos, destino servidor, uso
  para progresso, atraso, retenção, revogação e exclusão.
- Solicitar READ de passos; distância apenas para modo opcional comprovado.
  Background separadamente quando disponível. Sem GPS, rota, batimentos,
  contatos, peso, escrita em saúde ou histórico irrestrito.
- Cache local mantém IDs nativos, âncoras, alterações e fonte só pelo necessário
  à normalização; servidor recebe intervalos mínimos, não export de HealthKit.
- Mesmo passos agregados revelam hábitos e continuam sensíveis. UUID opaco,
  HMAC/hash e remoção de número **não tornam dados anônimos** quando ligados
  a uma conta do jogo.
- Progresso de ovos é informação de jogo; não expor horários/valores de saúde,
  dispositivo ou padrão de caminhada a participantes do grupo.
- Tokens, códigos, seeds, payloads e identificadores WhatsApp nunca em logs,
  analytics, relatórios de crash ou Git. ID opaco de operação e categoria de
  falha bastam para diagnóstico; PERFORMANCE_METRICS=false continua padrão.
- Transporte HTTPS e criptografia/proteção de banco/disco/backup precisam de
  verificação futura; não presumir que já estão configurados nas VMs. Sem
  desabilitar validação de certificado para testes de celular.

### Retenção proposta e exclusão

| Dado | Prazo inicial | Observação |
|---|---|---|
| Código/solicitação de vínculo | 10 min | Prazo explícito além do TTL; tentativa limitada |
| Resposta cifrada de troca/refresh | Até 60 s | Recuperar mesmo resultado, nunca logar token |
| Access / refresh | 15 min / até 30 dias | Revogação independe de expiração e remove capacidade de sync |
| Cache detalhado de saúde local/servidor | Até 7 dias | Purga controlada; reter watermark mínimo sem dados brutos |
| Recibos técnicos de sync | Até 14 dias | TTL não autoriza contabilizar períodos expirados |
| Histórico de nascimento | Enquanto houver finalidade de jogo, até pedido de exclusão | Não contém saúde detalhada; política a aprovar |
| Operações de entrega/dívidas/watermarks | Até recuperação/segurança contra replay permitir compactar | Não expirar uma intenção não entregue ou receipt necessário |
| Logs operacionais sem saúde | Proposta 14 dias | Política precisa incluir plataforma/logging e backups |

Desvincular revoga tokens e para novos uploads; excluir dados é operação
distinta e explícita. A exclusão futura deve cobrir cache, projeções e índices,
com prazo informado para backups e efeitos de tombstones. Não apagar dados do
Cassandra atual para cumprir pedido referente só à saúde. Guardar marcador
mínimo anti-replay deve ter finalidade, retenção e explicação próprias;
pendência de política é impedimento para liberar, não licença para reter tudo.
Revisar responsabilidades de tratamento e consentimento adequado ao público,
inclusive menores, antes de piloto; esta documentação não atesta conformidade.

## 4. Restrições de plataforma e publicação

### Android

Permissão de background/capacidade não garante que o SO execute um worker na
hora pedida. Health Connect pode não estar disponível ou ter dados; force-stop,
economia de bateria e política OEM interrompem coleta/sync. Recuperar em
primeiro plano. Não pedir exceção ampla de bateria como condição para jogar.
Releitura histórica tem limites, e reinstalação altera permissões; servidor
continua sendo a autoridade anti-replay.

Google Play requer declaração das finalidades/tipos e política de privacidade
compatível com Health Connect. Não solicitar mais dados que a funcionalidade
justifica. [Requisitos oficiais](https://developer.android.com/health-and-fitness/health-connect/publish).

### iOS

HealthKit pode não permitir leitura com o aparelho bloqueado. Observer não é
execução contínua; callback precisa terminar, e consulta ancorada serve para
recuperar depois. Não interpretar ausência de dados como prova de autorização.
Use dados/IDs locais mínimos e explique o atraso no app.

Há risco de **finalidade incompatível**: integrar HealthKit a um complemento
puramente de jogo não é aprovação automática. O app precisará demonstrar
propósito real de saúde/fitness, sem justificar coleta só com recompensas.
Avaliar a arquitetura contra [privacidade HealthKit](https://developer.apple.com/documentation/healthkit/protecting-user-privacy)
e [orientação de design](https://developer.apple.com/design/human-interface-guidelines/healthkit/).
As [diretrizes de revisão](https://developer.apple.com/app-store/review/guidelines/)
também tratam uso/divulgação de dados de saúde. Esta análise não conclui que
o projeto será aceito. Se não houver finalidade apropriada, suspender a
integração HealthKit e revisar o produto; não contornar permissões.

Direitos de marca/imagens Pokémon e distribuição nas lojas são outro requisito
de publicação a verificar. Não incluir assets do bot automaticamente no app
sem confirmar direitos/licenças. Sem monetização de dados de saúde.

## 5. Operação, concorrência e recursos

O histórico não deve ser outro módulo gigante hidratado em RAM. Até 7 dias de
intervalos ativos por conta, consultas paginadas, state limitado e cache LRU
limitado. CAS em lotes pequenos evita uma LWT por passo; uma conta ativa por
vez, orçamento móvel separado e baixo, preservando resposta de comandos.
Recibo/estado de autenticação não pode depender de cache desatualizado após
revogação. Limites iniciais serão aferidos em homologação, não presumidos.

A projeção pode falhar por tempo prolongado: diário/intenção ficam duráveis,
não compactados antes de recibo confirmado. Aplicar backpressure por conta e
negar novas operações se diário/payload atingirem limite, em vez de perder
recuperação ou crescer memória indefinidamente. Índice de outbox pode faltar
após crash; reparar por leitura da conta e varredura paginada do novo state,
com orçamento, sem examinar dados atuais do jogo em produção nesta fase.

Distância aprovada não é transação com coleção/eventos. Checkpoints e recibos
propostos precisam de revisão e testes de interrupção; desenho sozinho não
comprova ausência de duplicação. Não usar fila em memória como exclusão entre
duas réplicas. Não iniciar segundo escritor Pokémon contra snapshot legado.

Entregar apenas na fila do chat e, quando necessário, depois de combate/troca.
Não renumerar coleção, modificar estatísticas antigas ou incrementar captura
para simular nascimento. Não incluir IVs ou efeito competitivo novo nesta fase.

Shutdown conserva guarda de admissão, drain, diagnóstico JSON e timeout/falha
existentes. Adicionar operações novas ao drain; sem `process.exit(0)`, descarte
de promises ou aumento indiscriminado do prazo. Remoção de raids automáticas
permanece: nenhum timer Adventure Sync criará raids.

## 6. Critérios que impedem liberação

Não liberar se: vínculo puder ser confirmado por terceiro; API pública alcançar
`/commands` com token móvel; mesma caminhada/nascimento duplicar por retry;
revogação não for verificada no commit; rollback perder receipt; limite de RAM
estourar; fonte duplicada não tiver política demonstrada; secrets/saúde aparecerem
em logs; integração exigir apagar sessão WhatsApp; ou política de uso/retenção
e viabilidade das lojas estiverem indefinidas.

Faltam decisões do proprietário sobre produto/balanceamento, retenção,
consolidação e disponibilidade dos recursos nas plataformas. Também faltam
testes reais de cluster e telefones. Não foram inferidos resultados de produção
a partir de testes locais nem realizadas mudanças de infraestrutura.
