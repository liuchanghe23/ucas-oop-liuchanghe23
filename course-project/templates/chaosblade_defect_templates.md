# ChaosBlade 缺陷模板库

本文件给出 15 条缺陷模板，作为 4.1“基于事故报告的缺陷知识提取”的目标格式参考，以及 4.2“基于异构系统的缺陷迁移”的输入示例。

模板内容按业界常见的失效模式编写，**并非从具体事故报告中抽取**。因此所有模板的 `status` 均为 `draft`，`provenance` 为占位，需要在用公开事故报告完成 4.1 抽取后回填来源和逐字段证据。

---

## 1. 模板结构说明

一条模板表达的是一个可证伪的缺陷假设：在具备某种结构的系统里，某种扰动会让某个机制产生违背预期的后果。字段从模块需要输出的结论倒推设计。

| 字段                    | 作用                                                   | 支撑的判定      |
| --------------------- | ---------------------------------------------------- | ---------- |
| `mechanism`           | 模板所属的机制类别，用于组织模板库                                    | —          |
| `roles`               | 抽象因果角色及其所需能力。不出现服务名和技术栈，能力识别由按技术栈注册的适配器完成            | 迁移匹配       |
| `applicability`       | 适用条件，按 `topology` / `config` / `code` / `runtime` 分级 | 不存在（不适用）   |
| `defect_conditions`   | 适用前提下，防护缺失或防护存在漏洞的条件，用 `all_of` / `any_of` 组合        | 静态预判、已防护   |
| `activation`          | 激活扰动：抽象故障类型、目标角色、注入点、相对参数、负载要求、执行映射提示                | 未触发、执行器不支持 |
| `failure_chain`       | 失效过程，每步可挂观测信号                                        | 解释与人工审核    |
| `oracles.activation`  | 证明扰动已作用到目标机制上的信号：机制被触发，或机制本应响应的条件已经达到                | 未触发        |
| `oracles.consequence` | 预期后果                                                 | 存在         |
| `oracles.invariant`   | 被违反的业务或系统不变量                                         | 存在         |
| `fix_signatures`      | 修复特征                                                 | 已修复        |
| `provenance`          | 来源事故实例与逐字段证据状态（`confirmed` / `inferred` / `missing`） | 可追溯性       |

几点约定：

- **参数相对化**：`activation.params` 中的数值写成相对系统画像的表达式，如 `${caller.timeout} * 1.5`，不照搬原事故中的绝对数值。迁移时由画像中匹配到的实际值代入。
- **注入点**：`inject_point` 取值包括 `on_request`（默认）、`after_side_effect`、`after_side_effect_before_commit`、`during_write_path`、`inside_critical_section`。执行器若无法表达指定的注入点，应输出“执行器不支持”，不得退化为普通注入，否则可能掩盖缺陷导致误判。
- **实例与模板分离**：事故实例单独存储，保留具体服务名、数值和时间线；模板只保留角色化后的结构，通过 `provenance.instances` 关联实例。只有单一来源的模板需要显式标注。
- **粒度**：一条模板对应“一个机制 × 一种失效方式”。

---

## 2. 通用判定规则

按顺序判定，命中即停：

1. 任一 `applicability` 条件被证据证伪 → **不存在（不适用）**。
2. 任一 `applicability` 条件因证据不可得而未知；或执行器不支持 `inject_point`；或 `consequence` 指标无法观测 → **无法判定**，并注明原因。
3. 注入后 `oracles.activation` 信号未出现 → **未被触发**。应调整扰动参数或负载后重试，不下结论。
4. `activation` 出现且 `consequence` 出现 → **缺陷存在**。
5. `activation` 出现但 `consequence` 未出现：
   - 静态判断 `defect_conditions` 不成立（找到了防护）→ **已修复 / 已防护**。若找到的防护不在 `fix_signatures` 中，提示补充模板。
   - 静态判断 `defect_conditions` 成立 → 静态与动态矛盾 → **无法判定**，回头修正系统画像或检查观测。
   - `defect_conditions` 未知 → **无法判定（证据不足）**。

对概率性后果（如重复执行、并发重叠），以多次运行为准，建议至少 3 次，并在结论中报告命中次数。

---

## 3. 模板索引

