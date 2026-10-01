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
- `cooldownMillis`：**最短触发间隔（冷却，毫秒）**，默认 `0` 表示不配置冷却。
- `version`：每次启停 / 参数调整（含冷却调整）都产生新版本（+1）。

### 1.3 触发语义：边沿触发

条件组只有在 **false → true**（由“不满足”变为“满足”）的那一次状态确认时才触发一次：

- 条件不满足：不触发，边沿状态保持 false；
- 越过阈值（false→true）：触发一次，领取单调递增的 `fireNo`；
- 条件持续满足期间，无论收到多少条（哪怕值不同的）上报，**都不重复触发**；
- 条件离开（true→false）后重新进入，才会再次触发。

### 1.4 冷却（最短触发间隔）

规则可设置 `cooldownMillis > 0`，用来压住阈值附近抖动造成的反复穿越：

- **触发后进入冷却**：一次真正触发（动作序列开始执行，即使后续部分失败并补偿也算触发）后，
  冷却窗口 `[lastFiredAt, lastFiredAt + cooldownMillis)` 内出现的任何 false→true 边沿
  **一律挡下**，不领取 `fireNo`、不下发任何动作；
- **冷却不压制“本该触发的那一次”**：挡下的边沿**不刷新**计时基准，窗口不会被抖动无限延长；
  冷却结束后条件需要**重新出现一次 false→true** 才触发——若条件在冷却结束时仍一直满足，
  不会补发，必须等下一次离开再进入（语义可预测：冷却是“忽略窗口内的边沿”，不是延时触发）；
- **默认值与兼容范围**：`cooldownMillis=0`（默认/新建规则/未设置）时冷却逻辑完全旁路，
  行为与旧版本逐字节一致；冷却只作用于“边沿是否放行”，不改变事件接入、幂等、
  序号确认、动作执行与补偿的任何既有语义；
- **计时口径（明确可预测）**：冷却只认**引擎处理该已确认事件的本地墙钟时刻（毫秒）**
  （`RuleClock`），**不使用事件自带的 `eventTime`**——迟到/乱序事件的产生时间可能很旧，
  用它计时会依赖上报端时钟、无法预测；窗口按**左闭右开**判定，
  `now - lastFiredAt < cooldownMillis` 挡下，等于窗口长度时放行；
- **运行中随时可改、各规则独立**：调长、调短、置 `0` 关闭都通过不可变快照原子替换立即生效，
  且只影响后续事件；计时基准（上次触发时刻）不因改配置被清零重计——
  例如冷却中调短到使窗口已过，下一次边沿立即放行；调长则当前边沿按新窗口继续挡；
  每条规则各自维护边沿状态与冷却基准，互不影响。

### 1.5 运行中变更立即生效

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
   「评估条件 → 判定边沿 → 冷却检查 → 领取 fireNo → 执行动作」。并发的不同事件即使都让条件处于满足态，
   false→true 边沿全局只会发生一次，冷却基准的读取与推进也在同一把规则锁内原子完成，
   因此**抖动、乱序/迟到、近乎同时到达的一批事件都不会多触发或漏发**。

> 拒绝（REJECTED）与忽略（IGNORED）是两类不同结论：前者表示事件非法、必须纠正；
> 后者表示事件合法但已过期/重复，系统有意幂等丢弃。HTTP 层统一返回 200，
> 结论通过响应体的 `outcome / rejectReason / ignoreReason / decision` 区分。

### 2.4 可解释性

每条事件的处理结果 `IngestResult` 都带自然语言 `decision`，规则结果 `RuleFireOutcome`
带逐动作的 `summary`。服务日志会打印**输入事件**（设备/属性/seq/eventId/值）与**判定依据**
（哪条条件成立、是边沿还是持续满足、是否判重）。全部轨迹可通过 `/api/traces/**` 查询。

规则“条件满足但没触发”有两种**可区分结论**，排查时一眼能判断是条件没到还是被冷却挡住：

| skipReason | 含义 | 典型 decision 文案 |
|------------|------|-------------------|
| （无结果） | 条件当前不满足（连边沿都没有） | `无规则满足条件（无 false→true 边沿）` |
| `STILL_SATISFIED` | 条件持续满足，非边沿，本就不该重复触发 | `规则 x 未触发(STILL_SATISFIED, 条件持续满足)` |
| `COOLDOWN_ACTIVE` | 出现了 false→true 边沿，但落在冷却窗口内被挡下 | `规则 x 边沿被冷却挡下(COOLDOWN_ACTIVE, retryAt=…)` |

