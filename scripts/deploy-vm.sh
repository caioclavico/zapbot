#!/usr/bin/env bash
# Executado na VM somente depois de carregar a imagem pelo SSH.
set -euo pipefail
export PATH="$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export ZAPBOT_APP_DIR="${1:?Informe o diretório do bot}"
revision="${2:?Informe o SHA do commit}"
[[ "$ZAPBOT_APP_DIR" =~ ^/[a-zA-Z0-9_./-]+$ && "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
release_dir="$ZAPBOT_APP_DIR/releases/$revision"
export ZAPBOT_IMAGE="zapbot:$revision"
[[ -s "$ZAPBOT_APP_DIR/.env" && -f "$release_dir/docker-compose.production.yml" ]]
# A ausência deste endereço poderia fazer o bot iniciar sem persistência.
grep -Eq '^CASSANDRA_CONTACT_POINTS=.+$' "$ZAPBOT_APP_DIR/.env"
mkdir -p "$ZAPBOT_APP_DIR/data" "$ZAPBOT_APP_DIR/.wwebjs_auth"
exec 9>"$ZAPBOT_APP_DIR/.deploy.lock"
flock -n 9 || { echo 'Já existe um deploy em andamento.' >&2; exit 1; }

docker_cmd=(docker)
compose_runner=(env)
if ! docker info >/dev/null 2>&1; then
  docker_cmd=(sudo -n docker)
  compose_runner=(sudo -n env)
fi
"${docker_cmd[@]}" info >/dev/null
"${docker_cmd[@]}" image inspect "$ZAPBOT_IMAGE" >/dev/null
# Passa somente estas duas variáveis depois do sudo, que limpa o ambiente.
# Resolve a imagem a cada chamada para também respeitar o rollback.
compose() {
  "${compose_runner[@]}" "ZAPBOT_APP_DIR=$ZAPBOT_APP_DIR" "ZAPBOT_IMAGE=$ZAPBOT_IMAGE" \
    docker compose --project-name zapbot -f "$release_dir/docker-compose.production.yml" "$@"
}
compose config --quiet
previous_image=$("${docker_cmd[@]}" inspect --format '{{.Config.Image}}' zapbot 2>/dev/null || true)

rollback() {
  echo 'Deploy falhou; tentando restaurar a imagem anterior.' >&2
  if [[ -n "$previous_image" ]]; then
    ZAPBOT_IMAGE="$previous_image" compose up -d --no-build --pull never --no-deps bot || true
  fi
}
# Mantém a referência para rollback manual, sem copiar/tocar nas credenciais.
if [[ -n "$previous_image" ]]; then
  printf '%s\n' "$previous_image" > "$ZAPBOT_APP_DIR/previous-image"
fi
trap rollback ERR
# Só recria o bot. Não remove órfãos nem volumes do banco antigo.
compose up -d --no-build --pull never --no-deps bot
container_id=$("${docker_cmd[@]}" inspect --format '{{.Id}}' zapbot)
started=$("${docker_cmd[@]}" inspect --format '{{.State.StartedAt}}' "$container_id")
ready=0
for attempt in $(seq 1 60); do
  state=$("${docker_cmd[@]}" inspect --format '{{.State.Running}} {{.RestartCount}}' "$container_id")
  [[ "$state" == 'true 0' ]] || { echo 'Bot parou ou reiniciou durante o deploy.' >&2; false; }
  logs=$("${docker_cmd[@]}" logs --since "$started" "$container_id" 2>&1)
  if grep -q 'seguindo sem persistência' <<< "$logs"; then
    echo 'Falha ao conectar o Cassandra remoto.' >&2
    false
  fi
  if grep -q 'Conectado ao Cassandra; estado particionado carregado' <<< "$logs" &&
     "${docker_cmd[@]}" exec "$container_id" node scripts/healthcheck.js >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 5
done
if [[ "$ready" != 1 ]]; then
  echo 'WhatsApp sem saúde confirmada após 300s. Container preservado; consulte /health e os logs.' >&2
  # Demora não autoriza destruir/recriar um cliente potencialmente saudável.
  # Marca o workflow como pendente de diagnóstico sem rollback automático.
  trap - ERR
  exit 1
fi
printf '%s\n' "$revision" > "$ZAPBOT_APP_DIR/deployed-revision"
trap - ERR
echo "Deploy confirmado: $revision (Cassandra e WhatsApp prontos)."
