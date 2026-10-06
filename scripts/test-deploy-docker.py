#!/usr/bin/env python3
"""Opt-in real Docker fixture: no game domain, WhatsApp client or Cassandra.

Builds three tiny fixture layers over an already validated Node runtime image.
Only containers and one volume carrying this test's unique name are removed.
The registry pull is replaced by a local image map; Engine create/stop/start,
readiness, persistence and rollback use the real local Docker socket.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


spec = importlib.util.spec_from_file_location("deployment", Path(__file__).with_name("deploy-service.py"))
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


def command(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True)
    if result.returncode:
        print("Local fixture command error: " + result.stderr)
        raise RuntimeError("Local Docker fixture command failed")
    return result.stdout.strip()


def check_installer_permissions(base_image):
    # Only key/sudo syntax validators are stubbed; account IDs, Unix permission
    # checks and the installed filesystem are real, inside a disposable container.
    shell = """
set -euo pipefail
mkdir /tmp/validators
mkdir -p /etc/sudoers.d
for tool in ssh-keygen visudo python3; do
  printf '#!/bin/sh\nexit 0\n' > "/tmp/validators/$tool"
  chmod 755 "/tmp/validators/$tool"
done
export PATH="/tmp/validators:$PATH"
useradd --uid 2345 --create-home --home-dir /home/zapbot-deploy --shell /bin/sh zapbot-deploy
chmod 750 /home/zapbot-deploy
printf 'ssh-ed25519 AAAA fixture-public-key\n' > /tmp/fixture.pub
bash /bootstrap/install-deploy.sh odisseu fixture /tmp/fixture.pub --confirm-reviewed-persistence-v1
key=/home/zapbot-deploy/.ssh/authorized_keys
test "$(id -u zapbot-deploy)" = '2345'
test "$(getent passwd zapbot-deploy | cut -d: -f6)" = '/home/zapbot-deploy'
test "$(stat -c '%u:%a' /home/zapbot-deploy)" = '2345:750'
test "$(stat -c '%u:%a' /home/zapbot-deploy/.ssh)" = '0:755'
test "$(stat -c '%u:%a' "$key")" = '0:644'
test "$(cat "$key")" = 'restrict,command="/usr/bin/python3 /usr/local/lib/zapbot-deploy/deploy-ssh-command.py odisseu" ssh-ed25519 AAAA fixture-public-key'
runuser -u zapbot-deploy -- test -r "$key"
runuser -u zapbot-deploy -- test ! -w "$key"
test "$(stat -c '%u:%a' /etc/zapbot-deploy/odisseu)" = '0:700'
test "$(stat -c '%u:%a' /etc/zapbot-deploy/odisseu/config.json)" = '0:600'
runuser -u zapbot-deploy -- test ! -r /etc/zapbot-deploy/odisseu/config.json
sudoers=/etc/sudoers.d/zapbot-deploy-odisseu
test "$(stat -c '%u:%a' "$sudoers")" = '0:440'
test "$(cat "$sudoers")" = 'zapbot-deploy ALL=(root) NOPASSWD: /usr/local/sbin/zapbot-deploy-odisseu'
test "$(stat -c '%u:%a' /usr/local/sbin/zapbot-deploy-odisseu)" = '0:755'
case " $(id -nG zapbot-deploy) " in *' docker '*) exit 1;; esac
printf '%s\n' '-----BEGIN OPENSSH PRIVATE KEY-----' 'fixture-private-key' > /tmp/private.pub
if bash /bootstrap/install-deploy.sh odisseu fixture /tmp/private.pub --confirm-reviewed-persistence-v1; then
    echo 'Installer accepted a private key.' >&2
    exit 1
