#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

jvm=odisseu-a6-jvm
node=odisseu-a6-node
project=odisseu-a6-cutover
repo=https://github.com/caioclavico/zapbot.git

fail() { printf '%s\n' "$*" >&2; exit 1; }

status() {
  docker inspect -f '{{.Name}} {{.Config.Image}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$jvm" "$node"
  if [[ $(docker inspect -f '{{.State.Running}}' "$node") == true ]]; then
    docker exec "$node" node -e '
      fetch("http://127.0.0.1:3001/health", {signal: AbortSignal.timeout(5000)})
        .then(async r => { console.log(r.status, await r.text()); if (!r.ok) process.exitCode = 1; })
        .catch(e => { console.error(e.message); process.exitCode = 1; });'
  fi
}

check_node_image() {
  docker run --rm --pull never --network none --entrypoint node "$node_image" -e '
    const fs = require("node:fs");
    for (const file of ["target/main.js", "scripts/healthcheck.js",
      "scripts/lib/group-allowlist.cjs", "scripts/lib/a6-maintenance.cjs"]) {
      fs.accessSync("/app/" + file, fs.constants.R_OK);
    }
    fs.accessSync("/app/assets", fs.constants.R_OK | fs.constants.X_OK);'
}

build() (
  workspace=$(mktemp -d)
  trap 'rm -rf -- "$workspace"' EXIT
  git -C "$workspace" init -q
  git -C "$workspace" fetch --depth=1 "$repo" "$commit"
  (umask 022; git -C "$workspace" checkout --detach FETCH_HEAD)
  [[ $(git -C "$workspace" rev-parse HEAD) == "$commit" ]] || fail "Commit incorreto"
  docker build --platform linux/amd64 -f "$workspace/Dockerfile.jvm" -t "$jvm_image" "$workspace"
  docker build --platform linux/amd64 -f "$workspace/Dockerfile" -t "$node_image" "$workspace"
  check_node_image
  printf 'IMAGENS_PRONTAS %s\n' "$tag"
)

label() {
  docker inspect -f "{{index .Config.Labels \"$2\"}}" "$1"
}

token() {
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" | sed -n "s/^$2=//p"
}

wait_healthy() {
  local container=$1 state i
  for ((i=0; i<60; i++)); do
    state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container")
    [[ $state != 'running healthy' ]] || return 0
    [[ $state == running* ]] || fail "$container: $state"
    sleep 5
  done
  fail "$container não ficou healthy em 5 minutos"
}

# As credenciais chegam pelo stdin ou pelo ambiente do container.
maintenance_js='
const [service, action] = process.argv.slice(1);
const isJvm = service === "jvm";
const port = isJvm ? 18090 : 3001;
const header = isJvm ? "X-A6-Maintenance-Token" : "X-A6-Node-Maintenance-Token";
const token = isJvm ? require("node:fs").readFileSync(0,"utf8").trim()
  : process.env.ODISSEU_NODE_MAINTENANCE_TOKEN;
if (!token) throw new Error("Token de manutenção ausente");
async function control(path, method = "GET") {
  const r = await fetch("http://127.0.0.1:" + port + "/ops/maintenance/" + path, {
    method, headers: {[header]: token}, signal: AbortSignal.timeout(10000)
  });
  if (!r.ok) throw new Error(service + " " + path + " HTTP " + r.status);
  const body = await r.json();
  return (body.data || body).phase;
}
(async () => {
  let phase = await control("status");
  if (action === "resume" && ["frozen", "quiescent", "draining"].includes(phase)) {
    await control("resume", "POST");
    phase = await control("status");
  }
  console.log(service + " maintenance=" + phase);
  if (phase !== "open") process.exitCode = 1;
})().catch(e => {console.error(e.message); process.exitCode = 1;});'

jvm_maintenance() {
  printf '%s' "$ODISSEU_BRIDGE_MAINTENANCE_TOKEN" |
    docker run --rm -i --pull never --network "container:$jvm" \
      --entrypoint node "$node_image" -e "$maintenance_js" jvm "$1"
}

base_compose_files() {
  local container=$1 dir=$2 path files
  files=$(label "$container" com.docker.compose.project.config_files)
  local -a paths
  IFS=, read -r -a paths <<< "$files"
  for path in "${paths[@]}"; do
    if [[ $path != "$dir"/docker-compose.odisseu-release.*.yml ]]; then
      printf '%s\n' "$path"
    fi
  done
}

