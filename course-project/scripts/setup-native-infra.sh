#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
[[ $(id -u) -eq 0 ]] || { echo "Run this script with sudo" >&2; exit 1; }
apt-get -o Acquire::ForceIPv4=true update
DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 -o Acquire::ForceIPv4=true install -y mysql-server redis-server
systemctl enable --now mysql redis-server
mysql --protocol=socket <<'SQL'
CREATE DATABASE IF NOT EXISTS spaceexploration CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'course'@'localhost' IDENTIFIED BY 'course_local';
GRANT ALL PRIVILEGES ON spaceexploration.* TO 'course'@'localhost';
SQL
tables=$(mysql --protocol=socket -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='spaceexploration'")
if [[ "$tables" == 0 ]]; then
  mysql --protocol=socket spaceexploration <"$ROOT/platform/scripts/spaceexploration.sql"
  mysql --protocol=socket spaceexploration <"$ROOT/infra/02-fixtures.sql"
else
  echo "Existing database detected; leaving its schema and data untouched."
fi
mysql --version
redis-cli ping
