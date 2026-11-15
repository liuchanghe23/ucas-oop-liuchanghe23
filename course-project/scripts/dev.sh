#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PLATFORM="$ROOT/platform"
LOCAL="$ROOT/.local"
mkdir -p "$LOCAL/logs"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export PATH="$JAVA_HOME/bin:$PATH"
if [[ ! -f "$ROOT/.env" ]]; then cp "$ROOT/.env.example" "$ROOT/.env"; fi
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
    if [[ ${INFRA_MODE:-docker} == native ]]; then
      systemctl is-active mysql redis-server
      redis-cli ping
    else
      compose up -d --wait --wait-timeout 180
    fi
    ;;
  build) cd "$PLATFORM"; mvn -B -ntp -DskipTests package ;;
  test) cd "$PLATFORM"; mvn -B -ntp test ;;
  test-unit)
    cd "$PLATFORM"
    mvn -B -ntp -pl svc-fault-scheduler -am -Dtest=SpecNormalizerTest,FaultsServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
    ;;
  start)
    service=${2:-svc-task-resource}
    case "$service" in
      svc-task-resource|svc-task-executor|svc-result-processor|svc-topo|svc-fault-scheduler|svc-reqrsp-proxy) ;;
      *) echo "Unknown service: $service" >&2; exit 1 ;;
    esac
    [[ -f "$ROOT/.env" ]] || { echo "Run dev.sh infra first" >&2; exit 1; }
    unit="oop-$service"
    if systemctl --user is-active --quiet "$unit"; then
      echo "$service is already running"; exit 0
    fi
    jar=$(find "$PLATFORM/$service/target" -maxdepth 1 -name '*-boot.jar' -print -quit)
    [[ -n "$jar" ]] || { echo "Run dev.sh build first" >&2; exit 1; }
    systemd-run --user --collect --unit="$unit"       --property="WorkingDirectory=$PLATFORM"       --property="EnvironmentFile=$ROOT/.env"       --property="StandardOutput=append:$LOCAL/logs/$service.log"       --property="StandardError=append:$LOCAL/logs/$service.log"       "$JAVA_HOME/bin/java" -jar "$jar" --spring.profiles.active=local
    echo "Started $service; log: $LOCAL/logs/$service.log"
    ;;
  stop)
    for service in svc-task-resource svc-task-executor svc-result-processor svc-topo svc-fault-scheduler svc-reqrsp-proxy; do
      if systemctl --user is-active --quiet "oop-$service"; then
        systemctl --user stop "oop-$service"
      fi
    done
    ;;
  down)
    if [[ ${INFRA_MODE:-docker} == native ]]; then
      echo "Native services persist; stop with: sudo systemctl stop mysql redis-server"
    else
      compose down
    fi
    ;;
  *) echo "Usage: bash scripts/dev.sh {doctor|infra|build|test|test-unit|start [service]|stop|down}" >&2; exit 1 ;;
esac
