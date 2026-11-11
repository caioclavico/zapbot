# Aceitação temporária de risco — deploy Node-only PR #71

Data (UTC): 2026-11-11  
Responsável: autorização explícita do operador Odisseu nesta sessão  
Escopo: implantação Node-only da proteção fail-closed do Release Announcer

## Artefato aceito

| Campo | Valor |
|---|---|
| Branch | `a6/live-baseline-8ef41c8` |
| Merge | `66db8a3a0fe75249eec002f962b231cd12c1e736` |
| Image ID implantado | `sha256:250155bae3ba5236feb676c289d285967b9867d087fe3d9750adf5e7acd2f476` |
| Tag local imutável de referência | `odisseu-node:a6-pr71-final-250155bae3ba-amd64` |
| Tar SHA-256 | `b81894e5cd37c92da38a8ec882a035fe5301b7982d80b818adaebbe6118bc204` |
| Código de runtime (rebuild) | commit `613e6cad9639…` (media harden) ⊂ merge #71 |

## Riscos temporariamente aceitos (somente este digest)

1. Chromium 156 — advisories High herdados (conteúdo remoto WhatsApp Web).
2. Critical Debian reportados pelo Trivy na imagem: CVE-2026-6653 (libxml2 sistema), CVE-2026-58016 (glib), CVE-2025-7458 (sqlite), CVE-2023-45853 (zlib/MiniZip — falso positivo Debian mantido visível).

## Mitigações em vigor

- UID `1000:1000`, `cap_drop: ALL`, `no-new-privileges`, seccomp Chromium versionado.
- Sharp 0.35.5 + harden de sprites (allowlist path, streaming, magic, HEIF block).
- Controle Announcer `suspended` (reason `node-only-deploy-pr71`, mode `0600`, uid 1000).
- JVM/schema/Datomic não alterados; sem migration 036; sem anúncios.

## Revisão obrigatória

Reavaliar até **2026-12-11** (30 dias): atualização Chromium/bookworm, patches Debian, ou nova aceitação explícita. Sem revisão, tratar como **expirada**.

## Fora de escopo (não autorizado)

Migration 036, update JVM, alteração de saves, backup cold, cutover RC #66, publicação de anúncios, desligar controles de segurança.
