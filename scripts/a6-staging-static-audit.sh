#!/usr/bin/env bash
# Auditoria estática da stack A6 isolada (sem docker up / migrate / deploy).
# Exit 0 = isolamento OK para preparação; ≠0 = violação.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE="$ROOT/docker-compose.a6-staging.yml"
ENV_EX="$ROOT/.env.a6-staging.example"
FAIL=0
CORE_COMPOSE="${CORE_COMPOSE:-}"

say() { printf '%s\n' "$*"; }
pass() { say "PASS: $*"; }
fail() { say "FAIL: $*"; FAIL=1; }

say "=== A6 staging static audit ==="
say "root=$ROOT"

# --- Compose exists ---
if [[ ! -f "$COMPOSE" ]]; then
  fail "missing $COMPOSE"
  exit 1
fi
if [[ ! -f "$ENV_EX" ]]; then
  fail "missing $ENV_EX"
  exit 1
fi

# --- docker compose config (requires dummy token) ---
TMP_ENV="$(mktemp)"
trap 'rm -f "$TMP_ENV"' EXIT
cp "$ENV_EX" "$TMP_ENV"
# shellcheck disable=SC2016
sed -i.bak 's/^ODISSEU_BRIDGE_TOKEN=.*/ODISSEU_BRIDGE_TOKEN=audit-static-token-not-for-deploy/' "$TMP_ENV"
rm -f "${TMP_ENV}.bak"

if ! command -v docker >/dev/null 2>&1; then
  fail "docker CLI ausente — não validou compose config"
else
  CFG_FILE="$(mktemp)"
  if ODISSEU_BRIDGE_TOKEN=audit-static-token-not-for-deploy \
      docker compose -f "$COMPOSE" --env-file "$TMP_ENV" config >"$CFG_FILE" 2>"$CFG_FILE.err"; then
    pass "docker compose config válido"
  else
    fail "docker compose config falhou: $(cat "$CFG_FILE.err")"
  fi

  # Auditoria no config renderizado (sem comentários do YAML fonte)
  if [[ -s "$CFG_FILE" ]]; then
    for pat in \
      'odisseu-core-a56' \
      'a56-datomic' \
      'odisseu-m90-core' \
      'odisseu-core-datomic' \
      'wwebjs_auth_core' \
      'WWEBJS_CLIENT_ID: core' \
      'client_id: core'; do
      if grep -Eiq "$pat" "$CFG_FILE"; then
        fail "compose config renderizado contém padrão proibido: $pat"
      else
        pass "compose config sem padrão proibido: $pat"
      fi
    done
    # Confirma system/db exclusivos no render
    if grep -Eq 'ODISSEU_BRIDGE_SYSTEM: odisseu-a6-staging' "$CFG_FILE" || \
       grep -Eq 'odisseu-a6-staging' "$CFG_FILE"; then
      pass "config renderizado referencia odisseu-a6-staging"
    else
      fail "config renderizado sem odisseu-a6-staging"
    fi
  fi
  rm -f "$CFG_FILE" "$CFG_FILE.err"
fi

# --- Required exclusive identifiers ---
REQUIRED=(
  'name: odisseu-a6-staging'
  'ODISSEU_BRIDGE_SYSTEM: odisseu-a6-staging'
  'ODISSEU_BRIDGE_DB: core-a6-staging'
  'ODISSEU_PLATFORM_DB: platform-a6-staging'
  'odisseu-a6-staging-datomic'
  'odisseu-a6-staging-journal'
  'odisseu-a6-staging-maint'
  '127.0.0.1:18091:18090'
  'APP_ENV: staging'
  'ALLOW_ALL_GROUPS: "false"'
  'POKEMON_RAID_AUTO_TICK: "false"'
  'STAGING_GROUP_CONFIRMED:'
  'WWEBJS_CLIENT_ID: a6-staging'
  'odisseu-a6-staging_internal'
)

for req in "${REQUIRED[@]}"; do
  if grep -Fq "$req" "$COMPOSE"; then
    pass "compose tem: $req"
  else
    fail "compose falta: $req"
  fi
done

