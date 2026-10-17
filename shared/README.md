# shared/

Infraestrutura compartilhada entre módulos:

- `whatsapp/` — transporte (permanece Node/`whatsapp-web.js` por enquanto)
- `persistence/` — ports e adapters Datomic (JVM)
- `observability/` — métricas / logs
- `config/` — configuração JVM

M1: diretórios vazios + smoke que usa Datomic Local diretamente.
