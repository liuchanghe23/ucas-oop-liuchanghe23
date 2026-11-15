# 缺陷迁移服务：第一阶段

把一类故障的说明书（模板）与目标系统资料对照，列出哪些条件成立、哪些不成立、还缺什么证据，再生成供审核的实验操作单。

当前实现：15 条模板导入；不可变版本快照；带证据的系统画像；Go/Kafka 与 .NET 源码片段线索；T10/T11 的角色和条件匹配；抽象实验计划；HTTP 示例。

还未实现：真实故障执行、运行日志/数据库取证、动态结论、修改前后对比，以及另外 13 条模板的机器检查规则。那些模板会返回 RULESET_NOT_IMPLEMENTED。本服务无需 MySQL/Redis；现有平台服务仍使用原来的数据库。

## 在 WSL 中运行

从 course-project 目录执行：

```bash
bash scripts/dev.sh test-defect
cd platform
mvn -B -ntp -pl svc-defect-transfer -am -DskipTests package
cd ..
bash scripts/dev.sh start svc-defect-transfer
bash scripts/dev.sh demo-defect
# 可选：同时读取本机已有目标源码，演示待核实线索和全部 15 条预筛
bash scripts/dev.sh demo-defect --source-root ../../opentelemetry-demo
```

默认地址为 WSL 内的 http://127.0.0.1:8107；本次已验证 WSL 内访问，Windows 直接访问该端口尚未连通，可先在 WSL 运行示例。开发启动脚本会读取 .env；可设置 DEFECT_PORT、DEFECT_BIND_ADDRESS、DEFECT_STORE_DIR。默认只监听本机，接口没有登录功能。直接运行时，请从 platform 目录运行：

```bash
java -jar svc-defect-transfer/target/svc-defect-transfer-1.0.0-SNAPSHOT-boot.jar
```

持久化数据默认在 course-project/.local/defect-transfer；演示报告在 .local/defect-transfer-demo/<本次编号>/report.md。两者均不提交 Git。默认相对路径以启动工作目录为准，跨目录启动时可显式指定 DEFECT_STORE_DIR。

## API

统一前缀 /api/defect-transfer；JSON 成功返回 {"success":true,"data":...}，失败返回 {"success":false,"error":{"code":...,"message":...}}。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /health | 查看运行状态及 executionEnabled=false |
| POST | /templates/import | JSON {"markdown":"含 YAML 代码块的 Markdown"} |
| GET | /templates | 查看导入的所有模板版本，含原始内容 |
| GET | /templates/{id}/versions/{version} | 查看指定版本 |
| GET | /operational-specs | 查看结构化检查规则、来源摘要和审核状态 |
| POST | /profiles | 导入带证据的画像 |
| POST | /profiles/source-hints | 从调用者提供的源码片段提取待核实线索 |
| GET | /profiles/{id} | 查看不可变画像快照 |
| POST | /matches | {templateId,templateVersion,profileId,maxBindings} |
| GET | /matches/{id} | 查看角色、逐条件理由和缺口 |
| POST | /plans | 使用 examples/defect-transfer/plan-request.json 的结构 |
| GET | /plans/{id} | 查看计划、参数依据及阻塞原因 |

完整请求见 course-project/examples/defect-transfer；演示脚本会自动替换 profileId、matchId，避免手抄长编号。默认没有自动导入模板，需要调用导入接口或运行演示。

## 画像怎么填写

- entity：一个服务或组件；例如发消息的服务、消息队列、消费服务。
- edge：组件之间的方向关系。PUBLISHES 表示发布消息，DELIVERS 表示向消费者投递，PROTECTS 表示去重组件保护某个消费者。
- fact：具体事实，例如是否自动提交位点。含 value、certainty 和 evidenceIds。
- evidence：事实来源；记录位置 locator、覆盖范围 scope、说明 detail。人工依据 MANUAL 必须填写 reviewer。示例使用 SYNTHETIC，明确表示人为编写的演示资料。
- certainty：CONFIRMED 是已核实；INFERRED 是待核实线索；UNKNOWN 是尚不清楚。后两种都不会被当作确定判断。

CONFIRMED 表示调用者已经核实证据，服务只检查格式与引用关系，不保证上传资料真实。真实画像不能带 SYNTHETIC 依据。SOURCE_SNAPSHOT 与 DEPLOYMENT 要区分，源码线索也不能代替当前部署配置。

全局事实 inventory.complete、capabilities.complete、topology.complete 为 true 且有已确认依据时，才允许把枚举结果外推为该范围的不适用。尤其 capabilities.complete 表示“每个组件的相关能力都完整列出”，topology.complete 表示“相关方向关系完整列出”。若不能保证，省略或填写未知；不要为了得到明确结果随意声明完整。缺少普通事实（如 outbox.present）始终为 UNKNOWN，不能用完整拓扑声明推断“不存在补偿”。

角色按模板 needs 检查，模板规则中不含 checkout/accounting 等目标服务名。候选优先考虑已有关系和已确认能力；maxBindings 为 1–512，超过上限会标记 ENUMERATION_TRUNCATED，保留未完成状态。一个绑定不适用不代表整个系统不适用。可选 guard 未绑定也不证明没有去重；需要 consumer 的 dedup.present 事实或有依据的 PROTECTS 关系。

## 首批检查表