# --- Env example protections ---
for req in \
  'APP_ENV=staging' \
  'ALLOW_ALL_GROUPS=false' \
  'STAGING_GROUP_CONFIRMED=0' \
  'POKEMON_RAID_AUTO_TICK=false' \
  'ODISSEU_BRIDGE_TOKEN=CHANGE_ME'; do
  if grep -Fq "$req" "$ENV_EX" || grep -Eq "^${req}" "$ENV_EX"; then
    pass "env.example: $req"
  else
    # token line may be CHANGE_ME_GENERATE...
    if [[ "$req" == ODISSEU_BRIDGE_TOKEN=CHANGE_ME* ]] && grep -Eq '^ODISSEU_BRIDGE_TOKEN=CHANGE_ME' "$ENV_EX"; then
      pass "env.example: ODISSEU_BRIDGE_TOKEN placeholder"
    else
      fail "env.example falta: $req"
    fi
  fi
done

# --- Migration chain 025–030 present + listed in installer ---
MIG_DIR="$ROOT/migrations/pokemon"
SCHEMA_CLJ="$ROOT/modules/pokemon/infrastructure/pokemon/infrastructure/datomic/schema.clj"
MIG_LIST="025-save-slot-metadata.edn
026-battle-idle.edn
027-bag-daily.edn
028-economy-ledger.edn
029-raid-lobby.edn
030-raid-auto.edn"
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  if [[ -f "$MIG_DIR/$f" ]]; then
    pass "arquivo migrations/pokemon/$f"
  else
    fail "faltando migrations/pokemon/$f"
  fi
  if [[ -f "$SCHEMA_CLJ" ]] && grep -Fq "migrations/pokemon/$f" "$SCHEMA_CLJ"; then
    pass "instalador lista $f"
  else
    fail "schema.clj não lista $f"
  fi
done <<EOF
$MIG_LIST
EOF
# Garantir ordem 025→030 no vetor do instalador
if [[ -f "$SCHEMA_CLJ" ]]; then
  ORDER=$(grep -E 'migrations/pokemon/02[5-9]-|migrations/pokemon/030-' "$SCHEMA_CLJ" | sed 's/.*"\(migrations\/pokemon\/[^"]*\)".*/\1/')
  EXPECT=$(printf '%s\n' \
    'migrations/pokemon/025-save-slot-metadata.edn' \
    'migrations/pokemon/026-battle-idle.edn' \
    'migrations/pokemon/027-bag-daily.edn' \
    'migrations/pokemon/028-economy-ledger.edn' \
    'migrations/pokemon/029-raid-lobby.edn' \
    'migrations/pokemon/030-raid-auto.edn')
  if [[ "$ORDER" == "$EXPECT" ]]; then
    pass "ordem do instalador 025→030 correta"
  else
    fail "ordem do instalador 025→030 divergente"
    say "got:"$'\n'"$ORDER"
  fi
fi

# Explicit: compose must NOT mount host path that looks like a56
if grep -Eiq 'odisseu-core-a56-datomic|/data/datomic-local-a56|a56-datomic:' "$COMPOSE"; then
  fail "compose monta volume/path a56"
else
  pass "compose sem mount a56"
fi

# --- Compare vs Core compose if provided ---
if [[ -n "$CORE_COMPOSE" && -f "$CORE_COMPOSE" ]]; then
  say "--- comparação vs Core compose: $CORE_COMPOSE ---"
  CORE_SYS=$(grep -E 'ODISSEU_BRIDGE_SYSTEM' "$CORE_COMPOSE" | head -1 || true)
  A6_SYS=$(grep -E 'ODISSEU_BRIDGE_SYSTEM' "$COMPOSE" | head -1 || true)
  say "Core: $CORE_SYS"
  say "A6:   $A6_SYS"
  if [[ "$CORE_SYS" == "$A6_SYS" ]]; then
    fail "system Datomic idêntico ao Core"
  else
    pass "system Datomic distinto do Core"
  fi
fi

# --- Port conflict check (local host, read-only) ---
for port in 18091 3002; do
  if command -v lsof >/dev/null 2>&1; then
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      say "WARN: porta local $port já em LISTEN (não bloqueia preparação de arquivos)"
    else
      pass "porta local $port livre"
    fi
  fi
done

say "=== resultado: FAIL=$FAIL ==="
exit "$FAIL"
