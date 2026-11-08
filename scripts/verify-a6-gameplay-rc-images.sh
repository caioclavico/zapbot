#!/usr/bin/env bash
# Confere tags locais da RC gameplay contra Image IDs aprovados (não registry digests).
# Sem fallback silencioso. Exit ≠ 0 se tag ausente ou Id divergir.
set -euo pipefail

NODE_TAG="${A6_RC_NODE_TAG:-odisseu-node:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64}"
JVM_TAG="${A6_RC_JVM_TAG:-odisseu-jvm:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64}"
NODE_ID_EXPECTED="${A6_RC_NODE_IMAGE_ID:-sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79}"
JVM_ID_EXPECTED="${A6_RC_JVM_IMAGE_ID:-sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652}"
COMMIT_EXPECTED="${A6_RC_GIT_SHA:-21771ec372eb2e6623f67a8e3ea2f5c334dd4006}"
TREE_EXPECTED="${A6_RC_CONTENT_TREE:-7645f59f7445f58d0f733aa578a4296bd342fa63}"

# Artefatos de rollback breakglass (somente documentação/checagem opcional).
BREAKGLASS_NODE_ID="${A6_BREAKGLASS_NODE_IMAGE_ID:-sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390}"

fail() { echo "FAIL: $*" >&2; exit 1; }

require_image() {
  local tag="$1" expected="$2" role="$3"
  if ! docker image inspect "$tag" >/dev/null 2>&1; then
    fail "$role tag ausente: $tag (carregue o tar aprovado; sem fallback)"
  fi
  local id
  id="$(docker image inspect "$tag" --format '{{.Id}}')"
  [[ "$id" == "$expected" ]] || fail "$role Id=$id esperado=$expected"
  local sha tree
  sha="$(docker image inspect "$tag" --format '{{index .Config.Labels "io.zapbot.git-sha"}}')"
  tree="$(docker image inspect "$tag" --format '{{index .Config.Labels "io.zapbot.content-tree"}}')"
  [[ "$sha" == "$COMMIT_EXPECTED" ]] || fail "$role git-sha=$sha esperado=$COMMIT_EXPECTED"
  [[ "$tree" == "$TREE_EXPECTED" ]] || fail "$role content-tree=$tree esperado=$TREE_EXPECTED"
  # Recusar pins conhecidos de linhagens anteriores se a tag as carregar por engano
  case "$id" in
    sha256:ce429153851799cc77f7e40c1f3bc328d963d891a9a1dc38ec9ceb4a3c7fb377|\
    sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865|\
    sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390)
      fail "$role resolveu Image ID legado/proibido para deploy desta RC: $id"
      ;;
  esac
  echo "PASS $role tag=$tag id=$id git-sha=$sha"
}

require_image "$NODE_TAG" "$NODE_ID_EXPECTED" node
require_image "$JVM_TAG" "$JVM_ID_EXPECTED" jvm

echo "INFO rollback_breakglass_node_id=$BREAKGLASS_NODE_ID (não carregar como candidata)"
echo "VERIFY_A6_GAMEPLAY_RC_IMAGES PASS"
