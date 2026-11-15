# WSL development environment

Read the Chinese guide in this directory for full instructions.

    cd /mnt/c/Users/KYBJB-0201/Desktop/oop/ucas-oop-liuchanghe23
    bash course-project/scripts/dev.sh doctor
    bash course-project/scripts/dev.sh infra
    bash course-project/scripts/dev.sh build
    bash course-project/scripts/dev.sh test-unit
    bash course-project/scripts/dev.sh start svc-task-resource
    bash course-project/scripts/dev.sh start svc-result-processor

Default infrastructure mode is native: Ubuntu MySQL 8.4 and Redis 8. Docker Engine and Compose are also installed; compose.yaml provides an optional MySQL 8.4 / Redis 7 setup. Stop native services before switching to Docker mode to avoid port conflicts.

A fresh machine can use sudo bash course-project/scripts/setup-wsl.sh. The separate setup-native-infra.sh initializes only an empty course database and preserves an existing schema.

The application launcher uses persistent user systemd units. Logs go to course-project/.local/logs/. Use dev.sh stop to stop application services; native databases remain running until sudo systemctl stop mysql redis-server.

The platform snapshot is upstream main at e000f5c. The parent Boot repackage execution was removed because it duplicated child executions and produced an incorrect JarLauncher start class. JUnit 5 support was added using Surefire 3.5.3. Credentials are environment variables and local .env is ignored.

Full Kubernetes fault injection and the integration test suite require external infrastructure and are not covered by the local startup check. See VALIDATION.md for measured results.
