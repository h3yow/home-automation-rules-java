# 本地智能家居自动化规则引擎

基于 Spring Boot 3.4 / Java 21 的**纯本地**智能家居自动化规则引擎。设备状态、事件流、规则配置、
审计记录全部保存在内存中；设备执行由内置模拟器承担，**不依赖任何外部服务、真实凭据或付费组件**。

目标：让「设备状态变化 → 规则触发 → 动作执行」在**乱序、重复、并发**场景下结论一致、可解释、可复现。

---

## 1. 能力总览

- 设备注册（含合法取值范围）、状态上报与查询。
- 重复、乱序、迟到上报不会让已确认状态回退，也不会重复触发规则。
- 未注册设备、非法取值被拒绝，拒绝原因可区分（`DEVICE_NOT_REGISTERED` / `INVALID_VALUE` / `STALE_EVENT`）。
- 多条件（AND）、多动作（有序序列）的自动化规则；规则新增、参数调整、启停**运行中即时生效**。
- 同一事件并发到达，动作序列**恰好执行一次**，不重复下发、不遗漏。
- 动作失败链路：区分「设备不可达 / 执行超时 / 条件失效」，有界重试，失败后按序补偿。
- 重试与补偿具备幂等保护，**不会产生重复副作用**。
- 部分失败给出 `ALL_SUCCESS / PARTIAL_FAILURE / ALL_FAILED / NOT_EXECUTED` 整体结论与逐条可追溯记录。
- 单元测试打印每个输入事件与判定依据；审计接口可查询事件与触发两个维度的记录。

## 2. 规则模型

```
Rule
 ├─ id / name
 ├─ triggerDeviceId   触发设备：只有该设备的状态被"确认推进"才会评估本规则
 ├─ conditions[]      条件（全部 AND）：Condition(deviceId, operator, threshold)
 │                    operator ∈ GT GTE LT LTE EQ NE
 ├─ actions[]         有序动作序列：ActionDef(targetDeviceId, value)
 └─ compensations[]   补偿动作：动作序列出现最终失败/条件失效时，仅对"已成功"的动作按序补偿
```

- 规则存储为不可变快照 `RuleEntry(rule, enabled, version)`；每次新增/替换/启停都让 `version +1`。
- 一次触发自始至终持有同一份规则快照，运行中改规则不会让同一次评估看到混合配置。
- 启动时自动注册演示设备（`temp-1`、`fan-1`、`lamp-1` 等）与规则 `rule-cooling`（温度 > 30 开风扇）。

## 3. 事件处理与幂等语义

事件：`EventReport(eventId, deviceId, value, eventTime)`。

`StateStore` 对**同一设备**用 `ConcurrentHashMap.compute` 原子串行化以下三步判定：

1. **去重**：`eventId` 已处理过 → 返回 `DUPLICATE`，状态不变、不评估规则。
2. **乱序/迟到防护**：`eventTime <= 已确认事件时间` → 返回 `REJECTED/STALE_EVENT`，
   已确认状态**绝不回退**（同一毫秒的事件也视为迟到，保证确定性）。
3. **确认推进**：写入新状态，版本号 `version` 单调 +1。

受理前还会拒绝：未注册设备（`DEVICE_NOT_REGISTERED`）、越界或 NaN 取值（`INVALID_VALUE`）；
被拒事件不会进入去重表，也不会推进版本。

**规则触发幂等**：只有 `ACCEPTED` 事件参与规则评估；引擎以 `(ruleId, eventId)` 为键，
用 `CompletableFuture` 占位 + `putIfAbsent` 保证并发的同一事件只有一个线程真正执行动作序列，
其余线程等待并复用同一结果（标记 `deduplicated=true`）。

> 并发与乱序的区别：并发发送的事件其「到达时刻」本身可能乱序，此时迟到事件按规则被拒绝是**正确**行为；
> 测试对「无丢失（accepted + stale = 总数）」「版本不重复不跳号」「每个被确认事件恰好触发一次」做断言。

## 4. 失败、重试与补偿

单动作尝试结果携带可区分的失败类型：

| FailureKind | 含义 |
|---|---|
| `DEVICE_UNREACHABLE` | 设备离线/连接失败（模拟网关） |
| `TIMEOUT` | 单次尝试超过 200ms（模拟慢设备，future 超时中断） |
| `CONDITION_INVALID` | 动作执行前复查条件，发现条件已失效 |
| `UNREGISTERED_TARGET` / `INVALID_TARGET_VALUE` | 目标设备未注册 / 目标取值越界 |

执行规则：

- 动作严格**按序**执行；每个动作执行**前**复查一次条件快照，失效则记 `SKIPPED_CONDITION_INVALID` 并立即停止。
- 单动作**有界重试**，次数由 `rules.max-attempts`（默认 3，含首次）控制，带固定短退避。
- 同一动作的所有尝试复用同一个**幂等键** `triggerId#seq`。
- 幂等键在设备网关区分两种状态：
  - 已**成功过** → 直接幂等返回，绝不产生第二次副作用（重复下发/重放/补偿安全）；
  - 曾**失败**   → 允许真正重试，成功才计一次副作用。
