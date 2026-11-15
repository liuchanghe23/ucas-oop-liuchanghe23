#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PLATFORM="$ROOT/platform"
LOCAL="$ROOT/.local"
mkdir -p "$LOCAL/logs"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export PATH="$JAVA_HOME/bin:$PATH"
if [[ -f "$ROOT/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%$'\r'}
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || { echo "Invalid .env line" >&2; exit 1; }
    export "$line"
  done < "$ROOT/.env"
fi
compose() { docker compose --env-file "$ROOT/.env" -f "$ROOT/infra/compose.yaml" "$@"; }
case "${1:-doctor}" in
  doctor)
    java -version
    mvn -version
    git --version
    docker compose version
    docker info --format '{{.ServerVersion}}'
    ;;
  infra)
    if [[ ! -f "$ROOT/.env" ]]; then cp "$ROOT/.env.example" "$ROOT/.env"; fi
    compose up -d --wait --wait-timeout 180
    ;;
  build) cd "$PLATFORM"; mvn -B -ntp -DskipTests package ;;
  test) cd "$PLATFORM"; mvn -B -ntp test ;;
  start)
    service=${2:-svc-task-resource}
    case "$service" in
      svc-task-resource|svc-task-executor|svc-result-processor|svc-topo|svc-fault-scheduler|svc-reqrsp-proxy) ;;
      *) echo "Unknown service: $service" >&2; exit 1 ;;
    esac
    [[ -f "$ROOT/.env" ]] || { echo "Run dev.sh infra first" >&2; exit 1; }
    pidfile="$LOCAL/$service.pid"
    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      echo "$service is already running"; exit 0
    fi
    jar=$(find "$PLATFORM/$service/target" -maxdepth 1 -name '*-boot.jar' -print -quit)
    [[ -n "$jar" ]] || { echo "Run dev.sh build first" >&2; exit 1; }
    nohup java -jar "$jar" --spring.profiles.active=local >"$LOCAL/logs/$service.log" 2>&1 &
    echo $! >"$pidfile"
    echo "Started $service (pid $!); log: $LOCAL/logs/$service.log"
    ;;
  stop)
    for pidfile in "$LOCAL"/*.pid; do
      [[ -f "$pidfile" ]] || continue
      pid=$(cat "$pidfile")
      if [[ "$pid" =~ ^[0-9]+$ && -r /proc/$pid/cmdline ]] && tr '\0' ' ' <"/proc/$pid/cmdline" | grep -Fq "$PLATFORM/"; then
        kill "$pid"
      fi
    done
    ;;
  down) compose down ;;
  *) echo "Usage: bash scripts/dev.sh {doctor|infra|build|test|start [service]|stop|down}" >&2; exit 1 ;;
esac
