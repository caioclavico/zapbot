#!/usr/bin/env python3
"""Backfill RO: flags kit/diário do Cassandra → manifesto JSON (sem conceder itens).

Não escreve Cassandra. Não muta Datomic sozinho — emite JSON para o apply Clojure
ou marca kit=true para recovery-v1 quando a loja Cassandra não tiver o campo.

Uso:
  python3 scripts/a64-bag-kit-daily-backfill.py \\
    --roster ~/odisseu-recovery/private-roster-a64-prod-12.edn \\
    --user-chat '1203...@g.us' --user-player '...@lid' \\
    [--cqlsh-docker odisseu-cassandra] \\
    [--keyspace zapbot] \\
    -o /tmp/bag-flags-backfill.json

Apply (GO separado, Datomic):
  clojure -M -m odisseu.smoke.bag-flags-apply \\
    --storage DIR --flags /tmp/bag-flags-backfill.json [--apply]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


def parse_roster(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    pairs = re.findall(
        r':player-hash\s+"([0-9a-f]+)"[\s\S]*?:chat-id\s+"([^"]+)"[\s\S]*?:player-id\s+"([^"]+)"',
        text,
    )
    if not pairs:
        pairs = re.findall(
            r':player-hash\s+"([0-9a-f]+)"[\s\S]*?:player-id\s+"([^"]+)"[\s\S]*?:chat-id\s+"([^"]+)"',
            text,
        )
        pairs = [(h, c, p) for h, p, c in pairs]
    return [{"ph": h, "chatId": c, "playerId": p} for h, c, p in pairs]


def cql_select(docker: str | None, keyspace: str, chat: str, player: str) -> dict | None:
    chave = f"loja:{chat}|{player}"
    # Escape single quotes for CQL
    chave_cql = chave.replace("'", "''")
    cql = f"SELECT valor FROM {keyspace}.estado WHERE chave = '{chave_cql}';"
    if docker:
        cmd = [
            "docker", "exec", "-i", docker,
            "cqlsh", "-e", cql,
        ]
    else:
        cmd = ["cqlsh", "-e", cql]
    try:
        out = subprocess.check_output(cmd, text=True, stderr=subprocess.STDOUT, timeout=60)
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired) as e:
        print(f"WARN cql skip {chat}: {e}", file=sys.stderr)
        return None
    # cqlsh table dump — crude JSON extract from valor column
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    # Skip headers; look for a line that looks like JSON object
    for ln in lines:
        if ln.startswith("{") and "kit" in ln or ln.startswith("{"):
            try:
                return json.loads(ln)
            except json.JSONDecodeError:
                continue
    return None


def flags_from_loja(loja: dict | None, assume_kit_if_missing: bool) -> dict:
    if not loja:
        return {
            "kitInicialResgatado": assume_kit_if_missing,
            "ultimoBonusDiario": None,
            "sequenciaBonusDiario": 0,
            "fromCassandra": False,
            "assumeKit": assume_kit_if_missing,
        }
    kit = bool(loja.get("kit-inicial-resgatado") or loja.get("kitInicialResgatado"))
    return {
        "kitInicialResgatado": kit or assume_kit_if_missing,
        "ultimoBonusDiario": loja.get("ultimo-bonus-diario") or loja.get("ultimoBonusDiario"),
        "sequenciaBonusDiario": int(loja.get("sequencia-bonus-diario") or loja.get("sequenciaBonusDiario") or 0),
        "fromCassandra": True,
        "assumeKit": (not kit) and assume_kit_if_missing,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--roster", type=Path, required=True)
    ap.add_argument("--user-chat")
    ap.add_argument("--user-player")
    ap.add_argument("--cqlsh-docker", default=None)
    ap.add_argument("--keyspace", default="zapbot")
    ap.add_argument("-o", "--output", type=Path, required=True)
    ap.add_argument(
        "--assume-kit-for-all",
        action="store_true",
        help="Se Cassandra não tiver kit-inicial-resgatado, marca kit claimed (contas recovery).",
    )
    args = ap.parse_args()

    entries = parse_roster(args.roster)
    if args.user_chat and args.user_player:
        entries.append(
            {"ph": "user", "chatId": args.user_chat, "playerId": args.user_player}
        )

    out_rows = []
    for e in entries:
        loja = cql_select(args.cqlsh_docker, args.keyspace, e["chatId"], e["playerId"])
        flags = flags_from_loja(loja, assume_kit_if_missing=args.assume_kit_for_all)
        out_rows.append({**e, **flags})

    payload = {
        "version": 1,
        "note": "Somente flags; apply Datomic não concede itens.",
        "players": out_rows,
    }
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    kit_n = sum(1 for r in out_rows if r["kitInicialResgatado"])
    cass_n = sum(1 for r in out_rows if r["fromCassandra"])
    print(f"wrote {args.output} players={len(out_rows)} kit={kit_n} cassandra={cass_n}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
