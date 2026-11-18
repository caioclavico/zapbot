#!/usr/bin/env python3
"""Fase 7 — manifesto RO anonimizado via bridge HTTP. Sem mutação."""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request
from collections import defaultdict

COMPOSE = os.environ.get(
    "ODISSEU_COMPOSE",
    "/home/caiohclavico/odisseu-core/docker-compose.release-battle-idle.yml",
)
BASE = os.environ.get("ODISSEU_BRIDGE", "http://127.0.0.1:18090")
OUT = os.environ.get(
    "FASE7_OUT",
    "/home/caiohclavico/odisseu-recovery/a6-fase7-live-manifest-ro.json",
)
ROSTER_PATHS = [
    "/home/caiohclavico/odisseu-recovery/private-roster-a64-prod-12.edn",
    "/home/caiohclavico/odisseu-recovery/private-roster-a64-full-remaining-6.edn",
]
# Treinador principal documentado em A6.4 (sem PII no relatório — só hash).
USER = {
    "chatId": "120363427684548487@g.us",
    "playerId": "207700373848240@lid",
}


def sha12(s: str) -> str:
    return hashlib.sha256(("id:" + str(s)).encode()).hexdigest()[:12]


def token() -> str:
    return subprocess.check_output(
        [
            "docker",
            "compose",
            "-f",
            COMPOSE,
            "exec",
            "-T",
            "odisseu-node",
            "printenv",
            "POKEMON_JVM_TOKEN",
        ],
        text=True,
    ).strip()


