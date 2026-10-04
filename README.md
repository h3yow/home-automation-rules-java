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
- `cooldownMillis`：**最短触发间隔（冷却）**，单位毫秒，可空。
  - `null`（默认）或 `<=0`：**不启用冷却**，规则行为与历史版本完全一致（纯边沿触发）；
  - 正整数：一次实际触发后进入冷却窗口，窗口内的 false→true 边沿一律不触发。
- `version`：每次启停 / 参数调整（含冷却调整）都产生新版本（+1）。

### 1.3 触发语义：边沿触发 + 可选冷却

条件组只有在 **false → true**（由“不满足”变为“满足”）的那一次状态确认时才有资格触发：

- 条件不满足：不触发，边沿状态保持 false（事件结果中标注“条件未到”，不产生规则记录）；
- 越过阈值（false→true）且**不在冷却期**：触发一次，领取单调递增的 `fireNo`；
- 条件持续满足期间，无论收到多少条（哪怕值不同的）上报，**都不重复触发**
  （规则结果 `skipReason=STILL_SATISFIED`）；
- 条件离开（true→false）后重新进入，才会再次获得触发资格。

**冷却（cooldownMillis）叠加在边沿之上，规则如下：**

1. 规则配置了正的 `cooldownMillis` 后，每次**实际触发**都会记录冷却窗口起点；
2. 起点之后的 `cooldownMillis` 毫秒为冷却期。冷却期内条件即使先离开又再次满足
   （即真的出现了新的 false→true 边沿，例如温度在阈值附近抖动反复穿越），
   该次边沿也会被挡下：**不触发、不领取 fireNo、不下发任何动作**，
   规则结果记为 `skipReason=COOLDOWN_ACTIVE`，并带 `cooldownRemaining`（剩余毫秒）；
3. **被挡下的边沿不会补发、也不会延后**：冷却到期时系统不会“补一枪”；
   必须是冷却结束**之后**再出现一次新的 false→true 变化，才允许正常触发
   （冷却期结束时若条件一直满足，下一条上报只算持续满足，不算边沿）；
4. 冷却窗口起点**只由“实际触发”刷新**：被冷却挡下不会延长/重置窗口；
5. 没配置冷却的规则，以上机制完全不介入，行为与旧版本逐字节一致。

> 冷却与边沿可区分：事件判定结果 / `/api/traces/fires` 轨迹中，
> `COOLDOWN_ACTIVE`（边沿被冷却挡下）、`STILL_SATISFIED`（条件一直满足）、
> 无规则结果（条件没到）是三种不同结论，排查时一眼可辨。

### 1.4 运行中变更立即生效（含冷却）

规则的新增、启停、参数（名称/条件/动作）、**冷却时间（调长/调短/开启/关闭）**
调整都在运行中生效：规则以**不可变快照 + volatile 引用**原子替换，在途执行读到的是旧版本，
新事件读到新版本，不会出现半更新。每次调整 `version` 加一。

- 调长：下一次边沿立即按更长窗口判定（可能立刻被挡）；
- 调短/关闭：下一次边沿立即按更短窗口或纯边沿判定（可能立刻放行）；
- 关闭后再开启：立即生效；
- 冷却窗口起点始终是**最近一次实际触发时刻**，调整配置本身不会移动该起点，
  因此“从什么时候开始计时”不随配置变更漂移，结果可预测；
- 每条规则各配各的冷却、各自独立计时，互不影响。

变更前会校验所有条件/动作引用的设备属性是否存在、取值是否合法。

### 1.5 冷却的计时口径（明确、可预测）

- **时钟来源**：统一使用引擎时钟 `EngineClock`（默认即 `System.currentTimeMillis()`
  的本地墙钟毫秒值）。**不使用事件自带的 `eventTime`**，因此乱序/迟到上报不会改变冷却判定，
  也不会被客户端时钟影响。
- **采样点唯一**：在某条规则的串行临界区（每规则一把锁）内、“边沿评估那一刻”只采样一次
  `now`，冷却检查、`fireNo` 领取、窗口起点记录复用同一个值，临界区内不二次读钟。
- **判定公式**：设上次实际触发时刻为 `lastFiredAt`，本次边沿评估时刻为 `now`：
  - `lastFiredAt == 0`（从未触发）→ 立即允许触发；
  - `now - lastFiredAt >= cooldownMillis` → 允许触发，并把窗口起点刷新为 `now`；
  - `now - lastFiredAt < cooldownMillis` → 挡下，`cooldownRemaining = cooldownMillis - (now-lastFiredAt)`。
- 边界按毫秒整数比较，**“等于间隔”即视为冷却结束**（`>=`，半开区间 `[lastFiredAt, lastFiredAt+cooldown)`）。
- 抖动 / 并发 / 乱序 / 迟到都不会造成时间口径歧义：同一规则的全部判定在同一把锁内按
  事件确认顺序串行完成；挡下不刷新起点，触发才刷新起点。
