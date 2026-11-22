"""Manutenção autenticada do Node e da JVM.

O token não entra no argv montado aqui: no Node ele vem de process.env no
container; na JVM o shell expande a variável já presente no ambiente.
Este módulo não escreve o marcador. Quem persiste a fase é o processo que
atende o POST. O arquivo só é lido depois, para conferir o que a API disse.
"""

from __future__ import annotations

import json

from a6_production_deploy import DeployError


NODE = "odisseu-a6-node"
JVM = "odisseu-a6-jvm"
NODE_PORT = 3001
JVM_PORT = 18090
NODE_HEADER = "x-a6-node-maintenance-token"
JVM_HEADER = "X-A6-Maintenance-Token"
NODE_TOKEN_ENV = "ODISSEU_NODE_MAINTENANCE_TOKEN"
JVM_TOKEN_ENV = "ODISSEU_BRIDGE_MAINTENANCE_TOKEN"
NODE_MARKER = "/app/data/a6-maintenance.json"
JVM_MARKER = "/data/datomic-local/.a6-maintenance/bridge-maintenance.edn"
KNOWN_PHASES = {"open", "draining", "quiescent", "frozen"}
TIMEOUT_MS = "30000"


def _curl_script(method, path, header, env_name, port):
    """curl no JVM: aspas duplas no header para o sh expandir $ENV.

    Aspas simples em volta de `$ENV` enviam o literal e a API responde 401.
    O valor do token não entra no argv do Python — só o nome da variável.
    """
    for part in (method, path, header, env_name):
        if '"' in part or "'" in part or not part:
            raise DeployError("maintenance_command_refused")
    return (
        "curl -sS -w '\\n%{http_code}' -X "
        + method
        + " -H \""
        + header
        + ": $"
        + env_name
        + "\" 'http://127.0.0.1:"
        + str(port)
        + path
        + "'"
    )


def _node_http_script(method, path, header, env_name, port):
    """HTTP via node: imagens Node de produção não trazem curl.

    Aspas simples no -e (sh -c); JSON/JS usam aspas duplas. function()
    evita '>' no script. Token só via process.env; argv cita o nome.
    """
    for part in (method, path, header, env_name):
        if "'" in part or not part:
            raise DeployError("maintenance_command_refused")
    return (
        "node -e "
        + "'const http=require(\"http\");"
        + "const req=http.request({"
        + "hostname:\"127.0.0.1\","
        + "port:"
        + str(int(port))
        + ","
        + "path:"
        + json.dumps(path)
        + ","
        + "method:"
        + json.dumps(method)
        + ","
        + "headers:{"
        + json.dumps(header)
        + ":process.env."
        + env_name
        + "}"
        + "},function(res){var d=\"\";res.on(\"data\",function(c){d+=c});"
        + "res.on(\"end\",function(){process.stdout.write(d+\"\\n\"+res.statusCode)})});"
        + "req.on(\"error\",function(e){console.error(e);process.exit(1)});"
        + "req.end()'"
    )


def command(prefix, container, method, path, header, env_name, port):
    """Monta o probe HTTP. O valor do token não aparece no argv."""
    if container == NODE:
        script = _node_http_script(method, path, header, env_name, port)
    else:
        script = _curl_script(method, path, header, env_name, port)
    if ">" in script or env_name + "=" in script:
        raise DeployError("maintenance_command_refused")
    return list(prefix) + ["exec", container, "sh", "-c", script]


def marker_command(prefix, container, path):
    return list(prefix) + ["exec", container, "cat", path]


def parse_http(completed):
    if getattr(completed, "returncode", 1) != 0:
        raise DeployError("maintenance_unreachable")
    lines = (getattr(completed, "stdout", "") or "").splitlines()
    if not lines or not lines[-1].strip().isdigit():
        raise DeployError("maintenance_unreadable")
    code = int(lines[-1].strip())
    raw = "\n".join(lines[:-1]).strip()
    if not raw:
        raise DeployError("maintenance_unreadable")
    try:
        body = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise DeployError("maintenance_unreadable") from exc
    if not isinstance(body, dict):
        raise DeployError("maintenance_unreadable")
    return code, body


