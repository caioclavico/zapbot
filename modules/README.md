# modules/

Domínios do monólito modular. Cada módulo segue:

```
<nome>/
  domain/           ; regras puras (sem I/O)
  application/      ; casos de uso / ports
  infrastructure/   ; adapters (Datomic, HTTP, timers)
  tests/
```

M1: apenas scaffold vazio. Código de domínio permanece em `src/zapbot/` e
`pokemon-service/` até migrações posteriores.
