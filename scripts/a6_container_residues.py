"""Inventário de colisões; nenhuma exclusão, rename ou start automático."""
import json
import re

from a6_production_deploy import DeployError

FORMAT = ('{"id":{{json .Id}},"imageId":{{json .Image}},'
          '"running":{{json .State.Running}},"pid":{{json .State.Pid}},"status":{{json .State.Status}},'
          '"labels":{{json .Config.Labels}}}')
IMAGE_FORMAT = ('{"id":{{json .Id}},"revision":'
                '{{json (index .Config.Labels "org.opencontainers.image.revision")}}}')


def _read(runner, args):
    result = runner(args)
    if result.returncode:
        raise DeployError("residue_observation_failed")
    return result.stdout


def inventory(runner, prefix):
    names = set(_read(runner, prefix + ["ps", "-a", "--filter", "name=odisseu-a6-",
                                      "--format", "{{.Names}}"]).splitlines())
    rows = []
    for service in ("jvm", "node"):
        name = "odisseu-a6-" + service + "-previous"
        if name not in names:
            continue
        try:
            doc = json.loads(_read(runner, prefix + ["inspect", "--format", FORMAT, name]))
            image = json.loads(_read(runner, prefix + ["image", "inspect", "--format",
                                                     IMAGE_FORMAT, doc["imageId"]]))
            known = (re.fullmatch(r"[0-9a-f]{64}", str(doc.get("id", ""))) is not None
                     and image["id"] == doc["imageId"]
                     and re.fullmatch(r"sha256:[0-9a-f]{64}", str(image["id"])) is not None
                     and re.fullmatch(r"[0-9a-f]{40}", str(image.get("revision", ""))) is not None
                     and type(doc.get("running")) is bool
                     and type(doc.get("pid")) is int
                     and doc.get("status") in ("created", "exited", "dead", "running", "paused", "restarting"))
            rows.append({"name": name, "service": service, "containerId": doc.get("id"),
                         "imageId": doc.get("imageId"), "revision": image.get("revision"),
                         "originWindow": (doc.get("labels") or {}).get("io.odisseu.a6.window-id"),
                         "running": doc.get("running"), "identityVerified": known,
                         "notWriter": known and doc["running"] is False
                         and doc["pid"] == 0
                         and doc["status"] in ("created", "exited", "dead"),
                         "resolution": "archive_stopped_container_after_explicit_authorization",
                         "execute": False, "preserveImage": True})
        except (ValueError, KeyError, TypeError) as exc:
            raise DeployError("residue_identity_unknown") from exc
    return rows


def collisions(rows, value, state, observation, service=None):
    originals = {d["Name"].lstrip("/"): d.get("Id")
                 for d in state.get("drained", {}).get("documents", [])}
    conflicts = []
    for row in rows:
        if service is not None and row["service"] != service:
            continue
        kind = row["service"]
        own = (state.get("proof") and row["identityVerified"] and row["notWriter"]
               and observation[kind]["imageId"] == value[kind + "Digest"]
               and row["imageId"] == value["rollback" + kind.capitalize() + "Digest"]
               and row["revision"] == value["rollbackSha"]
               and row["containerId"] == originals.get("odisseu-a6-" + kind))
        if not own:
            conflicts.append(row)
    return conflicts