def node_marker_phase(text):
    try:
        parsed = json.loads(text or "")
    except json.JSONDecodeError as exc:
        raise DeployError("maintenance_marker_unreadable") from exc
    phase = parsed.get("phase") if isinstance(parsed, dict) else None
    if phase not in KNOWN_PHASES:
        raise DeployError("maintenance_state_unknown")
    return phase


def jvm_marker_phase(text):
    compact = " ".join(str(text or "").split())
    key = ":phase :"
    index = compact.find(key)
    if index < 0:
        raise DeployError("maintenance_marker_unreadable")
    phase = compact[index + len(key):].split(" ", 1)[0].strip(",}{")
    if phase not in KNOWN_PHASES:
        raise DeployError("maintenance_state_unknown")
    return phase


def _node_view(code, body, require_quiescent):
    if code == 401:
        raise DeployError("maintenance_unauthorized")
    phase = body.get("phase")
    active = body.get("activeOperations")
    if phase not in KNOWN_PHASES or not isinstance(active, int) or body.get("durable") is not True:
        raise DeployError("maintenance_state_unknown")
    if require_quiescent and body.get("quiescent") is not True:
        raise DeployError("node_not_quiescent")
    return {"phase": phase, "activeOperations": active, "durable": True}


def _jvm_data(code, body):
    if code == 401:
        raise DeployError("maintenance_unauthorized")
    if code == 409 or (isinstance(body.get("error"), dict) and body["error"].get("code") == "maintenance_drain_timeout"):
        raise DeployError("jvm_drain_timeout")
    data = body.get("data") if code == 200 and body.get("ok") is True else None
    if not isinstance(data, dict):
        raise DeployError("maintenance_state_unknown")
    phase = data.get("phase")
    if phase not in KNOWN_PHASES:
        raise DeployError("maintenance_state_unknown")
    counts = {}
    for key, name in (
        ("active-writes", "activeWrites"),
        ("admitted-writes", "admittedWrites"),
        ("completed-writes", "completedWrites"),
        ("failed-writes", "failedWrites"),
        ("rejected-writes", "rejectedWrites"),
    ):
        # Live bridge JSON uses camelCase; lab mocks may still use kebab-case.
        value = data.get(name)
        if value is None:
            value = data.get(key)
        if not isinstance(value, int) or value < 0:
            raise DeployError("maintenance_state_unknown")
        counts[name] = value
    counts["phase"] = phase
    quiescent = data.get("quiescent")
    if quiescent is None:
        quiescent = data.get("quiescent?")
    counts["quiescent"] = quiescent is True
    return counts


def prove_quiescent(view):
    """activeWrites isolado não prova quiescência."""
    if view.get("phase") != "quiescent" or view.get("quiescent") is not True:
        raise DeployError("jvm_not_quiescent")
    if view["activeWrites"] != 0:
        raise DeployError("jvm_writes_active")
    if view["failedWrites"] != 0:
        raise DeployError("jvm_writes_failed")
    if view["admittedWrites"] != view["completedWrites"] + view["failedWrites"]:
        raise DeployError("jvm_writes_incomplete")
    return view


def prove_jvm_status(view):
    if view.get("phase") != "quiescent":
        raise DeployError("jvm_not_quiescent")
    if view["activeWrites"] != 0:
        raise DeployError("jvm_writes_active")
    if view["failedWrites"] != 0:
        raise DeployError("jvm_writes_failed")
    if view["admittedWrites"] != view["completedWrites"] + view["failedWrites"]:
        raise DeployError("jvm_writes_incomplete")
    return view


