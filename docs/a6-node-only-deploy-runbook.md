# Runbook — deploy Node-only (A6) — NÃO EXECUTAR sem READY

Pré-condições obrigatórias (todas):

1. Veredito A = HOMOLOGADO no doc de segurança.
2. Veredito B = READY após decisão administrativa explícita dos riscos residuais.
3. Hold de produção ainda ativo até o momento consciente do cutover.
4. Imagem final referenciada por **digest/Image ID imutável** (nunca só tag).
5. JVM live / schema 035 intocados.
6. Overlay com `docker/chromium-seccomp.json`, UID `1000:1000`, `RELEASE_ANNOUNCER_ENABLED` conforme política.
7. Volume `/app/data` com controle do Announcer persistente; LocalAuth intacto.

Passos (quando autorizado):

1. Confirmar health live (somente leitura): WhatsApp READY, Chromium true, RestartCount.
2. Carregar tar da imagem final e verificar SHA-256 + Image ID.
3. Preparar overlay pinado no digest final (não mutável).
4. Garantir marker de controle `suspended` **ou** política explícita de abertura — fail-closed.
5. Drain/manutenção conforme procedimento A6 existente.
6. Recriar **somente** o serviço Node; não reiniciar JVM.
7. Validar health, Chromium, ausência de envios indevidos, LocalAuth.
8. Observar janela mínima; rollback pronto (runbook irmão).

Proibido neste runbook: migration 036, cutover RC #66, backup cold real sem ordem, alteração de saves/recibos, envio WhatsApp de teste em produção.
