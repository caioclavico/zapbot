#!/usr/bin/env bash
# Executado na VM após conferir o SHA-256 do arquivo recebido.
set -euo pipefail
archive="${1:?Informe o arquivo da imagem}"
image="${2:?Informe a tag esperada}"
[[ -s "$archive" && "$image" =~ ^zapbot:[0-9a-f]{40}$ ]] || exit 2
exec 8>"$(dirname "$archive")/../../.image-load.lock"
flock -n 8 || { echo 'Já existe um carregamento de imagem em andamento.' >&2; exit 1; }
docker_cmd=(docker)
if ! timeout 15s docker info >/dev/null 2>&1; then
  docker_cmd=(sudo -n docker)
fi
timeout 15s "${docker_cmd[@]}" info --format 'Docker={{.ServerVersion}} driver={{.Driver}} CPUs={{.NCPU}} RAM={{.MemTotal}}'
diagnostico() {
  echo "[$(date -u +%FT%TZ)] Recursos da VM durante o carregamento:"
  free -m || true
  df -h "$(dirname "$archive")" || true
  # CPU, espera de disco e swap; não inclui mensagens ou credenciais do bot.
  timeout 5s vmstat 1 2 || true
  ps -C dockerd,containerd,gzip -o pid,etime,pcpu,pmem,stat,comm || true
}
diagnostico
# Descomprimir fora do daemon distingue gzip lento da importação das camadas.
tar_file="${archive%.gz}"
trap 'rm -f "$tar_file"' EXIT
echo "[$(date -u +%FT%TZ)] Descomprimindo arquivo na VM (limite: 5min)."
timeout 5m gzip -dc "$archive" > "$tar_file"
echo "[$(date -u +%FT%TZ)] Descompressão concluída: $(stat -c %s "$tar_file") bytes."
echo "[$(date -u +%FT%TZ)] Importando camadas no Docker (limite: 20min)."
timeout --kill-after=30s 20m "${docker_cmd[@]}" load -i "$tar_file" &
load_pid=$!
(
  while kill -0 "$load_pid" 2>/dev/null; do
    sleep 30
    kill -0 "$load_pid" 2>/dev/null || break
    diagnostico
  done
) &
monitor_pid=$!
status=0
wait "$load_pid" || status=$?
kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true
if [[ "$status" != 0 ]]; then
  echo "Falha no docker load: código $status (124 indica timeout)." >&2
  diagnostico
  # Encerrar o cliente Docker não garante que o daemon interrompeu a importação.
  echo 'O daemon pode continuar importando camadas. Verifique-o antes de repetir o carregamento.' >&2
  exit "$status"
fi
timeout 30s "${docker_cmd[@]}" image inspect "$image" --format 'Imagem disponível: {{.Id}}'
rm -f "$archive"
echo "[$(date -u +%FT%TZ)] Carregamento confirmado."
