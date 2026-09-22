# 本地智能家居自动化规则引擎

基于 Spring Boot 3.4 / Java 21 的**纯本地**智能家居自动化规则引擎。用本地内存存储模拟
设备状态、事件流与设备网关，专注解决在**乱序、重复、并发**上报下结论的一致性与可解释性，
以及动作执行失败时的**异常链路、重试与补偿**。

- 无外部服务、无真实凭据、无付费组件；所有依赖均为 Spring Boot 常规开源组件。
- 设备状态、规则、执行轨迹全部保存在本地内存（重启重置）。
- 启动即用：内置演示设备（温度传感器 / 插座 / 门锁 / 灯）与一条演示规则。

---

## 1. 规则模型

### 1.1 设备与属性

- `Device`：设备 id、名称、类型（LIGHT/PLUG/LOCK/TEMPERATURE_SENSOR/THERMOSTAT）及一组属性规格。
- `PropertySpec`：每个属性声明类型（BOOLEAN/INTEGER/DECIMAL/STRING）与可选数值范围。
  所有上报值与动作下发值都必须先经 `normalize()` 校验与类型规范化，非法值无法进入系统。

### 1.2 规则（`Rule`）

一条规则包含：

- `conditions`：多个条件，**AND** 语义。条件形如 `deviceId.property <operator> expected`，
  运算符支持 `EQ/NE/GT/GE/LT/LE`。
- `actions`：多个动作的**有序序列**，动作形如把 `targetDeviceId.property` 设为 `value`。
  每个动作有稳定的 `actionDefId`。
- `enabled`：启停状态。
- `version`：每次启停 / 参数调整都产生新版本（+1）。

### 1.3 触发语义：边沿触发

条件组只有在 **false → true**（由“不满足”变为“满足”）的那一次状态确认时才触发一次：

- 条件不满足：不触发，边沿状态保持 false；
- 越过阈值（false→true）：触发一次，领取单调递增的 `fireNo`；
- 条件持续满足期间，无论收到多少条（哪怕值不同的）上报，**都不重复触发**；
- 条件离开（true→false）后重新进入，才会再次触发。

### 1.4 运行中变更立即生效

规则的新增、启停、参数（名称/条件/动作）调整都在运行中生效：
规则以**不可变快照 + volatile 引用**原子替换，在途执行读到的是旧版本，新事件读到新版本，不会出现半更新。
变更前会校验所有条件/动作引用的设备属性是否存在、取值是否合法。

---

## 2. 事件处理与幂等语义

### 2.1 上报事件结构

```
StateEvent { deviceId, property, rawValue, seq, eventId, eventTime }
```

- `seq`：上报端为「设备 + 属性」维护的**单调递增序号**，用于乱序/迟到判定；
- `eventId`：客户端为**同一次物理上报**生成的幂等键（重复投递必须复用同一 eventId）；
- `eventTime`：事件产生时间，可早于到达时间（迟到事件）。

### 2.2 处理流水线（短路顺序）

| 顺序 | 检查 | 结论 | 可区分原因 |
|----|------|------|-----------|
| 1 | 必填字段缺失 | `REJECTED` | `MALFORMED_EVENT` |
| 2 | 设备未注册 | `REJECTED` | `UNKNOWN_DEVICE` |
| 3 | 属性不存在 / 类型错 / 越界 | `REJECTED` | `INVALID_VALUE` |
| 4 | eventId 已处理 | `IGNORED` | `DUPLICATE_EVENT` |
| 5 | seq < 已确认序号 | `IGNORED` | `OUTDATED_SEQ`（乱序/迟到）|
| 5 | seq == 已确认序号（不同 eventId）| `IGNORED` | `STALE_SEQ`（同序号重复）|
| 6 | seq > 已确认序号 | `ACCEPTED` | 推进状态并评估规则 |

### 2.3 三条一致性保证

1. **已确认状态不回退**：每个「设备 + 属性」独立维护 `lastSeq`，
   只有 `seq > lastSeq` 才更新；乱序、迟到、同序号上报一律忽略，状态停留在高水位。
2. **重复上报不重复触发**：eventId 去重集合使用并发结构，同一事件并发重复投递只有一个线程通过；
   其余全部得到 `IGNORED/DUPLICATE_EVENT`。