- 测试中可通过注入 `EngineClock.setFixedNow/advance` 使用确定时间断言边界，无需 sleep。

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
带逐动作的 `summary`。规则“有边沿相关结论但没触发”时通过 `skipReason` 区分：
`STILL_SATISFIED`（条件持续满足）与 `COOLDOWN_ACTIVE`（边沿被冷却挡下，附
`cooldownRemaining`）；条件根本没到则不产生规则记录。服务日志会打印**输入事件**
（设备/属性/seq/eventId/值）与**判定依据**（哪条条件成立、是边沿还是持续满足、
是否判重、是否被冷却挡下）。全部轨迹可通过 `/api/traces/**` 查询。

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
   补偿失败**不占用幂等键**，设备恢复后可再次补偿；
4. 返回整体结论 `PARTIAL-FAILURE`，逐动作列出状态、尝试次数、失败原因、是否已补偿；
   若补偿也失败，summary 明确提示需要人工介入。

触发前快照同时覆盖**条件设备**（复核用）与**动作目标设备**（补偿取原值用）。

### 3.6 回滚的一致性语义（重点）

补偿回滚完成后，**设备物理侧、查询到的已确认状态、动作执行记录、最终结论**四者必须对齐：

- **物理侧**：把触发前原值重新下发给设备；若触发前该属性本就没有已确认值，
  回滚语义是「清除本次动作留下的残留」，物理侧同样回到无值状态。
- **已确认状态**（`/api/devices/{id}/state` 查询到的就是它）：补偿成功后，
  状态存储用「比较并恢复」同步回触发前原值——只有该属性仍停留在本动作回写的那一版
  （此后没有任何更新的状态被确认）时才恢复；触发前无原值的，恢复为「移除该属性」。
  因此回滚完成后查询到的状态与触发前一致，不会出现「结论说已回滚、查到的还是动作后的值」。
- **同一设备被多个动作先后改动**：每个动作的回滚基准都是同一份触发前快照，
  逆序补偿后该属性回到**触发前那一刻**的原值，而不是某个中间值。
- **回滚没成功**：状态存储保持动作后的值、动作记录不标记 `compensated`、
  最终结论写 `SOME COMPENSATION FAILED, device state NOT restored, manual intervention needed`
  ——绝不表现出「已经恢复到原值」的样子，三者依然一致。
- **回滚与新确认状态竞争**：若动作之后、回滚之前该属性已有新值被确认
  （例如另一个控制源写入），回滚**不会**把已确认的新状态盖回旧值；
  此时查询到的状态是那个更新的已确认值，属于合法终态
  （最终状态要么是回滚后的原值、要么是其后被确认的新值，不会出现第三种结果）。
- 未配置冷却的规则，以上行为与历史版本完全一致（补偿链路与冷却互不影响）。

---

## 4. 本地验证方法

### 4.1 运行测试

```bash
mvn clean test
```

覆盖场景（共 27 个用例，测试日志会打印输入事件与判定依据）：

| 测试类 | 覆盖 |
|--------|------|
| `StateIngestTest` | 乱序/迟到/同序号/重复上报、状态不回退；未注册设备/非法取值/缺字段拒绝且原因可区分 |
| `RuleTriggerTest` | 边沿只触发一次、持续满足不重复、离开再进入重新触发；运行中启停与调参立即生效 |
| `ConcurrentTriggerTest` | 同一事件 16 线程并发只接受一次、动作序列一次；20 个不同事件并发只边沿触发一次 |
| `CooldownTest` | **冷却抖动抑制（冷却期反复穿越只触发一次、冷却后新边沿不漏触发）；冷却期内 16 线程并发穿越不多触发不多下发；运行中调长/调短/关闭/重开即时生效且各规则独立；不配置冷却默认行为与历史一致；COOLDOWN_ACTIVE 与 STILL_SATISFIED 在事件结果与轨迹中可区分；冷却不影响部分失败补偿，设备状态/动作记录/最终结论三者一致** |
| `ActionFailureTest` | 瞬时不可达重试成功；永久超时重试耗尽+补偿；不可达与超时原因区分；条件复核失败跳过 |
| `RollbackStateConsistencyTest` | **回滚一致性：中途失败回滚后查询状态=触发前原值（下发次数/查询状态/动作记录/最终结论四者同时断言）；触发前无原值时回滚后状态与物理侧均无残留；补偿失败时状态保持动作值、记录不标已补偿、结论明确人工介入（不假装已恢复）；同一设备多动作回滚到触发前那一刻的原值；回滚不覆盖其后已确认的新状态** |
| `IdempotencyTest` | 成功后重复/失败后重复/瞬时恢复三种幂等，物理副作用次数可断言 |
| `ApiIntegrationTest` | HTTP 端到端：查询、拒绝码、触发、动作轨迹 |

> 冷却相关测试全部通过注入式 `EngineClock` 用确定时间驱动（无需 sleep），
> 动作实际下发次数通过 `ExecutionLog.actionRecords()` 与 `DeviceGateway.physicalValue(...)` 双重断言。

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