| 编号  | 模板 ID                 | 名称                | 机制   | 激活扰动           | 被违反的不变量         |
| --- | --------------------- | ----------------- | ---- | -------------- | --------------- |
| T01 | DT-RETRY-DUP-001      | 超时重试导致写操作重复执行     | 重试   | 被调方响应延迟（副作用之后） | 副作用至多生效一次       |
| T02 | DT-RETRY-AMP-001      | 多层重试叠加导致负载放大与亚稳态  | 重试   | 底层依赖部分报错       | 短暂故障移除后系统可自行恢复  |
| T03 | DT-TIMEOUT-INV-001    | 超时预算倒挂导致孤儿请求      | 超时   | 最下游响应延迟        | 上游放弃后下游及时停止处理   |
| T04 | DT-CB-SLOW-001        | 熔断不统计慢调用          | 熔断   | 被调方响应延迟（低于超时）  | 单依赖变慢不影响无关接口    |
| T05 | DT-POOL-SHARE-001     | 多依赖共享资源池无隔离       | 舱壁隔离 | 次要依赖响应延迟       | 非核心依赖故障不影响核心接口  |
| T06 | DT-CACHE-STAMPEDE-001 | 缓存失效后并发回源击穿       | 缓存   | 缓存不可用或清空       | 缓存失效时存储负载在容量内   |
| T07 | DT-CACHE-STALE-001    | 删缓存失败导致长期读到旧值     | 缓存   | 服务到缓存链路短时丢包    | 写入后 T 秒内读到新值    |
| T08 | DT-FAILOVER-CONN-001  | 主备切换后客户端仍持有旧主连接   | 故障转移 | 杀死数据库主节点       | 切换完成后 T 秒内写入恢复  |
| T09 | DT-REPLICA-RYW-001    | 复制延迟导致读不到自己的写入    | 读写分离 | 复制链路网络延迟       | 用户能读到自己已提交的写入   |
| T10 | DT-MQ-REDELIVER-001   | 消息重投导致重复消费        | 消息投递 | 杀死消费者进程        | 每条消息的副作用至多生效一次  |
| T11 | DT-DUALWRITE-LOST-001 | 副作用与发消息非原子导致下游缺失  | 消息投递 | 生产者到消息队列链路丢包   | 已提交的业务实体最终有下游处理 |
| T12 | DT-PROBE-CASCADE-001  | 存活探针依赖下游引发全量重启    | 健康检查 | 外部依赖短时不可用      | 外部依赖故障不导致实例重启   |
| T13 | DT-GRAY-LB-001        | 健康检查发现不了慢节点       | 负载均衡 | 单实例响应延迟        | 劣化实例 T 秒内被隔离    |
| T14 | DT-FALLBACK-DEP-001   | 降级路径依赖同一故障域       | 降级   | 主依赖所在故障域不可用    | 主依赖不可用时降级结果可用   |
| T15 | DT-LOCK-LEASE-001     | 锁租约过期后原持有者继续执行临界区 | 分布式锁 | 临界区内依赖延迟       | 同一时刻至多一个执行者     |

---

## 4. 模板

### T01 超时重试导致有副作用的写操作重复执行

典型形态：重复扣款、重复发货、重复发券。“幂等配置了但仍失效”的情况由 D2 覆盖。

```yaml
template_id: DT-RETRY-DUP-001
name: 超时重试导致有副作用的写操作重复执行
version: 1
status: draft
mechanism: retry
summary: >
  调用方对写请求超时即重试。被调方已提交副作用但响应晚于调用方超时，
  重试使同一业务请求的副作用生效多次。

roles:
  caller: {needs: [sync_call, retry_on_timeout]}
  callee: {needs: [side_effect_write]}
  guard:  {needs: [dedup], optional: true}

applicability:
  - {id: A1, level: topology, expr: "caller 同步调用 callee"}
  - {id: A2, level: config,   expr: "caller 的重试触发条件包含超时，且最大尝试次数 >= 2"}
  - {id: A3, level: code,     expr: "callee 在返回响应前提交副作用"}

defect_conditions:
  any_of:
    - {id: D1, expr: "guard 不存在"}
    - {id: D2, expr: "guard 存在，但去重记录在处理完成后才写入，不覆盖进行中的请求"}

activation:
  fault_type: response_delay
  target: callee
  inject_point: after_side_effect
  params: {delay: "${caller.timeout} * 1.5", duration: 120s}
  workload: "至少 50 笔经过 caller→callee 的写请求，带可追踪的业务键"
  executor_hint: "被调方服务端口出向流量的网络延迟。不可退化为入向延迟：传播截止时间的框架可能直接取消首次请求，从而掩盖缺陷"

failure_chain:
  - {step: 1, desc: "callee 完成副作用，响应被拖慢"}
  - {step: 2, desc: "caller 超时并重试",     signal: activation}
  - {step: 3, desc: "callee 再次执行副作用", signal: consequence}

oracles:
  activation:  "caller 侧对该接口的重试次数 > 0"
  consequence: "存在业务键 k，callee 侧副作用记录数(k) > 1"
  invariant:   "每个业务请求的副作用至多生效一次"

fix_signatures:
  - "幂等键 + 进行中请求占位（唯一约束或锁）"
  - "写操作只在连接建立失败时重试，超时不重试"

provenance:
  instances: []    # 待 4.1 抽取后回填
  evidence: {}
```

### T02 多层重试叠加导致负载放大与亚稳态失效

典型形态：重试风暴。扰动移除后系统仍无法恢复，是“亚稳态失效”的常见成因。

```yaml
template_id: DT-RETRY-AMP-001
name: 多层重试叠加导致负载放大与亚稳态失效
version: 1
status: draft
mechanism: retry
summary: >
  调用链上多层各自重试且缺少重试预算。底层依赖短暂变慢或部分报错时，
  请求量按各层重试次数相乘放大；扰动移除后，积压的重试流量使依赖持续过载，系统无法自行恢复。

roles:
  upper:   {needs: [sync_call, retry]}
  middle:  {needs: [sync_call, retry]}
  backend: {needs: [capacity_bound]}    # 有容量上限的依赖，如数据库、核心服务

applicability:
  - {id: A1, level: topology, expr: "upper → middle → backend 构成同步调用链"}
  - {id: A2, level: config,   expr: "upper 与 middle 均配置重试，且 upper.max_attempts × middle.max_attempts >= 4"}

defect_conditions:
  all_of:
    - {id: D1, expr: "各层重试均没有重试预算（重试请求占比上限）"}
    - {id: D2, expr: "backend 前没有熔断、过载拒绝或自适应限流"}
  # 无指数退避与抖动会加剧放大，但不单独构成缺陷条件

activation:
  fault_type: error_injection    # 也可用 response_delay
  target: backend
  inject_point: on_request
  params: {error_rate: 30%, duration: 60s}
  workload: "入口流量维持在 backend 容量的 60%–70%，扰动移除后继续保持至少 5 分钟"
  executor_hint: "backend 进程内异常注入或服务端口部分丢包；负载需由压测工具持续施加"

failure_chain:
  - {step: 1, desc: "backend 部分请求失败"}
  - {step: 2, desc: "middle 与 upper 先后重试，请求量相乘放大", signal: activation}
  - {step: 3, desc: "backend 饱和，扰动移除后仍不恢复",       signal: consequence}

oracles:
  activation:  "upper 与 middle 的重试次数均 > 0"
  consequence: "backend 请求量 / 入口请求量 > 1.5；且扰动移除 5 分钟后 backend 仍饱和、入口错误率高于基线"
  invariant:   "依赖短暂故障移除后，系统在 T（如 2 分钟）内恢复到基线"

fix_signatures:
  - "重试预算（如重试请求不超过总请求的 10%）"
  - "只在最靠近故障的一层重试，其他层透传失败"
  - "指数退避 + 抖动，配合熔断"

provenance:
  instances: []
  evidence: {}
```

