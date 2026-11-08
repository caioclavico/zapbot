#!/usr/bin/env python3
"""Gera catálogo oficial de evidências de raridade (PokéAPI CSV → EDN).

Não classifica ainda — só materializa evidências versionadas para o classificador
JVM determinístico (sem HTTP em runtime).

Fonte: https://github.com/PokeAPI/pokeapi data/v2/csv
Docs: https://pokeapi.co/docs/v2
"""
from __future__ import annotations

import argparse
import csv
import json
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "modules/pokemon/domain/pokemon/domain/data/official_rarity_catalog.edn"
TOTAL = 1025
CSV_BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv"
CATALOG_VERSION = "official-rarity-catalog-v1"

PARADOX = {
    "great-tusk", "scream-tail", "brute-bonnet", "flutter-mane", "slither-wing",
    "sandy-shocks", "iron-treads", "iron-bundle", "iron-hands", "iron-jugulis",
    "iron-moth", "iron-thorns", "roaring-moon", "iron-valiant", "walking-wake",
    "iron-leaves", "gouging-fire", "raging-bolt", "iron-boulder", "iron-crown",
}

# Ultra Beasts — não são is_legendary na species CSV; list explícita (gen7).
ULTRA_BEASTS = {
    "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela", "kartana",
    "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon",
}