fi
test "$(cat "$key")" = 'restrict,command="/usr/bin/python3 /usr/local/lib/zapbot-deploy/deploy-ssh-command.py odisseu" ssh-ed25519 AAAA fixture-public-key'
"""
    command("run", "--rm", "--user", "0:0", "--entrypoint", "/bin/bash",
            "-v", str(Path(__file__).resolve().parent) + ":/bootstrap:ro", base_image, "-c", shell)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", default="/var/run/docker.sock")
    parser.add_argument("--base-image", default="zapbot-pokemon:cicd-validated")
    parser.add_argument("--service", choices=("odisseu", "pokemon"), default="pokemon")
    args = parser.parse_args()
    if not args.socket.startswith("/"):
        raise SystemExit("A local Unix Docker socket is required.")
    endpoint = command("context", "inspect", "--format", '{{(index .Endpoints "docker").Host}}')
    if not endpoint.startswith("unix://") or Path(endpoint[7:]).resolve() != Path(args.socket).resolve():
        raise SystemExit("Docker CLI and fixture engine must use the same local Unix socket.")
    if not re.fullmatch(r"[A-Za-z0-9/:_.@-]+", args.base_image):
        raise SystemExit("Invalid fixture base image.")
    suffix = uuid.uuid4().hex[:12]
    name = "cicd-fixture-" + suffix
    volume = name + "-data"
    prefix = "ghcr.io/fixture/" + ("zapbot" if args.service == "odisseu" else "zapbot-pokemon") + ":"
    images = {}
    messages = []
    engine = deployment.Engine(args.socket)
    original_containers = {row["Id"] for row in engine.active()}
    try:
        with tempfile.TemporaryDirectory(prefix="zapbot-docker-fixture-") as temp:
            root = Path(temp)
            for version, broken in (("a", False), ("b", False), ("c", True)):
                (root / "fixture.cjs").write_text(
                    "const fs=require('node:fs'),h=require('node:http');"
                    f"const broken={str(broken).lower()};"
                    + ("fs.writeFileSync('/app/.wwebjs_auth/fixture-session',broken?'failed-candidate-auth':'retained-session-fixture');"
                       if args.service == 'odisseu' else '') +
                    "const server=h.createServer((req,res)=>{"
                    "res.writeHead(broken?503:200,{'Content-Type':'application/json'});"
                    "res.end(JSON.stringify(req.url==='/ready'?{ready:!broken}:{status:broken?'error':'ok',whatsapp:broken?'STARTING':'READY',chromium:true}));"
                    f"}}).listen({3001 if args.service == 'odisseu' else 8080},'0.0.0.0');"
                    "console.log('Conectado ao Cassandra; estado particionado carregado.');"
                    "process.on('SIGTERM',()=>{fs.writeFileSync('/app/data/stopped','graceful');"
                    "server.close(()=>process.exit(0));});"
                )
                (root / "Dockerfile").write_text(
                    f"FROM {args.base_image}\n"
                    f"LABEL io.zapbot.service={args.service} io.zapbot.persistence-version=1\n"
                    f"LABEL io.zapbot.fixture-version={version}\n"
                    f"LABEL org.opencontainers.image.revision={version * 40}\n"
                    "COPY fixture.cjs /app/fixture.cjs\nCMD [\"node\",\"/app/fixture.cjs\"]\n"
                )
                tag = name + ":" + version
                command("build", "--platform", "linux/amd64", "-t", tag, str(root))
                images[prefix + version * 40] = engine.image(tag)["Id"]
            if args.service == "pokemon":
                command("volume", "create", volume)
            app, state, config = (root / part for part in ("app", "state", "config"))
            for directory in (app, state, config):
                directory.mkdir(mode=0o700)
            (app / ".env").write_text("POKEMON_READ_ONLY=true\n")
            (app / ".env").chmod(0o600)
            (config / "persistence-version").write_text("1\n")
            (config / "persistence-version").chmod(0o600)
            binds = [volume + ":/app/data:rw"]
            ports = {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8080"}]}
            if args.service == "odisseu":
                for directory in (app / "data", app / ".wwebjs_auth"):
                    directory.mkdir()
                (app / ".wwebjs_auth/fixture-session").write_text("retained-session-fixture")
                binds = [str(app / "data") + ":/app/data:rw",
                         str(app / ".wwebjs_auth") + ":/app/.wwebjs_auth:rw"]
                ports = {}
            old_id = engine.create(name, {
                "Image": images[prefix + "a" * 40], "Hostname": "fixture-preserved-hostname",
                "WorkingDir": "/app", "Cmd": ["node", "/app/fixture.cjs"],
                "Env": ["CASSANDRA_CONTACT_POINTS=fixture.invalid", "POKEMON_READ_ONLY=false", "PORT=8080"],
                "HostConfig": {"Binds": binds, "Memory": 128 * 1024**2,
                               "MemorySwap": 256 * 1024**2, "ShmSize": 64 * 1024**2,
                               "RestartPolicy": {"Name": "unless-stopped"},
                               "PortBindings": ports},
                "ExposedPorts": {key: {} for key in ports},
            })
            engine.start(old_id)
            before = engine.inspect(old_id)

            class LocalOnlyEngine(deployment.Engine):
                def pull(self, image, registry_auth=None):
                    assert image in images, "Only locally built fixture images are allowed"

                def image(self, image):
                    return super().image(images.get(image, image))

            local = LocalOnlyEngine(args.socket)

            def instance():
                job = deployment.Deployment(args.service, local, app_dir=app, state_dir=state,
                                            config_dir=config, owner_uid=os.getuid(), timeout=5,
                                            stable_seconds=0.2, poll_seconds=0.2, output=messages.append)
                job.name = name  # Never use production container names, even locally.
                return job

            instance().run("deploy", prefix + "b" * 40)
            good = engine.inspect(name)
            assert good["Image"] == images[prefix + "b" * 40]
            assert good["Config"]["Env"] == before["Config"]["Env"]
            assert good["Config"]["Hostname"] == before["Config"]["Hostname"]
            for field in ("Memory", "MemorySwap", "ShmSize", "PortBindings", "RestartPolicy", "Binds"):
                assert good["HostConfig"][field] == before["HostConfig"][field], field
            if args.service == "pokemon":
                assert good["Mounts"][0]["Name"] == volume
            else:
                assert (app / ".wwebjs_auth/fixture-session").read_text() == "retained-session-fixture"
            assert (app / ".env").read_text() == "POKEMON_READ_ONLY=true\n"
            try:
                instance().run("deploy", prefix + "c" * 40)
                raise AssertionError("Broken health must fail deployment")
            except deployment.DeployError:
                pass
            assert engine.inspect(name)["Image"] == images[prefix + "b" * 40]
            assert engine.probe(name, args.service)
            assert any("rollback healthy" in message for message in messages)
            if args.service == 'odisseu':
                # Verify a retained container remounts the restored directory,
                # not the inode archived as the failed candidate's evidence.
                value = command('exec', name, 'node', '-e',
                    "process.stdout.write(require('node:fs').readFileSync('/app/.wwebjs_auth/fixture-session','utf8'))")
                assert value == 'retained-session-fixture'
                failed = list((app / '.zapbot-auth-recovery').glob('*-failed/fixture-session'))
                assert len(failed) == 1 and failed[0].read_text() == 'failed-candidate-auth'
            instance().run("rollback")
            assert engine.inspect(name)["Id"] == old_id
            assert engine.probe(name, args.service)
            assert json.loads((state / "state.json").read_text())["last-good-image-id"] == before["Image"]
            assert not (state / "transaction.json").exists()
            assert (app / ".env").stat().st_mode & 0o777 == 0o600
            check_installer_permissions(args.base_image)
            print(f"Real local Docker ({args.service}): deployment, failed health, automatic/manual rollback and persistence passed.")
    except Exception:
        print("Fixture deployment status:\n" + "\n".join(messages))
        fixture = engine.inspect(name)
        print("Fixture process state: " + json.dumps({key: fixture["State"].get(key)
              for key in ("Running", "ExitCode", "OOMKilled", "Restarting")}))
        try:
            print("Fixture-only process log: " + engine.logs(name, fixture["State"]["StartedAt"]).decode(errors="replace"))
        except deployment.DeployError:
            print("Fixture-only log request failed.")
        raise
    finally:
        # Filter by this run's random name, never prune or touch pre-existing IDs.
        listing = command("ps", "-a", "--format", "{{.ID}} {{.Names}}")
        for line in listing.splitlines():
            identifier, container_name = line.split(" ", 1)
            if container_name == name or container_name.startswith(name + "-"):
                command("rm", "-f", identifier)
        assert {row["Id"] for row in engine.active()} >= original_containers
        if args.service == "pokemon":
            subprocess.run(["docker", "volume", "rm", volume], capture_output=True)
        for version in ("a", "b", "c"):
            subprocess.run(["docker", "image", "rm", name + ":" + version], capture_output=True)


if __name__ == "__main__":
    main()