def get(tok: str, path: str, params=None):
    q = ("?" + urllib.parse.urlencode(params)) if params else ""
    req = urllib.request.Request(
        BASE + path + q, headers={"Authorization": f"Bearer {tok}"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def load_players():
    seen = set()
    players = []
    for rp in ROSTER_PATHS:
        if not os.path.isfile(rp):
            continue
        text = open(rp, encoding="utf-8").read()
        pairs = re.findall(r':chat-id\s+"([^"]+)"[\s\S]*?:player-id\s+"([^"]+)"', text)
        if not pairs:
            pairs = re.findall(
                r':player-id\s+"([^"]+)"[\s\S]*?:chat-id\s+"([^"]+)"', text
            )
            pairs = [(c, p) for p, c in pairs]
        for chat, pid in pairs:
            key = (chat, pid)
            if key not in seen:
                seen.add(key)
                players.append({"chatId": chat, "playerId": pid})
    ukey = (USER["chatId"], USER["playerId"])
    if ukey not in seen:
        players.append(USER)
    return players


def bag_skus(inv):
    if not isinstance(inv, dict):
        return []
    bag = inv.get("itens") or inv.get("items") or inv.get("inventory")
    if isinstance(bag, list):
        out = []
        for i in bag:
            if not isinstance(i, dict):
                continue
            sku = i.get("itemId") or i.get("id")
            if sku is None:
                continue
            try:
                qty = int(i.get("qty") or i.get("quantity") or 0)
            except (TypeError, ValueError):
                qty = 0
            out.append((str(sku), qty))
        return sorted(out)
    return []


def main():
    tok = token()
    health = get(tok, "/health")
    players = load_players()
    entries = []
    totals = defaultdict(int)
    errors = []

    for p in players:
        chat, pid = p["chatId"], p["playerId"]
        ph, ch = sha12(pid), sha12(chat)
        try:
            saves = get(tok, "/v1/saves", {"chatId": chat, "playerId": pid})["data"]
            trainer = get(tok, "/v1/trainer", {"chatId": chat, "playerId": pid}).get(
                "data"
            ) or {}
            inv = get(tok, "/v1/inventory", {"chatId": chat, "playerId": pid}).get(
                "data"
            ) or {}
        except Exception as e:
            errors.append(
                {"playerHash": ph, "chatHash": ch, "error": str(e)[:200]}
            )
            continue

        slot_rows = []
        for s in saves.get("saves") or []:
            slot = s.get("slotId")
            poke_ids = []
            levels = []
            xp_sum = 0
            hp_sum = 0
            coins = s.get("coins")
            cc = s.get("collectionCount")
            if slot == saves.get("activeSaveId"):
                try:
                    col = get(
                        tok, "/v1/collection", {"chatId": chat, "playerId": pid}
                    ).get("data") or {}
                    items = (
                        col.get("collection")
                        or col.get("pokemon")
                        or col.get("items")
                        or []
                    )
                    if isinstance(items, dict):
                        items = items.get("pokemon") or list(items.values())
                    for it in items:
                        if not isinstance(it, dict):
                            continue
                        iid = it.get("idPokemon") or it.get("id") or it.get("pokemonId")
                        if iid is not None:
                            poke_ids.append(str(iid))
                        levels.append(int(it.get("nivel") or it.get("level") or 0))
                        xp_sum += int(
                            it.get("xpDesdeNivel")
                            or it.get("xp")
                            or it.get("experiencia")
                            or 0
                        )
                        hp_sum += int(it.get("hpAtual") or it.get("hp") or 0)
                    if cc is None:
                        cc = len(poke_ids)
                    # Prefer API count when present
                    if col.get("count") is not None:
                        cc = int(col["count"])
                except Exception as e:
                    errors.append(
                        {
                            "playerHash": ph,
                            "slot": slot,
                            "collectionError": str(e)[:120],
                        }
                    )

            slot_canon = {
                "slotId": slot,
                "origin": s.get("origin"),
                "displayNamePresent": bool(s.get("displayName")),
                "collectionCount": cc,
                "coins": coins,
                "trainerLevel": s.get("trainerLevel") or s.get("level"),
                "trainerXp": s.get("trainerXp") or s.get("xp"),
                "active": slot == saves.get("activeSaveId"),
            }
            if poke_ids:
                slot_canon["pokemonIdCount"] = len(poke_ids)
                slot_canon["pokemonIdsDigest"] = hashlib.sha256(
                    ("|".join(sorted(poke_ids))).encode()
                ).hexdigest()[:16]
                slot_canon["levelSum"] = sum(levels)
                slot_canon["xpSum"] = xp_sum
                slot_canon["hpSum"] = hp_sum
            if slot == saves.get("activeSaveId"):
                skus = bag_skus(inv)
                slot_canon["inventorySkuCount"] = len(skus)
                slot_canon["inventoryDigest"] = hashlib.sha256(
                    json.dumps(skus).encode()
                ).hexdigest()[:16]
                slot_canon["coinsOperational"] = (
                    inv.get("coins") or trainer.get("coins") or coins
                )

            body = {k: slot_canon[k] for k in sorted(slot_canon)}
            slot_canon["canonicalHash"] = hashlib.sha256(
                json.dumps(body, sort_keys=True, default=str).encode()
            ).hexdigest()
            slot_rows.append(slot_canon)
            totals["slots"] += 1
            totals["pokemonListed"] += int(cc or 0)
            if coins is not None:
                totals["coinsSumListed"] += int(coins or 0)

        entry = {
            "playerHash": ph,
            "chatHash": ch,
            "activeSaveId": saves.get("activeSaveId"),
            "fenceGeneration": saves.get("fenceGeneration"),
            "slotCount": len(slot_rows),
            "slots": sorted(slot_rows, key=lambda x: str(x.get("slotId"))),
        }
        entry["playerCanonicalHash"] = hashlib.sha256(
            json.dumps(
                {
                    "active": entry["activeSaveId"],
                    "fence": entry["fenceGeneration"],
                    "slots": [(s["slotId"], s["canonicalHash"]) for s in entry["slots"]],
                },
                sort_keys=True,
            ).encode()
        ).hexdigest()
        entries.append(entry)
        totals["players"] += 1

    report = {
        "phase": "fase7-live-ro",
        "ok": len(errors) == 0,
        "healthRole": (health.get("data") or {}).get("role"),
        "playerCount": len(entries),
        "totals": dict(totals),
        "errors": errors,
        "players": sorted(entries, key=lambda e: e["playerHash"]),
        "privacy": "chat/player ids hashed sha12; no phone/name/message content",
        "note": "Coleção detalhada (IDs/XP/HP) só do save ativo via API RO.",
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, sort_keys=True)
    print(
        json.dumps(
            {
                "out": OUT,
                "players": len(entries),
                "errors": len(errors),
                "totals": dict(totals),
                "ok": report["ok"],
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
