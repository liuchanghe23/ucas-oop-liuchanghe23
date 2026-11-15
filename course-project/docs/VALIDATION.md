# Environment validation

Date: 2026-11-15. Platform baseline: upstream main at e000f5c0e07d929be06f81b4616841690ad513d3.

## Passed

- Ubuntu 26.04 on WSL 2; networking and package-manager DNS work after disabling WSL safeMode.
- OpenJDK 21.0.13; Maven 3.9.12; Git 2.53.0.
- Docker Engine 29.1.3 and Compose 2.40.3 installed; daemon is active.
- Native MySQL 8.4.13 and Redis 8.0.5 installed and active. Course database has 22 tables; Redis PING returns PONG.
- All 10 Maven reactor modules pass clean package with tests skipped for packaging.
- Bash syntax checks and dev.sh doctor pass.
- svc-task-resource starts and connects to MySQL. GET /hello, /api/systems and /api/fault-types return success; the lists are empty because no course test data was inserted.
- svc-result-processor starts; GET /hello returns success.
- Credential scan found no copied API keys or JWT credentials in new source files. Local .env and logs are ignored.

## Unit tests: failures retained and documented

The explicit test-unit command runs SpecNormalizerTest and FaultsServiceTest using JUnit 5:

    Tests run: 23, failures: 3, errors: 1, skipped: 0

19 pass. Four existing main-branch issues remain:

1. SpecNormalizerTest.testNormalizeWithFullCR: normalization mutates immutable nested metadata and throws UnsupportedOperationException.
2. SpecNormalizerTest.testToYaml: generated YAML does not match the test's expected string format.
3. FaultsServiceTest.testExecuteAlreadyExists: test expects IllegalArgumentException, but the service wraps it in RuntimeException.
4. FaultsServiceTest.testExecuteInvalidSpec: same exception-type mismatch.

The two production classes and two test classes were compared with the original main checkout and are unchanged. These failures do not come from the WSL setup; fixing the behavior or revising its contract is separate work. The test command exits nonzero, without suppressing failures.

## Development changes

- Replaced copied hardcoded credentials with environment variables and local endpoints.
- Added local profiles, native database setup, optional Compose setup and user-systemd application launchers.
- Removed the inherited default Boot repackage execution. It ran before each child's classified execution, causing the classified jar to use JarLauncher as its own Start-Class. The rebuilt resource jar now has TaskResourceApplication as Start-Class.
- Added Surefire 3.5.3 so JUnit 5 tests in inherited modules actually execute.
- Added bounded Maven HTTP timeouts and disabled connection pooling for unstable download connections.

## Scope and limitations

Docker Hub image downloads time out on the current network. Redis's official public ECR image downloaded, but MySQL image download did not; no Compose stack is claimed to be running. Native MySQL/Redis are the working local infrastructure.

No Kubernetes cluster or ChaosBlade Operator has been deployed. Full fault injection, target-system deployment, Jaeger integration and the complete integration suite remain outside this local setup verification. Shared course infrastructure was not modified.

Raw build/test logs are local at /home/liuchanghe/.local/state/oop/; test details are in the ignored Maven target/surefire-reports directory.