代码读取 operational-specs.json 中的结构化规则，绝不执行模板的中文表达式、Shell 或任意脚本。规则通过模板编号、版本、原始 YAML 的 SHA-256 摘要绑定；修改模板后必须新增版本并明确更新规则。审核状态当前为 IMPLEMENTATION_REVIEW_REQUIRED；草稿的事故来源仍未核实。

| 模板 | 必要事实（角色名后的字段） |
| --- | --- |
| T10 / DT-MQ-REDELIVER-001 | broker.capability.at_least_once_delivery；consumer.capability.side_effect_write；DELIVERS；consumer.sideEffectBeforeCommit=true 或 commitMode=periodic |
| T10 缺陷条件 | consumer.dedup.present=false，或者 dedup.stableKey=false / dedup.atomicWithEffect=false；检查结果保留每个 D 编号 |
| T11 / DT-DUALWRITE-LOST-001 | producer 的 side_effect_write/publish 能力；broker.queue；downstream.consume；PUBLISHES、DELIVERS；producer.sideEffectBeforePublish=true；effectAndPublishIndependent=true |
| T11 缺陷条件 | producer.durableRetry.present=false，且 outbox.present、cdc.present、reconciliation.present 全为 false |

上表简写的组件能力都放在 capability.<名称> 事实中。FALSE 的静态缺陷条件仍保留候选，可用于后续防护实验；它还不能直接得到“已防护”的动态结论。

源码片段接口格式示例：

```json
{"systemId":"demo","revision":"实际提交号","snippets":[
  {"entityId":"writer","path":"producer.go","adapter":"go","content":"config.Producer.RequiredAcks = sarama.NoResponse"}
]}
```

Go 仅识别 NoResponse 的确认方式，.NET 仅识别 EnableAutoCommit=true、SaveChanges/SaveChangesAsync 与唯一键冲突处理的线索。全部输出 INFERRED，附文件名和行号；不分析完整控制流程，不推断未出现的防护，不读取服务器任意文件。示例脚本可在本机读取指定的 OpenTelemetry Demo 源码并上传片段，这部分路径只属于演示，不属于匹配规则。

## 计划中的状态

- applicability / suitability：说明模板的必要条件是否满足。
- defectConditions：说明静态缺陷条件是否成立；不等于实验已确认缺陷。
- reviewInputsComplete：生成操作单所需的条件、参数、声明能力是否齐全。
- readiness：NOT_APPLICABLE、EVIDENCE_UNAVAILABLE、PARAMETER_UNRESOLVED 或 UNSUPPORTED；所有阻塞原因另存 blockers，优先显示适用性与证据缺口。
- executionEnabled：本阶段始终为 false。即使审核输入齐全，仍会有 EXECUTION_ADAPTER_NOT_IMPLEMENTED，计划为 UNSUPPORTED。
- conclusion：本阶段始终为 NOT_EVALUATED，不输出 PRESENT、PROTECTED 等动态结论。

执行器和观察器能力 catalog 是调用者提供的、带版本与证据的声明，不是服务自动探测结果。首阶段没有真实地址/Pod 选择器、ChaosBlade 故障字典验证和观察器实现，因此不会把声明完整称为可以执行，也不生成假的 CR。后续将以实际适配器替换这些声明检查。

T11 用 producer.publish.totalBudgetMs（正整数毫秒，必须含实际尝试、退避、总截止时间）× 2 得到中断时间，不直接使用重试次数乘超时；重试为 0 也不会得到 0 毫秒。恢复等待取 producer.downstreamRecoveryBoundMs，必须明确给出。publish.acks 需要已核实，ack=0 不能用本地发送成功代替消息到达；原模板要求的“关联业务键的发布失败”观察能力缺失时返回证据不足。这个参数解释作为 t11-v1 的显式策略保存，没有偷偷改原模板。

T10 保留模板 5 分钟、每分钟 1 次的参数；PRECISE 要求准确的副作用后/提交前能力，PROBABILISTIC 明确表示随机窗口策略。观察规则仍要求同一消息实际重投，重启本身不算激活。

负载有明确业务关联键、速率、总数；limits 显式限制故障时长和负载。超限会阻塞并保留原计算值，不截断后声称可以实验。请求总数与速率还必须足以覆盖整个故障窗口；T10 的 5 分钟示例以每秒 2 条、共 600 条配置，演示脚本会自动调整。计划列出 Exposure（扰动是否到达）、Activation（机制是否启动）、Consequence（后果）和 Protection（防护），供后续执行与取证实现。

## 存储和后续工作

第一阶段使用单服务进程的不可变 JSON 文件快照，写入临时文件后原子移动；同一模板版本不同内容返回 409，完全相同的重复导入不改变记录。批量导入先校验全部模板再写入，但不提供跨文件的断电事务保证。不要用多个进程共享同一目录。后续数据量增加或需要多实例时，再按设计迁移独立数据库表。

下一阶段优先补 T11 的真实部署画像、业务键取证、ChaosBlade 能力检查和恢复流程；完成一条真实实验后，再扩展五条验证和修复对照。

## T11 独立真实诊断

已通过独立脚本调用平台故障调度器完成一次真实链路丢包、下单、恢复和数据库核验，见 [实验记录](../../docs/T11首次真实实验记录.md)。本服务仍未提供 runs 接口，静态示例和真实诊断分别保存；不能将该实验当作本服务已经具备自动动态判定。