被冷却挡下的 `RuleFireOutcome` 还带 `cooldownMillis`（当时生效的配置）与 `retryAtMillis`
（`lastFiredAt + cooldownMillis`，下一次边沿最早可放行的处理时刻），且 `records` 为空
（没有任何动作下发）。两类 skip 都会进入 `/api/traces/fires`，可分别统计。

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

补偿在物理侧（`DeviceGateway`）回滚成功后，还会把原值对称写回 `StateStore` 的**已确认状态**，
并把 `/api/traces/actions` 中该动作记录替换为“已补偿”版本。因此回滚后
**最终结论、动作执行记录、设备（物理 + 已确认）状态三方一致**：结论说已回滚，
记录里对应成功动作必带 `[compensated]` 标记，查到的设备状态必为触发前原值。

---

## 4. 本地验证方法

### 4.1 运行测试

```bash
mvn clean test
```

覆盖场景（共 21 个用例，测试日志会打印输入事件与判定依据）：

| 测试类 | 覆盖 |
|--------|------|
| `StateIngestTest` | 乱序/迟到/同序号/重复上报、状态不回退；未注册设备/非法取值/缺字段拒绝且原因可区分 |
| `RuleTriggerTest` | 边沿只触发一次、持续满足不重复、离开再进入重新触发；运行中启停与调参立即生效 |
| `ConcurrentTriggerTest` | 同一事件 16 线程并发只接受一次、动作序列一次；20 个不同事件并发只边沿触发一次 |
| `CooldownTest` | 默认无冷却行为与旧版一致；阈值抖动下冷却只触发一次且窗口后下次边沿不被漏发；冷却期内 24 线程交错涌入不多发；运行中调短/调长/置 0 立即生效；`COOLDOWN_ACTIVE` 与 `STILL_SATISFIED` 在判定结果和追溯中可区分（固定时钟确定性断言，动作实际下发次数可断言） |
| `ActionFailureTest` | 瞬时不可达重试成功；永久超时重试耗尽+补偿；不可达与超时原因区分；条件复核失败跳过；部分失败回滚后“结论 / 动作记录 / 物理+已确认状态”三方一致 |
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

# 设置最短触发间隔（冷却）2000ms；运行中随时可改，置 0 即关闭冷却
curl -s -X PATCH $B/api/rules/rule-cool-down/cooldown \
  -H 'Content-Type: application/json' -d '{"cooldownMillis":2000}'
# 冷却窗口内“离开再进入”的边沿 -> decision 中出现 COOLDOWN_ACTIVE 与 retryAt；
# 持续满足但没有新边沿 -> STILL_SATISFIED；两者可明确区分。
# 新建规则时也可以直接带 "cooldownMillis":2000；省略或为 0 表示不启用冷却。

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
          RuleStore（原子快照 + 边沿状态 + fireNo + 每规则上次触发时刻）、ExecutionLog（轨迹）
device/   DeviceGateway（模拟设备物理副作用、故障注入与物理写入计数）、DeviceCommandException
engine/   EventIngestService（接入/校验/幂等）、ConditionEvaluator（条件判定与解释）、
          RuleEngine（边沿触发 + 冷却检查 + 每规则锁 + 补偿编排）、ActionExecutor（幂等/重试/复核/补偿）、
          RuleClock（冷却统一计时口径，可注入固定时钟）、RuleAdminService（运行中增删改/冷却调整）
api/      REST 控制器与统一异常处理
config/   演示数据初始化
```

## 6. 设计要点小结

- **顺序无关、重复无害、并发一致**：seq 单调 + eventId 幂等 + 设备/规则双级锁；
  冷却基准在规则锁内原子读写，抖动与并发涌入不会多触发或漏发。
- **冷却语义明确可预测**：默认关闭（与旧行为完全一致）；窗口左闭右开、只看引擎处理时刻；
  被挡边沿不刷新基准；冷却结束后等下一次新边沿，绝不延时补发；运行中调长/调短/关闭即时生效。
- **结论可区分、可解释、可追溯**：拒绝原因、忽略原因、动作失败原因、规则未触发原因
  （STILL_SATISFIED / COOLDOWN_ACTIVE）全部枚举化；每次判定与执行都有自然语言说明并落入轨迹。
- **失败安全**：条件复核防止过期执行；有界重试只针对可恢复故障；补偿回滚已成功副作用，
  并同步回写已确认状态与轨迹标记，保证结论/记录/状态三方一致；
  补偿失败明确要求人工介入，绝不静默吞错。
