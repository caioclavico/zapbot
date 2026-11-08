#!/usr/bin/env bash
# Confere tags locais da RC gameplay contra Image IDs aprovados (não registry digests).
# Sem fallback silencioso. Exit ≠ 0 se tag ausente ou Id divergir.
set -euo pipefail

NODE_TAG="${A6_RC_NODE_TAG:-odisseu-node:a6-p0-hp-ui-20261108T072116Z-6cef8f43b0dc-amd64}"
JVM_TAG="${A6_RC_JVM_TAG:-odisseu-jvm:a6-p0-hp-ui-20261108T072116Z-6cef8f43b0dc-amd64}"
NODE_ID_EXPECTED="${A6_RC_NODE_IMAGE_ID:-sha256:bec910bf80b8fe5e3fdbfa385b81b4caf9dff3f1f6c1ccd22c2933e0c0e65305}"
JVM_ID_EXPECTED="${A6_RC_JVM_IMAGE_ID:-sha256:9e87bb6f0d48e964ad4f8eaa001913f7811da05c0365bb65bd9a309858050d60}"
COMMIT_EXPECTED="${A6_RC_GIT_SHA:-6cef8f43b0dce617af0ecf0b0374f6a541388104}"
TREE_EXPECTED="${A6_RC_CONTENT_TREE:-50babd2f4dc1e4bd1e83ca5b91a7ad2628773304}"

# Artefatos de rollback breakglass (somente documentação/checagem opcional).
BREAKGLASS_NODE_ID="${A6_BREAKGLASS_NODE_IMAGE_ID:-sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390}"
# Produção live observada (rollback desta candidata = cutover A+B+C+P0 atual).
LIVE_NODE_ID="${A6_LIVE_NODE_IMAGE_ID:-sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade}"
LIVE_JVM_ID="${A6_LIVE_JVM_IMAGE_ID:-sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c}"

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
  # Recusar pins conhecidos de linhagens anteriores / live se a tag as carregar por engano
  case "$id" in
    sha256:ce429153851799cc77f7e40c1f3bc328d963d891a9a1dc38ec9ceb4a3c7fb377|\
    sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865|\
    sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390|\
    sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79|\
    sha256:446dfc2bdd7aba4f79c1e9e1e27590373ae4539be46a71aaefd69635743a6043|\
    sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652|\
    sha256:05b36c5aebeff40875c2a3b4545e368aa2dfffe7d93a3fc7a02c05b0d3b3a18c|\
    sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c|\
    sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade|\
    sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c)
      fail "$role resolveu Image ID legado/live/proibido para deploy desta RC: $id"
      ;;
  esac
  echo "PASS $role tag=$tag id=$id git-sha=$sha"
}

require_image "$NODE_TAG" "$NODE_ID_EXPECTED" node
require_image "$JVM_TAG" "$JVM_ID_EXPECTED" jvm

[[ "$NODE_ID_EXPECTED" != "$LIVE_NODE_ID" ]] || fail "candidata Node colide com live"
[[ "$JVM_ID_EXPECTED" != "$LIVE_JVM_ID" ]] || fail "candidata JVM colide com live"

echo "INFO rollback_live_node_id=$LIVE_NODE_ID"
echo "INFO rollback_live_jvm_id=$LIVE_JVM_ID"
echo "INFO rollback_breakglass_node_id=$BREAKGLASS_NODE_ID (não carregar como candidata)"
echo "VERIFY_A6_GAMEPLAY_RC_IMAGES PASS"