### T03 超时预算倒挂导致孤儿请求与资源浪费

典型形态：用户看到失败，但下游实际已经执行；下游资源被已无人等待的请求占用。

```yaml
template_id: DT-TIMEOUT-INV-001
name: 超时预算倒挂导致孤儿请求与资源浪费
version: 1
status: draft
mechanism: timeout
summary: >
  上游超时小于下游完成一次调用所需的最长时间（含下游自身的重试）。
  下游变慢时上游已经放弃，下游仍在处理并占用资源；用户侧失败，而下游实际已经执行。

roles:
  caller:     {needs: [sync_call, timeout]}
  callee:     {needs: [sync_call, timeout]}    # callee 还会调用更下游
  downstream: {needs: [any]}

applicability:
  - {id: A1, level: topology, expr: "caller → callee → downstream 构成同步调用链"}
  - {id: A2, level: config,   expr: "caller 与 callee 均配置了调用超时"}

defect_conditions:
  all_of:
    - {id: D1, expr: "caller.timeout <= callee.timeout × callee.max_attempts"}
    - {id: D2, expr: "callee 不感知上游取消：不传播截止时间，也不检查请求上下文是否已取消"}

activation:
  fault_type: response_delay
  target: downstream
  inject_point: on_request
  params:
    delay: "(${caller.timeout} + ${callee.timeout} * ${callee.max_attempts}) / 2"
    duration: 120s
  workload: "持续的 caller→callee→downstream 请求，开启全链路追踪"

failure_chain:
  - {step: 1, desc: "downstream 变慢"}
  - {step: 2, desc: "caller 先于 callee 超时并返回失败", signal: activation}
  - {step: 3, desc: "callee 继续等待并完成处理",         signal: consequence}

oracles:
  activation:  "caller 侧超时错误数 > 0"
  consequence: "按 trace 关联，callee 在 caller 超时之后仍完成的请求数 > 0；callee 线程或连接占用高于基线"
  invariant:   "上游已放弃的请求，下游应及时停止处理"

fix_signatures:
  - "逐跳传播截止时间，下游以剩余预算作为自身超时"
  - "下游检查上下文取消并提前退出"
  - "上游超时 > 下游超时 × 下游重试次数"

provenance:
  instances: []
  evidence: {}
```

### T04 熔断只按错误率统计，下游慢而不错时不打开

典型形态：熔断“配了等于没配”，调用方被慢调用拖垮。

```yaml
template_id: DT-CB-SLOW-001
name: 熔断只按错误率统计，下游慢而不错时不打开
version: 1
status: draft
mechanism: circuit_breaker
summary: >
  调用方配置了熔断，但只统计异常比例。下游明显变慢却未超时报错时，熔断保持关闭，
  调用方的工作线程或连接被慢调用占满，连带其他接口不可用。

roles:
  caller: {needs: [sync_call, circuit_breaker, bounded_worker_pool]}
  callee: {needs: [any]}

applicability:
  - {id: A1, level: topology, expr: "caller 同步调用 callee"}
  - {id: A2, level: config,   expr: "caller 对 callee 配置了熔断"}

defect_conditions:
  any_of:
    - {id: D1, expr: "熔断不统计慢调用（没有慢调用阈值或慢调用比例配置）"}
    - {id: D2, expr: "慢调用阈值 >= caller.timeout，实际等同于只统计超时"}

activation:
  fault_type: response_delay
  target: callee
  inject_point: on_request
  params: {delay: "${caller.timeout} * 0.8", duration: 120s}
  workload: "caller 请求速率 × delay > caller 工作池大小，确保池可被占满"

failure_chain:
  - {step: 1, desc: "callee 变慢但未超时"}
  - {step: 2, desc: "慢调用比例超过熔断本应响应的水平", signal: activation}
  - {step: 3, desc: "熔断保持关闭，caller 工作池耗尽",   signal: consequence}

oracles:
  activation:  "caller→callee 的慢调用比例 > 50%（熔断本应响应的条件已达到）"
  consequence: "熔断状态始终为 CLOSED；caller 工作池使用率达到上限，caller 上与 callee 无关的接口延迟或错误率上升"
  invariant:   "单个依赖变慢时，调用方与该依赖无关的接口不受影响"

fix_signatures:
  - "熔断同时统计慢调用比例，且慢调用阈值明显小于超时"
  - "按依赖隔离工作池（与 T05 的修复互补）"

provenance:
  instances: []
  evidence: {}
```