deploy() {
  [[ $EUID == 0 ]] || fail "Rode deploy com sudo para ler o env protegido do Compose"
  command -v python3 >/dev/null || fail "python3 é necessário para validar os volumes"
  exec 9>/var/lock/odisseu-deploy.lock
  flock -n 9 || fail "Já existe um deploy em andamento"
  docker image inspect "$jvm_image" "$node_image" >/dev/null
  check_node_image
  wait_healthy "$jvm"
  wait_healthy "$node"
  if [[ $(docker inspect -f '{{.Config.Image}}' "$jvm") == "$jvm_image" &&
        $(docker inspect -f '{{.Config.Image}}' "$node") == "$node_image" ]]; then
    status
    printf 'Versão já aplicada: %s\n' "$tag"
    return
  fi

  local dir files path override backup volume source container
  local -a paths compose volumes archive_args=()
  dir=$(label "$jvm" com.docker.compose.project.working_dir)
  files=$(base_compose_files "$jvm" "$dir")
  [[ -d $dir && -n $files ]] || fail "Compose original ausente"
  for container in "$jvm" "$node"; do
    [[ $(label "$container" com.docker.compose.project) == "$project" &&
       $(label "$container" com.docker.compose.service) == "$container" &&
       $(label "$container" com.docker.compose.project.working_dir) == "$dir" &&
       $(base_compose_files "$container" "$dir") == "$files" ]] ||
      fail "Configuração Compose divergente em $container"
  done

  export ODISSEU_BRIDGE_MAINTENANCE_TOKEN ODISSEU_NODE_MAINTENANCE_TOKEN
  ODISSEU_BRIDGE_MAINTENANCE_TOKEN=$(token "$jvm" ODISSEU_BRIDGE_MAINTENANCE_TOKEN)
  ODISSEU_NODE_MAINTENANCE_TOKEN=$(token "$node" ODISSEU_NODE_MAINTENANCE_TOKEN)
  [[ -n $ODISSEU_BRIDGE_MAINTENANCE_TOKEN && -n $ODISSEU_NODE_MAINTENANCE_TOKEN ]] ||
    fail "Tokens de manutenção ausentes"

  cd "$dir"
  mapfile -t paths <<< "$files"
  compose=(docker compose -p "$project" --project-directory "$dir")
  for path in "${paths[@]}"; do
    [[ -r $path ]] || fail "Arquivo do Compose indisponível: $path"
    compose+=(-f "$path")
  done
  override="$dir/docker-compose.odisseu-release.$tag.yml"
  printf 'services:\n  %s:\n    image: %s\n  %s:\n    image: %s\n' \
    "$jvm" "$jvm_image" "$node" "$node_image" > "$override"
  compose+=(-f "$override")
  "${compose[@]}" config --format json | python3 -c '
import json, subprocess, sys
config = json.load(sys.stdin)
containers = json.loads(subprocess.check_output(["docker", "inspect", sys.argv[1], sys.argv[2]]))
for container, image in zip(containers, sys.argv[3:]):
    name = container["Name"].lstrip("/")
    service = config["services"][name]
    if service["image"] != image:
        sys.exit("Imagem incorreta para " + name)
    old_env = dict(value.split("=", 1) for value in container["Config"]["Env"])
    changed_env = [key for key, value in service.get("environment", {}).items()
                   if value is not None and old_env.get(key) != str(value)]
    if changed_env:
        sys.exit("Ambiente divergente para " + name + " em: " + ", ".join(sorted(changed_env)))
    actual = {m["Destination"]: (m["Type"], m.get("Name") if m["Type"] == "volume" else m["Source"], m["RW"])
              for m in container["Mounts"] if m["Type"] in ("volume", "bind")}
    expected = {}
    for mount in service.get("volumes", []):
        kind = mount["type"]
        if kind in ("volume", "bind"):
            source = mount.get("source")
            if kind == "volume":
                source = config["volumes"].get(source, {}).get("name")
            expected[mount["target"]] = (kind, source, not mount.get("read_only", False))
    if actual != expected:
        sys.exit("Volumes divergentes para " + name + " em: " +
                 ", ".join(sorted(k for k in actual.keys() | expected.keys() if actual.get(k) != expected.get(k))))
' "$jvm" "$node" "$jvm_image" "$node_image"

  jvm_maintenance status
  docker exec "$node" node -e "$maintenance_js" node status
  local volume_list
  volume_list=$(docker inspect -f '{{range .Mounts}}{{if eq .Type "volume"}}{{println .Name}}{{end}}{{end}}' "$jvm" "$node" |
    sed '/^$/d' | sort -u)
  mapfile -t volumes <<< "$volume_list"
  local has_datomic=false
  for volume in "${volumes[@]}"; do
    [[ $volume != "$project-datomic" ]] || has_datomic=true
    source=$(docker volume inspect -f '{{.Mountpoint}}' "$volume")
    [[ $source == /* && -d $source ]] || fail "Volume indisponível: $volume"
  done
  [[ $has_datomic == true ]] || fail "Volume Datomic esperado não encontrado"

  install -d -m 0700 /var/backups/odisseu-a6
  backup=$(mktemp -d "/var/backups/odisseu-a6/$tag.XXXXXX")
  docker inspect -f '{{.Name}} {{.Config.Image}} {{.Image}}' "$jvm" "$node" > "$backup/images.txt"
  printf 'Backup frio: %s\n' "$backup"

  local stage=backup
  recover_before_swap() {
    local rc=$?
    trap - EXIT
    if (( rc != 0 )); then
      if [[ $stage == backup ]]; then
        printf 'Falha antes da troca. Tentando restaurar os containers existentes.\n' >&2
        docker start "$jvm" && (wait_healthy "$jvm") && docker start "$node" || true
      fi
      printf 'Deploy interrompido na etapa %s. Diretório: %s\n' "$stage" "$backup" >&2
    fi
    exit "$rc"
  }
  trap recover_before_swap EXIT
  docker stop -t 90 "$node"
  docker stop -t 90 "$jvm"
  for container in "$node" "$jvm"; do
    local exit_code
    exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$container")
    [[ $exit_code == 0 || $exit_code == 143 ]] || fail "$container terminou com código $exit_code"
  done
  # O diretório _data é comum aos volumes locais. Arquive cada volume
  # por seu próprio caminho para manter os nomes distintos no backup.
  for volume in "${volumes[@]}"; do
    [[ -z $(docker ps -q --filter "volume=$volume") ]] || fail "Volume em uso: $volume"
    source=$(docker volume inspect -f '{{.Mountpoint}}' "$volume")
    archive_args+=("${source#/}")
  done
  tar --numeric-owner -C / -czf "$backup/volumes.tar.gz" "${archive_args[@]}"
  tar -tzf "$backup/volumes.tar.gz" >/dev/null
  sha256sum "$backup/volumes.tar.gz" > "$backup/volumes.sha256"
  stage=troca
  "${compose[@]}" up -d --no-deps --no-build --pull never "$jvm"
  wait_healthy "$jvm"
  jvm_maintenance resume
  "${compose[@]}" up -d --no-deps --no-build --pull never "$node"
  wait_healthy "$node"
  docker exec "$node" node -e "$maintenance_js" node resume
  docker exec "$node" node -e '
    fetch("http://127.0.0.1:3001/health", {signal: AbortSignal.timeout(5000)})
      .then(async r => {const h = await r.json(); console.log("WhatsApp " + h.whatsapp);
        if (!r.ok || h.whatsapp !== "READY") process.exitCode = 1;})
      .catch(e => {console.error(e.message); process.exitCode = 1;});'
  status
  trap - EXIT
  printf 'DEPLOY_OK %s backup=%s\n' "$tag" "$backup"
}

case "${1:-}" in
  status) [[ $# == 1 ]] || fail "Uso: $0 status"; status; exit ;;
  build|deploy) [[ $# == 2 && $2 =~ ^[0-9a-f]{40}$ ]] || fail "Informe o SHA completo do commit" ;;
  *) fail "Uso: $0 {build SHA|deploy SHA|status}" ;;
esac
commit=$2
tag="a6-${commit:0:12}-amd64"
jvm_image="odisseu-jvm:$tag"
node_image="odisseu-node:$tag"
"$1"
