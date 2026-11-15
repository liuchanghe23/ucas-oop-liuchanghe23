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

At the initial environment-verification stage, no Kubernetes cluster or ChaosBlade Operator had been deployed. The later T11 increment below supersedes that environment status. Full fault injection, target-system deployment, Jaeger integration and the complete integration suite remain outside this local setup verification. Shared course infrastructure was not modified.

Raw build/test logs are local at /home/liuchanghe/.local/state/oop/; test details are in the ignored Maven target/surefire-reports directory.


## Defect-transfer increment (2026-11-15)

- New standalone svc-defect-transfer on WSL loopback port 8107; no database dependency.
- All 11 Maven reactor modules package successfully with tests skipped for this aggregate build. The new module was tested separately; Spotless check passes for its 16 Java files.
- 33 new tests pass (0 failures, 0 errors, 0 skipped), including template immutability, three-valued matching, evidence gaps, limits and abstract planning.
- Real HTTP smoke: all 15 drafts imported idempotently; T10/T11 synthetic flows generate auditable plans; five source hints extracted from the clean OpenTelemetry Demo checkout, all INFERRED.
- All 15 source-profile checks remain UNKNOWN, including 13 unimplemented rulesets. No dynamic defect verdict is claimed.
- Restart persistence verified by fetching a pre-existing plan ID after service restart.
- Source scan output and reports are ignored under course-project/.local/defect-transfer-demo/. See [implementation record](4.2阶段1实现与验证.md).
- Original scheduler test failures above remain unchanged; they are not included in the new module's passing test count.

Windows-to-WSL localhost forwarding for port 8107 did not pass the host-side check in this session. The verified access path is inside WSL (`curl http://127.0.0.1:8107/api/defect-transfer/health` or the demo script).


## T11 isolated real diagnostic increment (2026-11-15)

- Installed a dedicated local K3s v1.32.9+k3s1 node and ChaosBlade Operator/Tool 1.8.0; deployed 13 OpenTelemetry Demo 3.1.0 components in the isolated oop-t11 namespace using pinned registry image digests.
- Original platform scheduler successfully created and removed a real checkout-to-Kafka pod network-loss experiment. No original platform Java business code was changed.
- First valid run: baseline 3/3 orders delivered; 20-second 100% edge loss dropped 10 packets; all 5 accepted fault-window orders appeared in accounting by the recovery +10-second sample; the recovery control order also arrived.
- Cleanup verified independently: no ChaosBlade CR remains and the selected interface returned to noqueue with no filters.
- Verdict remains INCONCLUSIVE: runtime publish budget, correlated publish failures and image-to-source build provenance remain unverified. This does not prove protection or repair.
- The defect-transfer service remains static with executionEnabled=false; a separate diagnostic script drove this experiment through the scheduler.
- Reusable baseline/diagnostic/manifest scripts and sanitized evidence are checked in. Local tokens, kubeconfig, database passwords, images and full logs are ignored.
- See [T11 experiment record](T11首次真实实验记录.md).

- A repeat using the checked-in scripts passed the 3/3 baseline, returned 4 order responses and 1 client deadline, delivered all 4 visible order IDs after recovery, and restored the fault rules. The timed-out request has a matching payment event but its final business outcome is unresolved; the repeat is also INCONCLUSIVE. Both runs are retained.