### T05 多个依赖共享同一资源池，单一依赖变慢拖垮全部接口

典型形态：推荐、广告等次要依赖变慢，导致下单、支付等核心接口一起失败。

```yaml
template_id: DT-POOL-SHARE-001
name: 多个依赖共享同一资源池，单一依赖变慢拖垮全部接口
version: 1
status: draft
mechanism: bulkhead
summary: >
  服务调用多个下游时共用同一个线程池、连接池或 HTTP 客户端实例。
  一个次要依赖变慢后占满共享池，访问其他健康依赖的核心接口也随之失败。

roles:
  service:   {needs: [shared_pool]}
  minor_dep: {needs: [any]}    # 次要依赖
  core_dep:  {needs: [any]}    # 核心依赖

applicability:
  - {id: A1, level: topology, expr: "service 同时调用 minor_dep 和 core_dep"}
  - {id: A2, level: code,     expr: "两类调用经过同一个线程池、连接池或客户端实例"}

defect_conditions:
  all_of:
    - {id: D1, expr: "没有按依赖划分的舱壁（独立池或并发上限）"}
    - {id: D2, expr: "对 minor_dep 的调用超时 × 请求速率足以占满共享池"}

activation:
  fault_type: response_delay
  target: minor_dep
  inject_point: on_request
  params: {delay: "${service.minor_dep.timeout} * 0.9", duration: 120s}
  workload: "同时施加访问 minor_dep 与 core_dep 的流量"

failure_chain:
  - {step: 1, desc: "minor_dep 变慢"}
  - {step: 2, desc: "共享池被 minor_dep 调用占满", signal: activation}
  - {step: 3, desc: "访问 core_dep 的请求排队或失败", signal: consequence}

oracles:
  activation:  "共享池使用率达到上限"
  consequence: "仅依赖 core_dep 的接口错误率或 p99 显著上升"
  invariant:   "非核心依赖故障不影响核心接口可用性"

fix_signatures:
  - "按依赖划分线程池或信号量并发上限"
  - "次要依赖异步化，或配置更短超时与降级"

provenance:
  instances: []
  evidence: {}
```

### T06 缓存失效后并发回源击穿数据库

典型形态：缓存节点故障或热点 key 集中过期，数据库被打满，连带写请求失败。

```yaml
template_id: DT-CACHE-STAMPEDE-001
name: 缓存失效后并发回源击穿数据库
version: 1
status: draft
mechanism: cache
summary: >
  热点数据依赖缓存挡住读流量。缓存节点故障或热点 key 集中失效时，
  大量请求同时回源，缺少合并回源与回源限流，数据库过载并波及写请求。

roles:
  service: {needs: [cache_aside_read]}
  cache:   {needs: [kv_cache]}
  store:   {needs: [capacity_bound]}

applicability:
  - {id: A1, level: topology, expr: "service 读 cache，未命中时读 store"}
  - {id: A2, level: runtime,  expr: "正常情况下缓存命中率高（如 > 90%），store 容量按命中后的流量规划"}

defect_conditions:
  all_of:
    - {id: D1, expr: "未命中时没有合并回源（single-flight 或互斥重建）"}
    - {id: D2, expr: "回源路径没有限流或降级"}

activation:
  fault_type: cache_unavailable    # 也可用 cache_flush 清空热点 key
  target: cache
  inject_point: on_request
  params: {duration: 60s}
  workload: "读流量维持在日常峰值，并集中在少量热点 key"

failure_chain:
  - {step: 1, desc: "缓存不可用或热点 key 失效"}
  - {step: 2, desc: "读请求全部回源",       signal: activation}
  - {step: 3, desc: "store 过载，写请求受影响", signal: consequence}

oracles:
  activation:  "缓存命中率降至接近 0"
  consequence: "store 请求量升至基线的 5 倍以上，store 延迟或连接数超过阈值，写接口错误率上升"
  invariant:   "缓存不可用时 store 负载不超过容量，服务以降级方式保持可用"

fix_signatures:
  - "合并回源（single-flight）或热点 key 互斥重建"
  - "回源限流 + 降级返回"
  - "过期时间加随机抖动，热点 key 采用逻辑过期"

provenance:
  instances: []
  evidence: {}
```

### T07 写库后删缓存失败被忽略，导致长期读到旧值

典型形态：价格、库存、配置更新后，部分用户长时间看到旧值。

