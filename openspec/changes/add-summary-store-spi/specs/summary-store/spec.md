# summary-store Specification

## Purpose

定义摘要状态的存储扩展点：摘要进度以不可变值对象承载、经由 `SummaryStore` 读写，摘要生成逻辑经由 `Summarizer` 可替换。目标是让「摘要存哪」与「摘要怎么生成」都不再写死在 `SummarizingChatMemory` 里，同时保持不配置任何扩展时的行为不变。

## ADDED Requirements

### Requirement: SummaryState 以不可变值对象承载摘要进度

`SummaryState` SHALL 是含 `summary`、`summarizedUpTo`、`lastCompactionSucceeded` 三个分量的不可变 record。`summary` 为 null SHALL 表示「从未成功压缩过」。`summarizedUpTo` SHALL 表示已进入摘要的消息在底层记忆中的下标位置，且 SHALL 只在同一底层记忆实例上有意义。`lastCompactionSucceeded` SHALL 表示最近一次压缩是否成功。

#### Scenario: 未压缩过的初始状态
- **WHEN** 创建一个表示初始状态的 `SummaryState`
- **THEN** `summary()` 为 null
- **AND** `summarizedUpTo()` 为 0
- **AND** `lastCompactionSucceeded()` 为 true

#### Scenario: 值对象不可变
- **WHEN** 尝试修改 `SummaryState` 的分量
- **THEN** 因 record 的不可变性而不存在修改方法，只能构造新实例

### Requirement: SummaryState 通过显式映射完成往返

`SummaryState` SHALL 通过显式 `toMap()` 序列化、通过 `fromMap(Map)` 反序列化，键名 SHALL 固定为 `summary`、`summarizedUpTo`、`lastCompactionSucceeded`。映射 SHALL NOT 依赖反射。缺失键 SHALL 回退为初始值：`summary` 为 null、`summarizedUpTo` 为 0、`lastCompactionSucceeded` 为 true。`fromMap` 收到 null 或空映射时 SHALL 返回初始状态而非抛异常。

#### Scenario: 完整状态往返
- **WHEN** 一个含摘要文本、非零游标与成功标记的状态被序列化后再反序列化
- **THEN** 得到的三个分量与原状态相等

#### Scenario: 键名固定
- **WHEN** 检查 `toMap()` 的键集合
- **THEN** 恰好包含 `summary`、`summarizedUpTo`、`lastCompactionSucceeded`

#### Scenario: 缺失键回退为初始值
- **WHEN** 以一个不含任何键的空映射调用 `fromMap`
- **THEN** 返回初始状态（`summary` 为 null、游标为 0、成功为 true）

#### Scenario: null 映射回退为初始值
- **WHEN** 以 null 调用 `fromMap`
- **THEN** 返回初始状态

### Requirement: SummaryStore 定义摘要状态的读写契约

`SummaryStore` SHALL 声明 `load(String sessionId)`、`save(String sessionId, SummaryState state)`、`clear(String sessionId)`。`load` SHALL 对不存在的会话返回初始状态而非 null，SHALL NOT 抛 NullPointerException。`save` 与 `clear` 的 sessionId 为 null 或空白时 SHALL 被忽略或按实现约定处理，且 SHALL NOT 破坏 store 中其它会话的状态。

#### Scenario: 未保存过的会话返回初始状态
- **WHEN** 对从未 save 过的 sessionId 调用 `load`
- **THEN** 返回 `summary` 为 null 的初始状态

#### Scenario: 保存后可读回
- **WHEN** 对某 sessionId 保存一个含摘要的状态后再次 load
- **THEN** 读到的状态与保存的相等

#### Scenario: 清除后回到初始状态
- **WHEN** 对已保存的 sessionId 调用 `clear` 后再 load
- **THEN** 返回初始状态

#### Scenario: 会话之间互不影响
- **WHEN** 分别向 `a` 与 `b` 保存不同状态
- **THEN** 各自 load 得到各自的状态

### Requirement: InMemorySummaryStore 是零依赖默认实现

框架 SHALL 提供 `InMemorySummaryStore`，以进程内映射保存状态，SHALL NOT 引入任何新依赖。它 SHALL 满足 `SummaryStore` 的全部语义，且 `load` 返回的状态 SHALL NOT 允许调用方通过修改返回值影响 store 内部数据。

#### Scenario: 默认实现可用
- **WHEN** 构造一个 `InMemorySummaryStore` 并执行 save/load
- **THEN** 行为与 `SummaryStore` 契约一致，且未新增运行时依赖

#### Scenario: 返回值不可反向修改 store
- **WHEN** 取出状态后构造新的 `SummaryState` 并保存到别处
- **THEN** store 中原状态不受影响

#### Scenario: 并发访问不产生异常
- **WHEN** 多个线程对同一 store 的不同会话并发 save/load
- **THEN** 不发生并发修改异常，且各会话读到自己的状态

### Requirement: Summarizer 把摘要生成抽成可替换扩展点

`Summarizer` SHALL 声明 `summarize(List<Message> messages, String previousSummary)`，返回新的摘要文本。返回 null 或空白 SHALL 视为本次摘要失败，由调用方降级处理。`Summarizer` SHALL NOT 负责推进游标或持久化状态。

#### Scenario: 生成的摘要被返回
- **WHEN** 以一组消息调用 `summarize`
- **THEN** 返回非空白的摘要文本

#### Scenario: 旧摘要参与输入
- **WHEN** 调用时传入非空的 `previousSummary`
- **THEN** 该旧摘要内容构成实现输入的一部分，使早期信息不被二次压缩丢弃

#### Scenario: 失败以 null 或空白表达
- **WHEN** 实现无法生成摘要
- **THEN** 返回 null 或空白字符串，由调用方决定降级，而非抛出受检异常

### Requirement: ModelSummarizer 是默认的摘要实现

框架 SHALL 提供 `ModelSummarizer`，通过 `ChatModel` 生成摘要。它 SHALL 把消息按角色渲染为可读文本（USER 为 `Human:`、ASSISTANT 为 `AI:`、TOOL 为 `Tool:`、SYSTEM 为 `System:`），SHALL 对单条工具结果做长度截断，SHALL NOT 创建线程。

#### Scenario: 按角色渲染
- **WHEN** 输入包含 USER、ASSISTANT、TOOL 三种角色的消息
- **THEN** 渲染结果分别以 `Human:`、`AI:`、`Tool:` 为行前缀

#### Scenario: 工具结果被截断
- **WHEN** 某条工具结果长度超过实现设定的上限
- **THEN** 渲染时被截断并带截断标记，避免摘要输入过长

#### Scenario: 不创建线程
- **WHEN** 执行一次摘要生成
- **THEN** 期间没有由框架创建的线程

### Requirement: 存储故障降级而不中断对话

当 `SummaryStore` 的 `load` 或 `save` 抛出异常时，`SummarizingChatMemory` SHALL NOT 向上抛出，SHALL 降级为「视作初始摘要状态」，即退化为窗口裁剪语义，使对话继续。

#### Scenario: load 故障时对话继续
- **WHEN** 注入的 `SummaryStore` 在 `load` 时抛出运行时异常
- **THEN** `add()` 正常返回，不向上抛异常

#### Scenario: save 故障时对话继续
- **WHEN** 注入的 `SummaryStore` 在 `save` 时抛出运行时异常
- **THEN** `messages()` 仍可正常返回结果

#### Scenario: 故障可观测
- **WHEN** 发生存储故障并降级之后
- **THEN** 可通过 `lastCompactionSucceeded()` 得知最近一次压缩未成功
