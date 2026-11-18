#!/usr/bin/env python3
"""Regenera modules/pokemon/domain/pokemon/domain/data/raid_boss_catalog.edn

Fonte: CSVs oficiais do PokeAPI (data/v2/csv). Stats suavizados com o mesmo
fator do legado (MEDIA=75, FATOR=0.5). Não cria lista fixa de raids — o domínio
descobre elegíveis em tempo de carga.
"""
from __future__ import annotations

import argparse
import csv
import json
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "modules/pokemon/domain/pokemon/domain/data/raid_boss_catalog.edn"
TOTAL = 1025
MEDIA = 75.0
FATOR = 0.5
CSV_BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv"

PARADOX = {
    "great-tusk", "scream-tail", "brute-bonnet", "flutter-mane", "slither-wing",
    "sandy-shocks", "iron-treads", "iron-bundle", "iron-hands", "iron-jugulis",
    "iron-moth", "iron-thorns", "roaring-moon", "iron-valiant", "walking-wake",
    "iron-leaves", "gouging-fire", "raging-bolt", "iron-boulder", "iron-crown",
}


def suavizar(v: float) -> int:
    return int(round(MEDIA + FATOR * (float(v) - MEDIA)))


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


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(ROOT / "tmp-raid-boss-catalog"))
    args = ap.parse_args()
    cache = Path(args.cache)

    type_id_to_name = {}
    with open(fetch_csv("types.csv", cache)) as f:
        for row in csv.DictReader(f):
            type_id_to_name[int(row["id"])] = row["identifier"]

    species = {}
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
                "is_legendary": row["is_legendary"] == "1",
                "is_mythical": row["is_mythical"] == "1",
                "evolves_from": int(row["evolves_from_species_id"])
                if row["evolves_from_species_id"]
                else None,
            }

    pokemon_by_species = {}
    with open(fetch_csv("pokemon.csv", cache)) as f:
        for row in csv.DictReader(f):
            sid = int(row["species_id"])
            if sid > TOTAL:
                continue
            if row.get("is_default", "1") != "1":
                continue
            pokemon_by_species[sid] = int(row["id"])

    stats: dict[int, dict[int, int]] = defaultdict(dict)
    with open(fetch_csv("pokemon_stats.csv", cache)) as f:
        for row in csv.DictReader(f):
            stats[int(row["pokemon_id"])][int(row["stat_id"])] = int(row["base_stat"])

    ptypes: dict[int, dict[int, str]] = defaultdict(dict)
    with open(fetch_csv("pokemon_types.csv", cache)) as f:
        for row in csv.DictReader(f):
            ptypes[int(row["pokemon_id"])][int(row["slot"])] = type_id_to_name[
                int(row["type_id"])
            ]

    entries = []
    for sid, sp in sorted(species.items()):
        pid = pokemon_by_species[sid]
        st = stats[pid]
        tipos = [ptypes[pid][s] for s in sorted(ptypes[pid])]
        raw = {
            "hp": st[1],
            "ataque": st[2],
            "defesa": st[3],
            "atq-esp": st[4],
            "def-esp": st[5],
            "veloc": st[6],
        }
        smooth = {k: suavizar(v) for k, v in raw.items()}
        name = sp["species"]
        entries.append(
            {
                "id": sid,
                "species": name,
                "nome": name.replace("-", " ").title(),
                "generation": sp["generation"],
                "tipos": tipos,
                **smooth,
                "bst-raw": sum(raw.values()),
                "taxa-captura": sp["capture_rate"],
                "lendario-api?": sp["is_legendary"],
                "mitico-api?": sp["is_mythical"],
                "paradox-api?": name in PARADOX,
                "evolves-from": sp["evolves_from"],
            }
        )

    payload = {
        "version": "raid-boss-catalog-v1",
        "source": "PokeAPI CSV (pokeapi/data/v2/csv) + suavizar-stat legado",
        "total-pokemons": TOTAL,
        "species-count": len(entries),
        "species": entries,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(edn(payload))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(entries)} species)")


if __name__ == "__main__":
    main()