```yaml
template_id: DT-CACHE-STALE-001
name: 写库后删缓存失败被忽略，导致长期读到旧值
version: 1
status: draft
mechanism: cache
summary: >
  更新时先写数据库再删除缓存，删除失败只记日志不重试。删除恰好失败时，
  缓存中的旧值一直保留到过期，期间所有读请求都拿到旧数据。

roles:
  service: {needs: [cache_aside_write]}
  cache:   {needs: [kv_cache]}
  store:   {needs: [persistent_store]}

applicability:
  - {id: A1, level: code,   expr: "写路径为：写 store 后删除或更新 cache"}
  - {id: A2, level: config, expr: "缓存 TTL 较长或不设 TTL"}

defect_conditions:
  all_of:
    - {id: D1, expr: "缓存删除失败时只记录或吞掉异常，没有重试或异步补偿"}
    - {id: D2, expr: "没有基于数据库变更日志的缓存失效通道"}

activation:
  fault_type: network_loss
  target: "service → cache 的链路"
  inject_point: during_write_path
  params: {loss: 100%, duration: 5s}
  workload: "扰动期间对一批已缓存的 key 执行更新，扰动结束后持续读取"
  executor_hint: "service 容器到 cache 端口的出向丢包；窗口要短，避免读路径整体降级掩盖现象"

failure_chain:
  - {step: 1, desc: "store 写入成功"}
  - {step: 2, desc: "缓存删除失败且被忽略", signal: activation}
  - {step: 3, desc: "后续读命中旧缓存",     signal: consequence}

oracles:
  activation:  "扰动期间缓存删除操作失败次数 > 0"
  consequence: "扰动结束后，已更新的 key 读到的值与 store 不一致，且持续时间 > T"
  invariant:   "写入成功后 T 秒内（如 5 秒）读到新值"

fix_signatures:
  - "删除失败进入重试队列或异步补偿"
  - "订阅数据库变更日志失效缓存"
  - "延迟双删 + 较短 TTL（只能缩短不一致窗口）"

provenance:
  instances: []
  evidence: {}
```

### T08 数据库主备切换后客户端仍持有旧主连接

典型形态：数据库切换几十秒完成，应用却持续报错十几分钟，直到重启。

```yaml
template_id: DT-FAILOVER-CONN-001
name: 数据库主备切换后客户端仍持有旧主连接
version: 1
status: draft
mechanism: failover
summary: >
  数据库配置了主备自动切换，切换本身很快完成。但应用连接池复用长连接且不做有效性检查，
  或进程缓存了旧的 DNS 解析结果，切换完成后写请求仍持续失败，实际恢复时间远超切换时间。

roles:
  service:    {needs: [connection_pool]}
  db_cluster: {needs: [auto_failover]}

applicability:
  - {id: A1, level: topology, expr: "service 通过连接池访问 db_cluster 的主节点"}
  - {id: A2, level: config,   expr: "db_cluster 配置了自动主备切换，service 经域名或虚拟地址连接"}

defect_conditions:
  any_of:
    - {id: D1, expr: "连接池不做借出校验或空闲校验，且连接最大存活时间很长或不限"}
    - {id: D2, expr: "进程级 DNS 缓存 TTL 很长或永久"}
    - {id: D3, expr: "驱动在写到只读节点时不触发重连"}

activation:
  fault_type: process_kill    # 也可用 pod_delete
  target: "db_cluster 当前主节点"
  inject_point: on_request
  params: {duration: "直到切换完成"}
  workload: "持续写请求"

failure_chain:
  - {step: 1, desc: "主节点宕机"}
  - {step: 2, desc: "db_cluster 完成切换，新主可写", signal: activation}
  - {step: 3, desc: "service 仍向旧地址或旧连接写入", signal: consequence}

oracles:
  activation:  "db_cluster 完成切换（新主可写）"
  consequence: "切换完成后 service 写错误持续时间 > T"
  invariant:   "数据库切换完成后 T 秒内（如 30 秒）应用写入恢复"

fix_signatures:
  - "连接借出校验，或设置有限的连接最大存活时间"
  - "DNS 缓存 TTL 设为秒级"
  - "驱动识别只读错误并重建连接"

provenance:
  instances: []
  evidence: {}
```

### T09 读写分离下复制延迟导致读不到自己刚写的数据

典型形态：下单成功后订单列表里没有该订单，用户因此重复下单。

```yaml
template_id: DT-REPLICA-RYW-001
name: 读写分离下复制延迟导致读不到自己刚写的数据
version: 1
status: draft
mechanism: read_write_split
summary: >
  写请求走主库、读请求走只读副本。复制延迟变大时，用户刚完成的写操作在随后的读中不可见，
  可能诱发用户重复提交等二次错误。

roles:
  service: {needs: [read_write_split]}
  primary: {needs: [persistent_store]}
  replica: {needs: [async_replication]}

applicability:
  - {id: A1, level: config, expr: "service 的读流量路由到异步复制的副本"}
  - {id: A2, level: code,   expr: "存在写后立即由同一用户读取的业务流程（如下单后查看订单）"}

defect_conditions:
  all_of:
    - {id: D1, expr: "写后读既不强制走主库，也没有会话级一致性（如按写入位点等待副本）"}

activation:
  fault_type: network_delay
  target: "primary → replica 的复制链路"
  inject_point: on_request
  params: {delay: "${flow.write_to_read_interval} * 2", duration: 120s}
  workload: "按业务流程执行写入后立即读取，记录每次读是否可见"

failure_chain:
  - {step: 1, desc: "用户写入主库成功"}
  - {step: 2, desc: "复制延迟超过写后读间隔", signal: activation}
  - {step: 3, desc: "用户读副本看不到刚写的数据", signal: consequence}

oracles:
  activation:  "副本复制延迟 > 写后读间隔"
  consequence: "写后读不可见的比例 > 0"
  invariant:   "用户能读到自己已成功提交的写入"

fix_signatures:
  - "写后一段时间内该用户的读走主库"
  - "按写入位点（GTID / LSN）等待副本追上后再读"
  - "会话层或前端保留本次写入结果"

provenance:
  instances: []
  evidence: {}
```

### T10 消息重投导致消费者重复执行副作用

典型形态：消费者重启或重平衡后，同一笔订单被记两次账、发两次通知。

