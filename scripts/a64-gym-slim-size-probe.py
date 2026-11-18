#!/usr/bin/env python3
"""RO size probe for gym battle state-edn after tighter slim."""
import base64
import gzip
import json
import re
import sys

cols = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "/tmp/col.json"))["data"]["collection"]


def kw(s):
    s = re.sub(r"([a-z])([A-Z])", r"\1-\2", s).replace("_", "-").lower()
    return s


def edn2(o):
    if o is None:
        return "nil"
    if isinstance(o, bool):
        return "true" if o else "false"
    if isinstance(o, float):
        return str(int(o)) if o == int(o) else repr(o)
    if isinstance(o, int):
        return str(o)
    if isinstance(o, str):
        return json.dumps(o, ensure_ascii=False)
    if isinstance(o, list):
        return "[" + " ".join(edn2(x) for x in o) + "]"
    if isinstance(o, dict):
        parts = []
        for k, v in o.items():
            kk = k if str(k).startswith(":") else ":" + kw(str(k))
            parts.append(f"{kk} {edn2(v)}")
        return "{" + " ".join(parts) + "}"
    return str(o)


COMBAT_KEEP = [
    "slug", "nomeExibicao", "tipo", "poder", "classe", "precisao",
    "statusCausado", "chanceStatus", "cura", "dreno", "recuo",
    "minAcertos", "maxAcertos", "alteracoes", "alvo",
]
ZERO_DROP = {"cura", "dreno", "recuo", "chanceStatus"}
POKE_KEEP = [
    "species", "nome", "shiny", "nivel", "hp", "hpAtual", "status",
    "ataque", "defesa", "atqEsp", "defEsp", "veloc", "tipos",
    "habilidade", "golpes", "item",
]


def slim_g(g, drop_defaults=True):
    out = {}
    for k in COMBAT_KEEP:
        if k not in g:
            continue
        v = g[k]
        if v is None:
            continue
        if drop_defaults and k in ZERO_DROP and v == 0:
            continue
        if drop_defaults and k == "alteracoes" and v == []:
            continue
        if drop_defaults and k == "alvo" and v == "adversario":
            continue
        if drop_defaults and k == "precisao" and v == 100:
            continue
        out[k] = v
    return out


def slim_p(p, drop_defaults=True):
    out = {}
    for k in POKE_KEEP:
        if k not in p:
            continue
        v = p[k]
        if v is None and k == "status":
            continue
        if k == "golpes":
            out[k] = [slim_g(g, drop_defaults) for g in (v or [])]
        else:
            out[k] = v
    return out


def battle_state(team_x, team_o, slim=slim_p):
    xs = [{"collection-index": i, "pokemon": slim(p)} for i, p in enumerate(team_x)]
    os = [slim(p) for p in team_o]
    return {
        "battle-id": "x" * 36,
        "kind": "gym",
        "gym-id": "pedra",
        "gym-nome": "Ginásio Pedra",
        "expected-revision": 0,
        "lider-nome": "Brock",
        "desafiante-nome": "Caio",
        "chat-id": "120363427684548487@g.us",
        "player-id": "197345274802194@lid",
        "save-id": "recovery-v1",
        "fence-generation": 2,
        "team-x-indices": [0, 1, 2],
        "pokemons": {"x": xs[0]["pokemon"], "o": os[0]},
        "hp": {"x": xs[0]["pokemon"]["hpAtual"], "o": os[0]["hpAtual"]},
        "status": {"x": None, "o": None},
        "estagios": {"x": {}, "o": {}},
        "defendendo": {"x": False, "o": False},
        "itens-usados": {"x": {}, "o": {}},
        "indices-ativos": {"x": 0, "o": None},
        "participacao": {"x": {0: 0}, "o": {}},
        "reservas": {"x": xs[1:], "o": os[1:]},
        "fainted-x": [],
        "turn-index": 0,
        "phase": "active",
        "outcome": None,
        "active?": True,
        "aguardando-captura?": False,
        "last-activity-at": 1730000000000,
        "idle-expires-at": 1730000300000,
    }


ranked = sorted(
    cols,
    key=lambda p: (p.get("nivel") or 0)
    * sum(p.get(k) or 0 for k in ("ataque", "defesa", "atqEsp", "defEsp", "veloc")),
    reverse=True,
)
team = ranked[:3]
npc = ranked[50:53] if len(ranked) > 53 else ranked[3:6]
fat = sorted(cols, key=lambda p: len(edn2(slim_p(p))), reverse=True)[:3]

st = battle_state(team, npc)
st2 = battle_state(fat, fat)
print("typical encoded", len(edn2(st)), [p["nome"] for p in team], [p["nome"] for p in npc])
print("worst encoded", len(edn2(st2)), [p["nome"] for p in fat])


def slim_keepzeros(p):
    out = {}
    for k in POKE_KEEP:
        if k not in p:
            continue
        v = p[k]
        if k == "golpes":
            out[k] = [
                {kk: g[kk] for kk in COMBAT_KEEP if kk in g and g[kk] is not None}
                for g in (v or [])
            ]
        elif v is not None or k != "status":
            out[k] = v
    return out


st3 = battle_state(fat, fat, slim=slim_keepzeros)
print("current-style worst", len(edn2(st3)))
raw = edn2(st2).encode()
print("gzip+b64 worst", len(base64.b64encode(gzip.compress(raw, 9))), "raw", len(raw))
# team-edn occupation 3 only
team_edn = edn2([slim_p(p) for p in fat])
print("team-edn worst3", len(team_edn), "gz", len(base64.b64encode(gzip.compress(team_edn.encode(), 9))))
