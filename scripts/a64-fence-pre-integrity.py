#!/usr/bin/env python3
"""RO integrity: 12 recovery trainers + user (119). Sem mutação."""
import json
import re
import subprocess
import urllib.parse
import urllib.request

ROSTER = "/home/caiohclavico/odisseu-recovery/private-roster-a64-prod-12.edn"
REPORT = "/home/caiohclavico/odisseu-recovery/a64-activate-12-report.json"
COMPOSE = "/home/caiohclavico/odisseu-core/docker-compose.release-battle-idle.yml"
USER_CHAT = "120363427684548487@g.us"
USER_PID = "207700373848240@lid"


def token():
    return subprocess.check_output(
        ["docker", "compose", "-f", COMPOSE, "exec", "-T", "odisseu-node",
         "printenv", "POKEMON_JVM_TOKEN"],
        text=True,
    ).strip()


def saves(tok, chat, pid):
    q = urllib.parse.urlencode({"chatId": chat, "playerId": pid})
    req = urllib.request.Request(
        f"http://127.0.0.1:18090/v1/saves?{q}",
        headers={"Authorization": f"Bearer {tok}"},
    )
    return json.load(urllib.request.urlopen(req, timeout=60))["data"]


def main():
    text = open(ROSTER, encoding="utf-8").read()
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
    rep = json.load(open(REPORT))
    exp = {r["ph"]: r for r in rep["integrity"]["post_twelve"]}
    tok = token()
    ok = 0
    sum_rec = 0
    bad = []
    for ph, chat, pid in pairs:
        if ph not in exp:
            continue
        e = exp[ph]
        data = saves(tok, chat, pid)
        slots = {s["slotId"]: s.get("collectionCount", 0) for s in data["saves"]}
        rec = slots.get("recovery-v1", 0)
        sum_rec += rec
        if rec == e["rec"] and data["activeSaveId"] == e["active"]:
            ok += 1
        else:
            bad.append(
                {
                    "ph": ph,
                    "active": data["activeSaveId"],
                    "want_active": e["active"],
                    "rec": rec,
                    "want_rec": e["rec"],
                }
            )
    udata = saves(tok, USER_CHAT, USER_PID)
    uslots = {s["slotId"]: s.get("collectionCount", 0) for s in udata["saves"]}
    user_ok = (
        uslots.get("recovery-v1") == 119
        and uslots.get("a") == 1
        and udata["activeSaveId"] == "recovery-v1"
    )
    out = {
        "twelve_ok": ok,
        "twelve_total": len(exp),
        "sum12": sum_rec,
        "user_ok": user_ok,
        "user": {
            "active": udata["activeSaveId"],
            "fence": udata["fenceGeneration"],
            "slots": uslots,
        },
        "sum13": sum_rec + uslots.get("recovery-v1", 0),
        "expect_sum13": 158,
        "bad": bad,
    }
    print(json.dumps(out, indent=2))
    if ok != len(exp) or not user_ok or out["sum13"] != 158:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