def prove_open(view):
    if view.get("phase") != "open" or view.get("activeWrites") != 0:
        raise DeployError("resume_failed")
    if view["failedWrites"] != 0:
        raise DeployError("jvm_writes_failed")
    if view["admittedWrites"] != view["completedWrites"] + view["failedWrites"]:
        raise DeployError("jvm_writes_incomplete")
    return view


def _call(wrapped, argv):
    return parse_http(wrapped(argv))


def _cat(wrapped, argv):
    completed = wrapped(argv)
    if getattr(completed, "returncode", 1) != 0:
        raise DeployError("maintenance_marker_unreadable")
    return getattr(completed, "stdout", "") or ""


def enter_node(wrapped, prefix):
    """POST /ops/maintenance/enter, relê o status e o marcador."""
    posted_code, posted = _call(wrapped, command(
        prefix, NODE, "POST", "/ops/maintenance/enter?timeoutMs=" + TIMEOUT_MS,
        NODE_HEADER, NODE_TOKEN_ENV, NODE_PORT,
    ))
    if posted_code == 401:
        raise DeployError("maintenance_unauthorized")
    if posted_code == 409 or posted.get("quiescent") is False:
        raise DeployError("node_maintenance_timeout")
    entered = _node_view(posted_code, posted, True)
    if entered["phase"] != "quiescent" or entered["activeOperations"] != 0:
        raise DeployError("node_not_quiescent")
    status_code, status = _call(wrapped, command(
        prefix, NODE, "GET", "/ops/maintenance/status",
        NODE_HEADER, NODE_TOKEN_ENV, NODE_PORT,
    ))
    current = _node_view(status_code, status, False)
    if status_code != 200 or current["phase"] != "quiescent" or current["activeOperations"] != 0:
        raise DeployError("node_not_quiescent")
    if node_marker_phase(_cat(wrapped, marker_command(prefix, NODE, NODE_MARKER))) != "quiescent":
        raise DeployError("maintenance_marker_mismatch")
    return current


