# Evidência sandbox AMD64 nativo — RC gameplay `e7488e4f`

- Host: `odisseu-core` (`x86_64`, Docker Engine `amd64` 29.9.1)
- Image ID: `sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79`
- Tag: `odisseu-node:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64`
- Commit: `21771ec372eb2e6623f67a8e3ea2f5c334dd4006`
- Seccomp profile SHA-256: `4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10`
- Método: `scripts/test-chromium-sandbox.cjs` com volumes descartáveis, `--network none`, UID 1000, `cap_drop=ALL`, `no-new-privileges`, sem sessão WhatsApp
- Produção: containers `odisseu-a6-node` / `odisseu-a6-jvm` **não** recriados; imagem candidata removida após o ensaio
