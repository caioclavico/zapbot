#!/usr/bin/env bash
# Instala Clojure CLI localmente em .tools/clojure (gitignored).
# Útil para macOS/Linux de desenvolvimento sem alterar /usr ou Homebrew.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${ROOT}/.tools/clojure"
INSTALLER="/tmp/posix-install-clojure-$$.sh"

mkdir -p "${ROOT}/.tools"
curl -fsSL -o "${INSTALLER}" \
  https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh
chmod +x "${INSTALLER}"
"${INSTALLER}" --prefix "${PREFIX}"
rm -f "${INSTALLER}"

echo "Clojure CLI instalado em ${PREFIX}/bin/clojure"
"${PREFIX}/bin/clojure" --version
