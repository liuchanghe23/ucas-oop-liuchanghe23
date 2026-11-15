# WSL development environment

Enter Ubuntu using wsl -d Ubuntu. The course checkout is:

    cd /mnt/c/Users/KYBJB-0201/Desktop/oop/ucas-oop-liuchanghe23

For a new installation:

    sudo bash course-project/scripts/setup-wsl.sh

Reopen Ubuntu after installation to activate docker group membership.

Daily commands:

    bash course-project/scripts/dev.sh doctor
    bash course-project/scripts/dev.sh infra
    bash course-project/scripts/dev.sh build
    bash course-project/scripts/dev.sh start svc-task-resource
    curl --fail http://localhost:8101/hello
    bash course-project/scripts/dev.sh start svc-result-processor
    curl --fail http://localhost:8104/hello

The infra command creates local MySQL 8.4 and Redis 7. Ports bind only to localhost. Development credentials come from .env.example, copied into ignored .env on first use. Schema initializes only on a new volume.

    bash course-project/scripts/dev.sh test
    bash course-project/scripts/dev.sh stop
    bash course-project/scripts/dev.sh down

The down command preserves data volumes. Logs are in course-project/.local/logs/.

This Docker Engine runs inside Ubuntu and is separate from Windows Docker Desktop. Java 21 and Maven are Ubuntu binaries.

Full fault injection requires Kubernetes, ChaosBlade Operator, Jaeger and a target system. No shared course server is modified. The fault scheduler requires Kubernetes even at startup. The local resource profile disables remote topology synchronization and the upstream Spring Cloud compatibility check.

The platform is a snapshot of upstream main at e000f5c. Existing coursework history is preserved. Use Windows Git for authenticated pushes and WSL for editing/building. Avoid simultaneous Git writes from both systems.

See VALIDATION.md for actual verification results.