```yaml
template_id: DT-MQ-REDELIVER-001
name: 消息重投导致消费者重复执行副作用
version: 1
status: draft
mechanism: message_delivery
summary: >
  消息队列为至少一次投递。消费者先执行副作用、后提交消费位点；消费者崩溃或发生重平衡时，
  已处理但未提交的消息被重新投递，在没有有效幂等的情况下副作用重复生效。

roles:
  broker:   {needs: [at_least_once_delivery]}
  consumer: {needs: [side_effect_write]}
  guard:    {needs: [dedup], optional: true}

applicability:
  - {id: A1, level: topology, expr: "consumer 从 broker 消费并执行副作用"}
  - {id: A2, level: code,     expr: "副作用先于位点提交，或位点为定时自动提交"}

defect_conditions:
  any_of:
    - {id: D1, expr: "guard 不存在"}
    - {id: D2, expr: "去重键在重投后会变化（如投递标签），或去重记录与副作用不在同一事务"}

activation:
  fault_type: process_kill
  target: consumer
  inject_point: after_side_effect_before_commit
  params: {frequency: "每分钟 1 次", duration: 5m}
  workload: "持续生产带业务键的消息"
  executor_hint: "随机杀消费者进程可在概率上命中该窗口；自动提交间隔越长越容易命中，需多次运行"

failure_chain:
  - {step: 1, desc: "consumer 完成副作用，尚未提交位点"}
  - {step: 2, desc: "consumer 崩溃，消息被重新投递", signal: activation}
  - {step: 3, desc: "副作用再次执行",               signal: consequence}

oracles:
  activation:  "发生消费者重启或重平衡，且有消息被重新投递"
  consequence: "存在业务键 k，副作用记录数(k) > 1"
  invariant:   "每条消息的副作用至多生效一次"

fix_signatures:
  - "以业务键去重，去重记录与副作用在同一事务内写入"
  - "副作用与位点在同一事务中提交（事务性消费）"

provenance:
  instances: []
  evidence: {}
```

### T11 副作用与发消息非原子，消息发送失败导致下游缺失

典型形态：订单已扣款或已落库，但记账、发货、通知等下游处理永远不会发生。

```yaml
template_id: DT-DUALWRITE-LOST-001
name: 副作用与发消息非原子，消息发送失败导致下游缺失
version: 1
status: draft
mechanism: message_delivery
summary: >
  业务先提交副作用（写库或调用外部服务），再向消息队列发布事件，发布失败只记日志。
  消息队列短暂不可用时，副作用已经生效，依赖该事件的下游处理却永远不会发生。

roles:
  producer:   {needs: [side_effect_write, publish]}
  broker:     {needs: [queue]}
  downstream: {needs: [consume]}

applicability:
  - {id: A1, level: topology, expr: "producer 提交副作用后发布事件，downstream 消费该事件"}
  - {id: A2, level: code,     expr: "副作用提交与事件发布是两个独立操作"}

defect_conditions:
  all_of:
    - {id: D1, expr: "发布失败没有持久化重试（仅内存重试或直接丢弃）"}
    - {id: D2, expr: "没有事务性发件箱、变更日志投递或对账补偿"}

activation:
  fault_type: network_loss
  target: "producer → broker 的链路"
  inject_point: on_request
  params: {loss: 100%, duration: "${producer.publish_timeout} * ${producer.publish_retries} * 2"}
  workload: "扰动期间持续产生业务写入"

failure_chain:
  - {step: 1, desc: "producer 提交副作用"}
  - {step: 2, desc: "事件发布失败并被丢弃", signal: activation}
  - {step: 3, desc: "downstream 始终没有收到该事件", signal: consequence}

oracles:
  activation:  "扰动期间 producer 发布失败次数 > 0"
  consequence: "扰动结束并等待 T 后，仍存在副作用已生效但下游无对应记录的业务实体"
  invariant:   "每个已提交的业务实体最终都有对应的下游处理"

fix_signatures:
  - "事务性发件箱（outbox）+ 独立投递进程"
  - "基于数据库变更日志的事件投递"
  - "定时对账并补发"

provenance:
  instances: []
  evidence: {}
```

### T12 存活探针依赖下游，下游故障引发全量实例重启

典型形态：数据库抖动 10 秒，服务却因所有副本同时重启而中断数分钟。

```yaml
template_id: DT-PROBE-CASCADE-001
name: 存活探针依赖下游，下游故障引发全量实例重启
version: 1
status: draft
mechanism: health_check
summary: >
  实例的存活探针检查数据库、缓存等外部依赖。依赖短暂故障时，所有副本同时探活失败并被重启，
  冷启动、连接重建与流量重新涌入叠加，服务不可用时间远长于依赖故障本身。

roles:
  service:    {needs: [liveness_probe, replicas]}
  dependency: {needs: [any]}

applicability:
  - {id: A1, level: config,   expr: "service 配置了存活探针，副本数 >= 2"}
  - {id: A2, level: topology, expr: "service 依赖 dependency"}

defect_conditions:
  all_of:
    - {id: D1, expr: "存活探针的检查逻辑访问 dependency"}

activation:
  fault_type: service_unavailable    # 端口丢包或进程暂停
  target: dependency
  inject_point: on_request
  params: {duration: "${service.liveness.failure_threshold} * ${service.liveness.period} * 1.5"}
  workload: "正常业务流量"

failure_chain:
  - {step: 1, desc: "dependency 短暂不可用"}
  - {step: 2, desc: "所有副本存活探针失败", signal: activation}
  - {step: 3, desc: "副本被批量重启，恢复时间被拉长", signal: consequence}

oracles:
  activation:  "service 的存活探针失败次数 > 0"
  consequence: "多个副本在扰动期间被重启；服务不可用时长明显大于依赖故障时长"
  invariant:   "外部依赖故障不应导致实例重启"

fix_signatures:
  - "存活探针只检查进程自身，依赖检查放入就绪探针"
  - "依赖故障时降级服务而非整体摘除（视业务而定）"

provenance:
  instances: []
  evidence: {}
```