# 验证回滚一致性：触发前 plug.on 没有已确认值，补偿回滚后查询状态也不含 on 属性
# （若触发前 plug.on 已有确认值，这里查到的会是那个原值）；
# /api/traces/actions 中 plug 动作记录 compensated=true，
# /api/traces/fires 的结论含 "rolled back to pre-fire state"，三者一致。
curl -s $B/api/devices/plug/state
curl -s $B/api/traces/actions
curl -s $B/api/traces/fires

# 设备恢复
curl -s -X POST $B/api/test/recover -H 'Content-Type: application/json' -d '{
  "deviceId":"lock","property":"locked"}'

# 规则启停 / 参数调整（运行中生效）
curl -s -X PATCH $B/api/rules/rule-cool-down/enabled -H 'Content-Type: application/json' -d '{"enabled":false}'
curl -s -X PATCH $B/api/rules/rule-cool-down -H 'Content-Type: application/json' -d '{
  "name":"高温自动断电",
  "conditions":[{"deviceId":"temp-sensor","property":"temperature","operator":"GT","expected":25}],
  "actions":[{"actionDefId":"turn-off-plug","targetDeviceId":"plug","property":"on","value":false}]}'

# 设置最短触发间隔（冷却）= 10 秒，立即对后续事件生效；响应中可见 cooldownMillis 与新版本号
curl -s -X PATCH $B/api/rules/rule-cool-down/cooldown -H 'Content-Type: application/json' -d '{"cooldownMillis":10000}'

# 调短 / 调长：再 PATCH 一次即可，例如 1000ms
curl -s -X PATCH $B/api/rules/rule-cool-down/cooldown -H 'Content-Type: application/json' -d '{"cooldownMillis":1000}'

# 关闭冷却：传 null 或 <=0，之后恢复纯边沿触发（与历史行为一致）
curl -s -X PATCH $B/api/rules/rule-cool-down/cooldown -H 'Content-Type: application/json' -d '{"cooldownMillis":null}'

# 新建规则时也可直接带 cooldownMillis（省略则默认无冷却）
curl -s -X POST $B/api/rules -H 'Content-Type: application/json' -d '{
  "id":"rule-jitter-guard",
  "name":"抖动保护规则",
  "enabled":true,
  "cooldownMillis":5000,
  "conditions":[{"deviceId":"temp-sensor","property":"temperature","operator":"GT","expected":28}],
  "actions":[{"actionDefId":"turn-off-plug","targetDeviceId":"plug","property":"on","value":false}]}'

# 冷却期内再次穿越阈值：事件仍 ACCEPTED（状态被确认），但规则结果
# fired=false、skipReason=COOLDOWN_ACTIVE、cooldownRemaining>0，且动作一次都不会多发；
# 条件一直满足时则是 skipReason=STILL_SATISFIED，两者可区分。
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"temp-sensor","property":"temperature","value":20,"seq":3,"eventId":"e-3","eventTime":300}'
curl -s -X POST $B/api/events -H 'Content-Type: application/json' -d '{
  "deviceId":"temp-sensor","property":"temperature","value":33,"seq":4,"eventId":"e-4","eventTime":400}'

# 可追溯轨迹：事件判定 / 规则触发（含 COOLDOWN_ACTIVE、STILL_SATISFIED） / 动作执行
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
          RuleEngine（边沿触发 + 每规则锁 + 冷却判定 + 补偿编排）、
          ActionExecutor（幂等/重试/复核/补偿）、RuleAdminService（运行中增删改与冷却调整）、
          EngineClock（冷却统一计时口径，默认真实墙钟、测试可注入固定时间）
api/      REST 控制器与统一异常处理
config/   演示数据初始化
```

## 6. 设计要点小结

- **顺序无关、重复无害、并发一致**：seq 单调 + eventId 幂等 + 设备/规则双级锁；
  冷却判定也在规则临界区内完成，抖动/乱序/并发穿越不多发、不漏发。
- **抗抖动冷却**：可按规则独立配置最短触发间隔；默认 `null` 完全兼容历史行为；
  调长/调短/开启/关闭运行中即时生效；窗口只在实际触发时刷新，挡下不刷新、不补发；
  时间口径统一走引擎时钟在边沿评估时刻的单次采样，明确可预测。
- **结论可区分、可解释、可追溯**：拒绝原因、忽略原因、动作失败原因、
  冷却挡下（COOLDOWN_ACTIVE）与持续满足（STILL_SATISFIED）全部枚举化；
  每次判定与执行都有自然语言说明并落入轨迹。
- **失败安全**：条件复核防止过期执行；有界重试只针对可恢复故障；补偿回滚已成功副作用，
  并把设备物理侧与已确认状态一起恢复为触发前原值（触发前无值则清除残留），
  补偿结果同步回填动作轨迹（设备状态、动作记录、最终结论三者一致）；
  回滚不覆盖其后已确认的新状态；补偿失败明确要求人工介入，绝不静默吞错。