3. **并发只触发一次动作序列**：每台设备一把锁做「比较并确认」，每条规则一把锁做
   「评估条件 → 判定边沿 → 领取 fireNo → 执行动作」。并发的不同事件即使都让条件处于满足态，
   false→true 边沿全局只会发生一次，因此**不漏发也不重复下发**。

> 拒绝（REJECTED）与忽略（IGNORED）是两类不同结论：前者表示事件非法、必须纠正；
> 后者表示事件合法但已过期/重复，系统有意幂等丢弃。HTTP 层统一返回 200，
> 结论通过响应体的 `outcome / rejectReason / ignoreReason / decision` 区分。

### 2.4 可解释性

每条事件的处理结果 `IngestResult` 都带自然语言 `decision`，规则结果 `RuleFireOutcome`
带逐动作的 `summary`。服务日志会打印**输入事件**（设备/属性/seq/eventId/值）与**判定依据**
（哪条条件成立、是边沿还是持续满足、是否判重）。全部轨迹可通过 `/api/traces/**` 查询。

---

## 3. 动作执行：失败、重试与补偿

### 3.1 幂等键

每个动作的全局副作用幂等键为：

```
idempotencyKey = ruleId : fireNo : actionDefId
```

幂等有两道硬保障：

- `ActionExecutor` 按幂等键缓存已完成记录并加锁：同键的并发/重复调用只执行一次，其余复用结果；
- `DeviceGateway`（本地模拟设备）也记录已执行键：同一键的命令只产生一次物理副作用。

### 3.2 失败原因可区分

| 原因 | 含义 | 是否重试 |
|------|------|---------|
| `DEVICE_UNREACHABLE` | 目标设备不可达，命令无法送达 | 是 |
| `EXECUTION_TIMEOUT` | 命令发出但执行超时 | 是 |
| `CONDITION_RECHECK_FAILED` | 下发瞬间条件已失效（条件复核失败），动作被跳过 | 否 |
| `INVALID_ACTION` | 动作本身非法（设备/属性不存在、取值非法） | 否 |

### 3.3 条件复核

每个动作下发前会用**当前已确认状态**重新校验规则条件组。若条件在触发后、下发前已失效，
该动作记为 `SKIPPED + CONDITION_RECHECK_FAILED`，不下发任何命令，避免对过期结论执行。

### 3.4 重试策略

- 仅对 `DEVICE_UNREACHABLE` / `EXECUTION_TIMEOUT` 重试；
- 默认最多 **3 次**尝试，指数式短退避（本地默认 5ms 起，测试很快）；
- 瞬时故障（注入「失败 N 次后恢复」）会在重试内成功，且 `attempts` 可断言、副作用仍只一次；
- 重试耗尽后失败定论被缓存，同键的再次调用不会重新尝试，杜绝任何潜在重复副作用。

### 3.5 部分失败与补偿

动作序列按序执行，任一动作失败或条件失效：

1. **立即停止**后续动作（前置动作失败后不会继续下发）；
2. **逆序补偿**本序列此前已成功的动作：把对应属性回滚为「触发前快照」中的原值；
3. 补偿本身也带幂等键（`comp:...`），重复补偿不会二次生效；
4. 返回整体结论 `PARTIAL-FAILURE`，逐动作列出状态、尝试次数、失败原因、是否已补偿；
   若补偿也失败，summary 明确提示需要人工介入。

触发前快照同时覆盖**条件设备**（复核用）与**动作目标设备**（补偿取原值用）。

---

## 4. 本地验证方法

### 4.1 运行测试

```bash
mvn clean test
```

覆盖场景（共 16 个用例，测试日志会打印输入事件与判定依据）：

| 测试类 | 覆盖 |
|--------|------|
| `StateIngestTest` | 乱序/迟到/同序号/重复上报、状态不回退；未注册设备/非法取值/缺字段拒绝且原因可区分 |
| `RuleTriggerTest` | 边沿只触发一次、持续满足不重复、离开再进入重新触发；运行中启停与调参立即生效 |
| `ConcurrentTriggerTest` | 同一事件 16 线程并发只接受一次、动作序列一次；20 个不同事件并发只边沿触发一次 |
| `ActionFailureTest` | 瞬时不可达重试成功；永久超时重试耗尽+补偿；不可达与超时原因区分；条件复核失败跳过 |
| `IdempotencyTest` | 成功后重复/失败后重复/瞬时恢复三种幂等，物理副作用次数可断言 |
| `ApiIntegrationTest` | HTTP 端到端：查询、拒绝码、触发、动作轨迹 |

