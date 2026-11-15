#!/usr/bin/env bash
set -euo pipefail
if [[ $(id -u) -ne 0 ]]; then
  echo "Run: sudo bash course-project/scripts/setup-wsl.sh" >&2
  exit 1
fi
apt-get -o Acquire::ForceIPv4=true update
DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 -o Acquire::ForceIPv4=true install -y openjdk-21-jdk-headless maven git curl unzip zip jq make ca-certificates docker.io docker-compose-v2
systemctl enable --now docker
if [[ -n ${SUDO_USER:-} && ${SUDO_USER} != root ]]; then
  usermod -aG docker "$SUDO_USER"
  loginctl enable-linger "$SUDO_USER"
fi
printf '%s\n' 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64' >/etc/profile.d/oop-java.sh
java -version
mvn -version
docker compose version
bash "$(dirname -- "${BASH_SOURCE[0]}")/setup-native-infra.sh"
echo "Reopen Ubuntu after adding your user to the docker group."