def edn(v) -> str:
    if v is True:
        return "true"
    if v is False:
        return "false"
    if v is None:
        return "nil"
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False)
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, list):
        return "[" + " ".join(edn(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + " ".join(f":{k} {edn(val)}" for k, val in v.items()) + "}"
    raise TypeError(type(v))


def fetch_csv(name: str, cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / name
    if not path.exists():
        url = f"{CSV_BASE}/{name}"
        print(f"download {url}")
        urllib.request.urlretrieve(url, path)
    return path


def evo_stages(species: dict[int, dict]) -> dict[int, int]:
    """Estágio 1=raiz, 2=meio, 3+=final (profundidade na cadeia)."""
    children: dict[int | None, list[int]] = defaultdict(list)
    for sid, sp in species.items():
        parent = sp["evolves_from"]
        children[parent].append(sid)

    stage: dict[int, int] = {}

    def walk(sid: int, depth: int) -> None:
        stage[sid] = depth
        for child in children.get(sid, []):
            walk(child, depth + 1)

    for sid, sp in species.items():
        if sp["evolves_from"] is None:
            walk(sid, 1)
    return stage


def chain_max_stage(species: dict[int, dict], stage: dict[int, int]) -> dict[int, int]:
    by_chain: dict[int, int] = defaultdict(int)
    for sid, sp in species.items():
        cid = sp["evolution_chain_id"]
        by_chain[cid] = max(by_chain[cid], stage.get(sid, 1))
    return {sid: by_chain[sp["evolution_chain_id"]] for sid, sp in species.items()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(ROOT / "tmp-rarity-catalog"))
    args = ap.parse_args()
    cache = Path(args.cache)

    species: dict[int, dict] = {}
    with open(fetch_csv("pokemon_species.csv", cache)) as f:
        for row in csv.DictReader(f):
            sid = int(row["id"])
            if sid > TOTAL:
                continue
            species[sid] = {
                "id": sid,
                "species": row["identifier"],
                "generation": int(row["generation_id"]),
                "capture_rate": int(row["capture_rate"]),
                "base_happiness": int(row["base_happiness"] or 0),
                "is_legendary": row["is_legendary"] == "1",
                "is_mythical": row["is_mythical"] == "1",
                "is_baby": row["is_baby"] == "1",
                "evolves_from": int(row["evolves_from_species_id"])
                if row["evolves_from_species_id"]
                else None,
                "evolution_chain_id": int(row["evolution_chain_id"]),
            }

    stage = evo_stages(species)
    chain_len = chain_max_stage(species, stage)

    # Evoluções especiais (trade / item / happiness)
    evo_flags: dict[int, set[str]] = defaultdict(set)
    with open(fetch_csv("pokemon_evolution.csv", cache)) as f:
        for row in csv.DictReader(f):
            esid = int(row["evolved_species_id"])
            if esid > TOTAL:
                continue
            trigger = int(row["evolution_trigger_id"] or 0)
            # 1 level, 2 trade, 3 use-item, 4 shed, 5 spin, …
            if trigger == 2:
                evo_flags[esid].add("trade")
            if trigger == 3 or row.get("trigger_item_id"):
                evo_flags[esid].add("item")
            if row.get("minimum_happiness"):
                evo_flags[esid].add("happiness")
            if row.get("trade_species_id"):
                evo_flags[esid].add("trade")
            if row.get("held_item_id") and trigger == 2:
                evo_flags[esid].add("trade-held")

    # Default pokemon id por species (encontros usam pokemon_id)
    pokemon_by_species: dict[int, int] = {}
    species_by_pokemon: dict[int, int] = {}
    with open(fetch_csv("pokemon.csv", cache)) as f:
        for row in csv.DictReader(f):
            sid = int(row["species_id"])
            if sid > TOTAL:
                continue
            pid = int(row["id"])
            species_by_pokemon[pid] = sid
            if row.get("is_default", "1") == "1":
                pokemon_by_species[sid] = pid

    stats: dict[int, dict[int, int]] = defaultdict(dict)
    with open(fetch_csv("pokemon_stats.csv", cache)) as f:
        for row in csv.DictReader(f):
            stats[int(row["pokemon_id"])][int(row["stat_id"])] = int(row["base_stat"])

    # Encontros: agregar por species_id (todas as formas do mon)
    enc_locations: dict[int, set[int]] = defaultdict(set)
    enc_versions: dict[int, set[int]] = defaultdict(set)
    enc_rows: dict[int, int] = defaultdict(int)
    with open(fetch_csv("encounters.csv", cache)) as f:
        for row in csv.DictReader(f):
            pid = int(row["pokemon_id"])
            sid = species_by_pokemon.get(pid)
            if sid is None or sid > TOTAL:
                continue
            enc_locations[sid].add(int(row["location_area_id"]))
            enc_versions[sid].add(int(row["version_id"]))
            enc_rows[sid] += 1

    entries = []
    for sid, sp in sorted(species.items()):
        pid = pokemon_by_species.get(sid)
        st = stats.get(pid or -1, {})
        bst = sum(st.get(i, 0) for i in range(1, 7))
        name = sp["species"]
        n_loc = len(enc_locations.get(sid, ()))
        n_ver = len(enc_versions.get(sid, ()))
        n_rows = enc_rows.get(sid, 0)
        flags = sorted(evo_flags.get(sid, ()))
        incomplete = n_rows == 0 and not sp["is_legendary"] and not sp["is_mythical"]
        entries.append(
            {
                "id": sid,
                "species": name,
                "generation": sp["generation"],
                "taxa-captura": sp["capture_rate"],
                "base-happiness": sp["base_happiness"],
                "lendario-api?": sp["is_legendary"],
                "mitico-api?": sp["is_mythical"],
                "bebe-api?": sp["is_baby"],
                "paradox-api?": name in PARADOX,
                "ultra-beast?": name in ULTRA_BEASTS,
                "evolves-from": sp["evolves_from"],
                "evolution-chain-id": sp["evolution_chain_id"],
                "evo-stage": stage.get(sid, 1),
                "chain-max-stage": chain_len.get(sid, 1),
                "evo-final?": stage.get(sid, 1) >= chain_len.get(sid, 1)
                and chain_len.get(sid, 1) > 1,
                "evo-methods": flags,
                "bst-raw": bst,
                "encounter-locations": n_loc,
                "encounter-versions": n_ver,
                "encounter-rows": n_rows,
                "has-encounters?": n_rows > 0,
                "dados-incompletos?": incomplete,
            }
        )

    incomplete_n = sum(1 for e in entries if e["dados-incompletos?"])
    payload = {
        "version": CATALOG_VERSION,
        "source": "PokeAPI CSV (pokeapi/data/v2/csv)",
        "source-docs": "https://pokeapi.co/docs/v2",
        "built-at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "total-pokemons": TOTAL,
        "species-count": len(entries),
        "incomplete-count": incomplete_n,
        "notes": (
            "Evidências oficiais para classificação Odisseu. "
            "encounter-* agrega location_area/version; ausência ≠ raridade extrema. "
            "taxa-captura é capture_rate oficial, não chance Odisseu."
        ),
        "species": entries,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(edn(payload) + "\n")
    print(
        f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(entries)} species, "
        f"incomplete={incomplete_n})"
    )


if __name__ == "__main__":
    main()