- 序列在某个动作**最终失败**处停止；之后**只对已经成功的动作**按序执行补偿（补偿同样有界重试、幂等）。
- 整体结论：
  - `ALL_SUCCESS` 全部成功；
  - `PARTIAL_FAILURE` 有成功也有失败（含已执行补偿的说明）；
  - `ALL_FAILED` 无任何动作成功（此时没有需要回滚的副作用，不执行补偿）；
  - `NOT_EXECUTED` 第一个动作执行前条件即失效，一个动作都没下发。
- `explanation` 文本与审计记录给出逐跳尝试次数、失败原因、补偿情况，可解释、可追溯。

## 5. 本地验证方法

### 5.1 运行测试（离线即可）

```bash
mvn -o test                 # 首次如无本地依赖缓存可去掉 -o
```

共 20 个测试，覆盖：

| 测试类 | 覆盖场景 |
|---|---|
| `StateReportingTest` | 重复幂等、乱序/迟到拒绝、未注册/非法取值区分、并发串行确认与版本连续性 |
| `RuleTriggerTest` | 多动作按序执行、重复事件不重复触发、运行时停用/启用、参数替换即时生效 |
| `ActionFailureTest` | 瞬时故障重试成功无重复副作用、持续不可达、超时区分、部分失败+补偿、条件失效、同触发重放 |
| `ConcurrentTriggerTest` | 同一事件并发只触发一次、不同事件并发不丢不重、并发遇瞬时故障仍恰好一次副作用 |
| `EndToEndIntegrationTest` | Spring 装配端到端：上报→触发→重试→审计 |
| `WebApiTest` | HTTP 接口冒烟：正常/重复/拒绝原因可在接口断言 |

测试日志会打印形如：

```
[事件输入] eventId=e-hot device=temp-1 value=35.0 eventTime=2000 -> 受理=ACCEPTED 依据: 事件被确认...
[规则判定] rule=e2e-rule v1 eventId=e-hot matched=true 依据: 触发设备=e2e-temp，... => 匹配=true；动作结果=...
```

### 5.2 启动应用并用 HTTP 验证

```bash
mvn -o spring-boot:run
# 如 8080 被占用：
mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```

```bash
# 高温触发（eventTime 需大于此前已确认时间，乱序会被 STALE_EVENT 拒绝）
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"m-2","deviceId":"temp-1","value":35,"eventTime":2000}'

# 重复事件 -> DUPLICATE，不触发
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"m-2","deviceId":"temp-1","value":35,"eventTime":2000}'

# 注入 fan-1 两次瞬时不可达，再触发 -> 重试后成功、仅一次副作用
curl -s -XPOST 'localhost:8080/api/sim/fail-next?deviceId=fan-1&kind=DEVICE_UNREACHABLE&times=2'
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"m-3","deviceId":"temp-1","value":36,"eventTime":4000}'

# 规则停用/启用（运行中生效）
curl -s -XPOST 'localhost:8080/api/rules/rule-cooling/enabled?enabled=false'
curl -s -XPOST 'localhost:8080/api/rules/rule-cooling/enabled?enabled=true'

# 状态与审计
curl -s localhost:8080/api/states
curl -s localhost:8080/api/audit/events
curl -s localhost:8080/api/audit/triggers
curl -s localhost:8080/api/sim/executions   # 全设备实际副作用总数（去重后）
```

### 5.3 HTTP 接口一览

| 方法 路径 | 说明 |
|---|---|
| `POST /api/devices` | 注册设备 `{deviceId,type,minValue,maxValue}` |
| `GET  /api/devices` / `GET /api/states[/{id}]` | 设备清单 / 当前状态 |
| `POST /api/events` | 上报事件 `{eventId,deviceId,value,eventTime}` |
| `POST /api/rules` / `POST /api/rules/{id}` | 新增 / 替换（参数调整）规则 |
| `POST /api/rules/{id}/enabled?enabled=` | 运行时启停 |
| `GET  /api/rules` | 规则列表 |
| `GET  /api/audit/events` / `/api/audit/triggers` | 事件受理 / 规则触发审计 |
| `POST /api/sim/fail-next?deviceId&kind&times` | 注入前 N 次故障（DEVICE_UNREACHABLE/TIMEOUT） |
| `POST /api/sim/offline?deviceId&offline` | 设备离线/恢复 |
| `GET  /api/sim/executions` | 去重后的实际副作用总数 |

## 6. 代码结构

```
model/     设备/事件/状态/回执等不可变领域模型与拒绝原因枚举
device/    设备注册表、状态存储（去重 + 乱序防护 + 版本推进）
rule/      规则、条件、算子、条件评估器
action/    设备网关（幂等+故障注入模拟器）、动作执行器（重试/超时/补偿）
store/     规则存储（版本化快照）、内存审计日志
engine/    规则引擎（触发幂等编排）、设备门面、规则门面
web/       REST 控制器与演示数据初始化
```

## 7. 设计边界（本地模拟）

- 所有状态仅在内存，重启清空；无数据库、无消息队列、无网络外呼。
- 「设备执行」由 `SimulatedDeviceGateway` 模拟：成功即记录幂等键，故障/延迟可通过接口注入。
- 时间戳使用调用方提供的 `eventTime` 做顺序判定，便于确定性地复现乱序与迟到。