### 4.2 启动应用并用 curl 复现

```bash
mvn spring-boot:run
```

```bash
B=http://localhost:8080

# 设备列表 / 已确认状态
curl -s $B/api/devices
curl -s $B/api/devices/temp-sensor/state

# 上报（先低温，不触发）
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"temp-sensor","property":"temperature","value":25,
  "seq":1,"eventId":"e-1","eventTime":100}'

# 乱序/迟到上报 -> IGNORED / OUTDATED_SEQ
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"temp-sensor","property":"temperature","value":40,
  "seq":0,"eventId":"e-0","eventTime":50}'

# 重复上报（同 eventId）-> IGNORED / DUPLICATE_EVENT
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"temp-sensor","property":"temperature","value":25,
  "seq":1,"eventId":"e-1","eventTime":100}'

# 未注册设备 -> REJECTED / UNKNOWN_DEVICE；非法值 -> INVALID_VALUE
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"ghost","property":"x","value":1,"seq":1,"eventId":"x","eventTime":1}'

# 注入故障：门锁永久超时（reason 可取 DEVICE_UNREACHABLE / EXECUTION_TIMEOUT，
# failTimes>0 表示瞬时失败该次数后恢复，省略表示永久）
curl -s -X POST $B/api/test/faults -H 'Content-Type: application/json' -d '{
  "deviceId":"lock","property":"locked","reason":"EXECUTION_TIMEOUT"}'

# 温度越过阈值 -> 触发规则；plug 成功、lock 超时重试耗尽，plug 被补偿
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"temp-sensor","property":"temperature","value":33,
  "seq":2,"eventId":"e-2","eventTime":200}'

# 设备恢复
curl -s -X POST $B/api/test/recover -H 'Content-Type: application/json' -d '{
  "deviceId":"lock","property":"locked"}'

# 规则启停 / 参数调整（运行中生效）
curl -s -X PATCH $B/api/rules/rule-cool-down/enabled -H 'Content-Type: application/json' -d '{"enabled":false}'
curl -s -X PATCH $B/api/rules/rule-cool-down -H 'Content-Type: application/json' -d '{
  "name":"高温自动断电",
  "conditions":[{"deviceId":"temp-sensor","property":"temperature","operator":"GT","expected":25}],
  "actions":[{"actionDefId":"turn-off-plug","targetDeviceId":"plug","property":"on","value":false}]}'

# 可追溯轨迹：事件判定 / 规则触发 / 动作执行
curl -s $B/api/traces/events
curl -s $B/api/traces/fires
curl -s $B/api/traces/actions
```

---

## 5. 代码结构

```
model/    领域模型：Device/PropertySpec/DeviceState/StateEvent、Rule/Condition/Action、
          各类结果与可区分原因枚举（RejectReason/IgnoreReason/ActionFailureReason）
store/    本地内存存储：DeviceRegistry、StateStore（序号单调确认）、
          RuleStore（原子快照 + 边沿状态 + fireNo）、ExecutionLog（轨迹）
device/   DeviceGateway（模拟设备物理副作用与故障注入）、DeviceCommandException
engine/   EventIngestService（接入/校验/幂等）、ConditionEvaluator（条件判定与解释）、
          RuleEngine（边沿触发 + 每规则锁 + 补偿编排）、ActionExecutor（幂等/重试/复核/补偿）、
          RuleAdminService（运行中增删改）
api/      REST 控制器与统一异常处理
config/   演示数据初始化
```

## 6. 设计要点小结

- **顺序无关、重复无害、并发一致**：seq 单调 + eventId 幂等 + 设备/规则双级锁。
- **结论可区分、可解释、可追溯**：拒绝原因、忽略原因、动作失败原因全部枚举化；
  每次判定与执行都有自然语言说明并落入轨迹。
- **失败安全**：条件复核防止过期执行；有界重试只针对可恢复故障；补偿回滚已成功副作用；
  补偿失败明确要求人工介入，绝不静默吞错。
