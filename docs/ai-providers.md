# Provedores de IA do Odisseu

Gemini continua principal, usando exatamente o `GEMINI_MODEL` configurado. A Groq é opcional: sem `GROQ_API_KEY`, nenhuma requisição é feita à Groq. Todos os comandos que usam a fachada de IA recebem a mesma identidade Odisseu e o mesmo conteúdo/contexto enviado ao Gemini, incluindo resumos e formatos JSON de jogos.

| Variável | Padrão | Uso |
| --- | --- | --- |
| `GEMINI_API_KEY` | vazio | Chave principal já existente |
| `GEMINI_MODEL` | valor já existente | Modelo Gemini preservado |
| `GROQ_API_KEY` | vazio | Habilita o fallback |
| `GROQ_MODEL` | `llama-3.1-8b-instant` | Modelo da Groq |
| `AI_REQUEST_TIMEOUT_MS` | `8000` | Limite por tentativa, incluindo leitura do JSON |
| `AI_TOTAL_TIMEOUT_MS` | `30000` | Limite total, incluindo esperas e fallback |
| `AI_RETRY_BASE_MS` | `1000` | Backoff exponencial de 1s e 2s |

Não salve chaves no Git. Configure-as no ambiente do processo do bot; nenhuma credencial é necessária para os testes.

São feitas até três tentativas Gemini (a inicial mais duas novas) em HTTP 429, 500, 502, 503 e 504; falhas de rede e timeout também podem ser repetidas dentro do prazo. A espera é o maior valor entre o backoff e `Retry-After`, aceitando segundos e data HTTP. Se a espera não couber no prazo, o Gemini não é chamado antes do permitido: o bot passa à Groq. Erros permanentes, JSON inválido e respostas vazias seguem diretamente para fallback.

Com Groq habilitada, parte do prazo total fica reservada para uma tentativa secundária. Cada chamada usa AbortController e um timer, cobrindo também um corpo de resposta que trave. O bot retorna `nil` se ambos falharem; perguntas e resumos apresentam um aviso curto de indisponibilidade temporária. Jogos e piadas preservam seus fallbacks locais existentes. Tradução continua tentando o provedor de tradução anterior antes da fachada de IA.

Os logs JSON contêm provedor, modelo, tentativa, latência, status HTTP e categoria de erro. Não incluem corpo de erro do provedor, stack/mensagem de exceção, prompt, resposta ou chave. O envio WhatsApp e a normalização do ID citado permanecem no fluxo existente.

## Verificação

- `npm run test:ai`: testes Node isolados com respostas simuladas, inclusive falhas, ausência de chave, prazos e privacidade dos logs.
- `npm test`: inclui a suíte Node de IA e os testes existentes.
- `npm run build`: compilação de produção do bot.

Referência da API Groq: https://console.groq.com/docs/api-reference. Não foram alterados serviço Pokémon, Cassandra ou infraestrutura de deploy.
