#!/usr/bin/env bash
# Confere tags locais da RC gameplay contra Image IDs aprovados (não registry digests).
# Sem fallback silencioso. Exit ≠ 0 se tag ausente ou Id divergir.
set -euo pipefail

NODE_TAG="${A6_RC_NODE_TAG:-odisseu-node:a6-tx-datoms-conflict-persistent-hotfix-20261109T100054Z-8ef41c8da1c2-amd64}"
JVM_TAG="${A6_RC_JVM_TAG:-odisseu-jvm:a6-tx-datoms-conflict-persistent-hotfix-20261109T100054Z-8ef41c8da1c2-amd64}"
NODE_ID_EXPECTED="${A6_RC_NODE_IMAGE_ID:-sha256:a87abb519681dc5aefbf83feaf295ec4427989f76386a10e5970a6d2d004dbd7}"
JVM_ID_EXPECTED="${A6_RC_JVM_IMAGE_ID:-sha256:e93a0f54c944f97089b61c7b15d2548611bf67cce2823f039708b6bab44b1274}"
COMMIT_EXPECTED="${A6_RC_GIT_SHA:-8ef41c8da1c28bfb69d57bade81a5a41e9225d44}"
TREE_EXPECTED="${A6_RC_CONTENT_TREE:-849129daf4cf12d14de69097c23dfebfdb20bebc}"

# Artefatos de rollback breakglass (somente documentação/checagem opcional).
BREAKGLASS_NODE_ID="${A6_BREAKGLASS_NODE_IMAGE_ID:-sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390}"
# Rollback imediato = imagens do hotfix #63 (pré-#65).
LIVE_NODE_ID="${A6_LIVE_NODE_IMAGE_ID:-sha256:2870175c58b040614d11b9291d48d5c238fe7235cad3a179a6ed53de1bfa5396}"
LIVE_JVM_ID="${A6_LIVE_JVM_IMAGE_ID:-sha256:08da9ff2f4734a255d9ecc035cc41d35a200b642fcd7775fa7e294b62c02784f}"

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
  # Recusar pins conhecidos de linhagens anteriores / live / species-hp pré-aliases / Final live
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
    sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c|\
    sha256:bec910bf80b8fe5e3fdbfa385b81b4caf9dff3f1f6c1ccd22c2933e0c0e65305|\
    sha256:9e87bb6f0d48e964ad4f8eaa001913f7811da05c0365bb65bd9a309858050d60|\
    sha256:002c83e83485ad5ad7fad936ab76dec416b810a4e446ce5ebeda4a3399c21b3a|\
    sha256:e044cce13d9588691a110ba866743f6450fca5f3b4172553622c11479dbe3d94|\
    sha256:617525d90f3022455b71d81f4cf063924861f5bbc048b3cdbe0287b54dd304b1|\
    sha256:4a75eaa241c0c6ce45d541dada52e5966b456989f19ecd71e3b409661111816b|\
    sha256:57a828ca6b6187b1415e0a5ea6f71c8b7ec12dd7ffc7fc63e5e6563c2aee5332|\
    sha256:e1bb8ac5df0ab5ea81b1ab96ca689a602d47e14d41daf07fd0429fafd9df44a8|\
    sha256:17520a1ec6fcb88687c2f208b8cd276c384944f01cde57a3b0793c217532e2a0|\
    sha256:c13140fa9965b8cb78faa7edc1d95a95a5abc3ed1287203554e65748e52683ee|\
    sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e|\
    sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40|\
    sha256:77d0d9a255125c74d59168713dc025c42d9d189d9bd7278bf1813be981464d2b|\
    sha256:9443b80134bf5f2bb47009354b705785f065ff438c31de10496621a35d2198d1|\
    sha256:2870175c58b040614d11b9291d48d5c238fe7235cad3a179a6ed53de1bfa5396|\
    sha256:08da9ff2f4734a255d9ecc035cc41d35a200b642fcd7775fa7e294b62c02784f)
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