### T13 健康检查无法发现慢节点，负载均衡持续向其转发

典型形态：灰色故障。某台机器变慢但不宕机，长期贡献约 1/N 的超时。

```yaml
template_id: DT-GRAY-LB-001
name: 健康检查无法发现慢节点，负载均衡持续向其转发
version: 1
status: draft
mechanism: load_balancing
summary: >
  某个实例因资源争用或硬件劣化变得很慢，但进程存活、端口可达，健康检查仍然通过。
  负载均衡继续按原策略分配流量，约 1/N 的请求持续超时，整体尾延迟长期偏高。

roles:
  caller:   {needs: [load_balancer]}
  replicas: {needs: [health_check], count: ">= 3"}

applicability:
  - {id: A1, level: topology, expr: "caller 经负载均衡访问 replicas 的多个实例"}

defect_conditions:
  all_of:
    - {id: D1, expr: "健康检查只检查进程或端口存活，不反映请求延迟"}
    - {id: D2, expr: "负载均衡没有异常实例剔除，也不按延迟或在途请求数加权"}

activation:
  fault_type: response_delay    # 也可用 cpu_burn，但 IO 密集型服务可能不明显
  target: "replicas 中的一个实例"
  inject_point: on_request
  params: {delay: "${caller.timeout} * 0.7", duration: 300s}
  workload: "正常业务流量"

failure_chain:
  - {step: 1, desc: "单个实例变慢"}
  - {step: 2, desc: "该实例健康检查仍然通过", signal: activation}
  - {step: 3, desc: "该实例持续接收流量，整体尾延迟偏高", signal: consequence}

oracles:
  activation:  "被注入实例的请求延迟显著高于其他实例，且其健康检查保持通过"
  consequence: "扰动持续 T 之后，被注入实例的流量占比仍约为 1/N，整体 p99 未回落"
  invariant:   "单个实例劣化后 T 秒内（如 60 秒）被隔离，服务整体延迟恢复"

fix_signatures:
  - "负载均衡启用异常实例剔除（outlier detection）"
  - "最少请求或按延迟加权的负载均衡策略"
  - "健康检查包含真实请求路径的延迟判定"

provenance:
  instances: []
  evidence: {}
```

### T14 降级路径依赖同一故障域，主路径故障时降级同时失效

典型形态：降级逻辑写了，但从没在真实故障下跑过；故障来时降级本身也报错。

```yaml
template_id: DT-FALLBACK-DEP-001
name: 降级路径依赖同一故障域，主路径故障时降级同时失效
version: 1
status: draft
mechanism: fallback
summary: >
  主路径失败时切换到降级逻辑，但降级逻辑依赖的组件与主路径的故障依赖相同或处于同一故障域，
  例如降级数据来自同一个缓存集群，或降级开关存放在已不可用的配置中心。

roles:
  service:      {needs: [fallback]}
  primary_dep:  {needs: [any]}
  fallback_dep: {needs: [any], optional: true}

applicability:
  - {id: A1, level: code, expr: "service 对 primary_dep 的调用失败时执行降级逻辑"}

defect_conditions:
  any_of:
    - {id: D1, expr: "降级逻辑仍调用 primary_dep"}
    - {id: D2, expr: "fallback_dep 与 primary_dep 共享故障域（同一集群、同一节点、同一网络分区或同一配置中心）"}

activation:
  fault_type: fault_domain_unavailable    # 节点宕机、集群不可用或网络分区
  target: "primary_dep 所在故障域"
  inject_point: on_request
  params: {duration: 120s}
  workload: "会走到该依赖的正常业务流量"

failure_chain:
  - {step: 1, desc: "primary_dep 所在故障域不可用"}
  - {step: 2, desc: "service 进入降级逻辑", signal: activation}
  - {step: 3, desc: "降级逻辑因同一故障失败", signal: consequence}

oracles:
  activation:  "降级逻辑被调用次数 > 0"
  consequence: "降级逻辑执行失败，用户请求返回错误而非降级结果"
  invariant:   "主路径依赖不可用时，降级结果可用"

fix_signatures:
  - "降级数据本地化，或放在独立故障域"
  - "降级开关有本地默认值，不依赖配置中心实时可用"

provenance:
  instances: []
  evidence: {}
```

### T15 分布式锁租约过期后原持有者继续执行临界区

典型形态：超卖、同一任务被两个实例同时处理。