def enter_jvm(wrapped, prefix):
    """POST /ops/maintenance/enter da ponte. Não força a fase."""
    posted_code, posted = _call(wrapped, command(
        prefix, JVM, "POST", "/ops/maintenance/enter?timeoutMs=" + TIMEOUT_MS,
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    if posted_code == 401:
        raise DeployError("maintenance_unauthorized")
    if posted_code != 200:
        _jvm_data(posted_code, posted)
        raise DeployError("jvm_not_quiescent")
    prove_quiescent(_jvm_data(posted_code, posted))
    status_code, status = _call(wrapped, command(
        prefix, JVM, "GET", "/ops/maintenance/status",
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    current = prove_jvm_status(_jvm_data(status_code, status))
    if jvm_marker_phase(_cat(wrapped, marker_command(prefix, JVM, JVM_MARKER))) != "quiescent":
        raise DeployError("maintenance_marker_mismatch")
    return current


def resume_node(wrapped, prefix):
    status_code, status = _call(wrapped, command(
        prefix, NODE, "GET", "/ops/maintenance/status",
        NODE_HEADER, NODE_TOKEN_ENV, NODE_PORT,
    ))
    current = _node_view(status_code, status, False)
    if status_code == 200 and current["phase"] == "open" and current["activeOperations"] == 0:
        if node_marker_phase(_cat(wrapped, marker_command(prefix, NODE, NODE_MARKER))) != "open":
            raise DeployError("maintenance_marker_mismatch")
        current["idempotent"] = True
        return current
    if current["phase"] not in ("quiescent", "frozen"):
        raise DeployError("resume_failed")
    posted_code, posted = _call(wrapped, command(
        prefix, NODE, "POST", "/ops/maintenance/resume",
        NODE_HEADER, NODE_TOKEN_ENV, NODE_PORT,
    ))
    if posted_code == 409:
        raise DeployError("resume_failed")
    opened = _node_view(posted_code, posted, False)
    if posted_code != 200 or opened["phase"] != "open" or opened["activeOperations"] != 0:
        raise DeployError("resume_failed")
    confirmed_code, confirmed = _call(wrapped, command(
        prefix, NODE, "GET", "/ops/maintenance/status",
        NODE_HEADER, NODE_TOKEN_ENV, NODE_PORT,
    ))
    final = _node_view(confirmed_code, confirmed, False)
    if confirmed_code != 200 or final["phase"] != "open" or final["activeOperations"] != 0:
        raise DeployError("resume_failed")
    if node_marker_phase(_cat(wrapped, marker_command(prefix, NODE, NODE_MARKER))) != "open":
        raise DeployError("maintenance_marker_mismatch")
    final["idempotent"] = False
    return final


def resume_jvm(wrapped, prefix):
    status_code, status = _call(wrapped, command(
        prefix, JVM, "GET", "/ops/maintenance/status",
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    current = _jvm_data(status_code, status)
    if status_code == 200 and current["phase"] == "open" and current["activeWrites"] == 0:
        prove_open(current)
        if jvm_marker_phase(_cat(wrapped, marker_command(prefix, JVM, JVM_MARKER))) != "open":
            raise DeployError("maintenance_marker_mismatch")
        current["idempotent"] = True
        return current
    if current["phase"] not in ("quiescent", "frozen"):
        raise DeployError("resume_failed")
    posted_code, posted = _call(wrapped, command(
        prefix, JVM, "POST", "/ops/maintenance/resume",
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    if posted_code != 200 or posted.get("ok") is not True:
        raise DeployError("resume_failed")
    opened = posted.get("data") if isinstance(posted.get("data"), dict) else {}
    if opened.get("phase") != "open":
        raise DeployError("resume_failed")
    confirmed_code, confirmed = _call(wrapped, command(
        prefix, JVM, "GET", "/ops/maintenance/status",
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    final = prove_open(_jvm_data(confirmed_code, confirmed))
    if jvm_marker_phase(_cat(wrapped, marker_command(prefix, JVM, JVM_MARKER))) != "open":
        raise DeployError("maintenance_marker_mismatch")
    final["idempotent"] = False
    return final


def node_status(wrapped, prefix):
    """GET /ops/maintenance/status. Contador ausente ou ilegível para a janela."""
    code, body = _call(wrapped, command(
        prefix, NODE, "GET", "/ops/maintenance/status",
        NODE_HEADER, NODE_TOKEN_ENV, NODE_PORT,
    ))
    if code != 200:
        _node_view(code, body, False)
        raise DeployError("maintenance_state_unknown")
    return _node_view(code, body, False)


def jvm_status(wrapped, prefix):
    """GET /ops/maintenance/status da ponte, com os cinco contadores."""
    code, body = _call(wrapped, command(
        prefix, JVM, "GET", "/ops/maintenance/status",
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    return _jvm_data(code, body)


def jvm_health(wrapped, prefix):
    """Lê /health. Não grava no Datomic."""
    code, body = _call(wrapped, command(
        prefix, JVM, "GET", "/health", JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ))
    data = body.get("data") if code == 200 and body.get("ok") is True else None
    if not isinstance(data, dict) or data.get("status") != "up":
        raise DeployError("jvm_unhealthy")
    maintenance = data.get("maintenance")
    if not isinstance(maintenance, dict):
        raise DeployError("datomic_integrity_unknown")
    phase = maintenance.get("phase")
    # Live /health maintenance block uses camelCase; lab mocks may use kebab-case.
    writes = maintenance.get("activeWrites")
    if writes is None:
        writes = maintenance.get("active-writes")
    if phase not in KNOWN_PHASES or not isinstance(writes, int):
        raise DeployError("datomic_integrity_unknown")
    return {"status": "up", "phase": phase, "activeWrites": writes}