```yaml
template_id: DT-LOCK-LEASE-001
name: 分布式锁租约过期后原持有者继续执行临界区
version: 1
status: draft
mechanism: distributed_lock
summary: >
  临界区由带租约的分布式锁保护。持锁者因慢依赖、GC 停顿或网络延迟而超过租约时长，
  锁自动释放并被另一个实例获取，两者同时执行临界区，导致超卖或重复处理。

roles:
  worker:       {needs: [lease_lock], count: ">= 2"}
  lock_service: {needs: [lease]}
  resource:     {needs: [side_effect_write]}

applicability:
  - {id: A1, level: code,   expr: "worker 获取带过期时间的锁后修改 resource"}
  - {id: A2, level: config, expr: "多个 worker 实例竞争同一把锁"}

defect_conditions:
  all_of:
    - {id: D1, expr: "resource 写入不校验防护令牌（fencing token）或版本号"}
    - {id: D2, expr: "没有租约续期，或续期失败时不中止临界区"}
  # 注：续期可以防住慢依赖导致的超时，但防不住进程整体停顿；后者只能靠 D1 的防护令牌

activation:
  fault_type: response_delay    # 也可用进程暂停，此时续期同样失效
  target: "worker 在临界区内调用的依赖"
  inject_point: inside_critical_section
  params: {delay: "${lock.lease_ttl} * 1.5"}
  workload: "多个 worker 持续竞争同一资源"

failure_chain:
  - {step: 1, desc: "持锁 worker 在临界区内被拖慢"}
  - {step: 2, desc: "租约过期，锁被另一 worker 获取", signal: activation}
  - {step: 3, desc: "两个 worker 同时修改 resource",  signal: consequence}

oracles:
  activation:  "存在持锁时长超过租约的临界区执行"
  consequence: "同一资源上出现两个临界区执行时间重叠，或出现超卖、重复处理"
  invariant:   "同一时刻至多一个执行者修改同一资源"

fix_signatures:
  - "写入时校验单调递增的防护令牌"
  - "数据库条件更新或乐观锁兜底"
  - "租约续期，且续期失败立即中止临界区"

provenance:
  instances: []
  evidence: {}
```

---

## 5. 能力词表与适配器线索

模板中的 `needs` 只写能力名。下表给出部分能力的含义和识别线索示例，具体识别逻辑放在按技术栈注册的适配器里；新增技术栈只需增加适配器，不改模板。

| 能力                                               | 含义              | 识别线索示例                                                                                                |
| ------------------------------------------------ | --------------- | ----------------------------------------------------------------------------------------------------- |
| `sync_call`                                      | 同步调用另一服务        | 调用链追踪中的 client/server span；服务间 HTTP / gRPC 依赖                                                         |
| `retry_on_timeout` / `retry`                     | 调用失败或超时后自动重试    | Spring Retry 或 Resilience4j Retry 配置；gRPC service config 的 retryPolicy；Istio VirtualService 的 retries |
| `timeout`                                        | 调用设置了超时         | 客户端超时配置；gRPC deadline；网关或网格路由超时                                                                       |
| `circuit_breaker`                                | 熔断              | Resilience4j CircuitBreaker（含慢调用相关阈值）；Sentinel 熔断规则；Istio DestinationRule 的 outlierDetection          |
| `bounded_worker_pool` / `shared_pool`            | 有界工作池，或多个依赖共用的池 | 线程池、HTTP 客户端连接池、数据库连接池的定义与引用位置                                                                        |
| `side_effect_write`                              | 执行有外部可见副作用的写操作  | 写库、调用支付或物流等外部接口、发送通知                                                                                  |
| `dedup`                                          | 幂等或去重机制         | 幂等键字段、唯一约束、去重表、请求 ID 校验                                                                               |
| `cache_aside_read` / `cache_aside_write`         | 旁路缓存读写          | 先查缓存后查库的代码路径；写库后删或更新缓存                                                                                |
| `connection_pool`                                | 数据库连接池          | HikariCP 等连接池配置（最大存活时间、校验方式）；JVM DNS 缓存设置                                                             |
| `auto_failover`                                  | 自动主备切换          | 数据库高可用组件或托管数据库的切换配置                                                                                   |
| `read_write_split` / `async_replication`         | 读写分离与异步复制       | 数据源路由配置；只读副本连接串                                                                                       |
| `at_least_once_delivery` / `publish` / `consume` | 消息投递与收发         | 消息队列客户端配置（确认模式、位点提交方式）                                                                                |
| `liveness_probe` / `health_check`                | 存活探针、健康检查       | Kubernetes livenessProbe / readinessProbe；负载均衡健康检查配置                                                  |
| `load_balancer`                                  | 负载均衡            | Service / 网格的负载均衡策略；客户端负载均衡配置                                                                         |
| `fallback`                                       | 降级逻辑            | 降级方法、默认值返回、降级开关                                                                                       |
| `lease_lock` / `lease`                           | 带租约的分布式锁        | 分布式锁客户端调用；锁的过期时间与续期配置                                                                                 |

---

## 6. 使用建议

**用于 4.1**：这 15 条可以作为抽取目标的格式与机制分类参考。真实模板应从至少 10 份公开事故报告中抽取，每条模板的 `provenance.instances` 指向具体事故实例，`evidence` 逐字段标注 `confirmed` / `inferred` / `missing`。原文无法确认的字段必须标记，不能由大模型自行补全。同一机制在多份报告中反复出现时，合并为一条模板并关联多个实例。

**用于 4.2**：选择目标系统后，先用系统画像对每条模板做适用性判定，筛出适用条件成立或未知的模板，再从中选至少 5 条做实验。每条模板建议设计三组对照：

1. 原始版本（缺陷条件成立）→ 预期判为“存在”；
2. 加入某条 `fix_signatures` 后的版本 → 预期判为“已修复”；
3. 扰动参数设到机制触发阈值以下（如 T01 的延迟小于调用方超时）→ 预期判为“未被触发”。

以 otel-demo 为例，下单链路上较容易对上的候选是 T01、T03、T10、T11，但前提条件（如 checkout 调 payment 是否配置了超时重试）需要以系统画像的实际匹配结果为准。
