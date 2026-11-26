# conversation-memory Specification

## MODIFIED Requirements

### Requirement: 滑动窗口记忆在保留系统消息的前提下限制上下文

`MessageWindowChatMemory` SHALL 装饰另一个 `ChatMemory`，其 `messages()` SHALL 最多返回 `maxMessages` 条非 SYSTEM 消息，同时 SHALL 始终包含全部 SYSTEM 消息，并 SHALL 通过 `fullHistory()` 暴露未被裁剪的完整历史。`windowing` SHALL 允许在已有记忆之上套一层窗口裁剪。其并发保护由注入的 `MemoryLock` 承担。

#### Scenario: 窗口保留最近的非系统消息
- **WHEN** 大小为 3 的窗口收到 1 条 SYSTEM 消息与 6 条 USER 消息
- **THEN** `messages()` 返回 4 条
- **AND** 第一条是 SYSTEM 消息
- **AND** 第二条是第 4 条 USER 消息
- **AND** 最后一条是第 6 条 USER 消息

#### Scenario: 完整历史不受窗口影响
- **WHEN** 在上述例子上调用 `fullHistory()`
- **THEN** 返回全部 7 条已存消息

#### Scenario: 历史短于窗口时整体返回
- **WHEN** 非 SYSTEM 消息数量不超过 `maxMessages`
- **THEN** `messages()` 返回全部这些消息加上全部 SYSTEM 消息

#### Scenario: 写入穿透到被装饰者
- **WHEN** 通过窗口追加一条消息
- **THEN** 被装饰者存储了它，且 `fullHistory()` 反映了该追加

#### Scenario: 窗口大小被抬升为至少 1
- **WHEN** 以 0 或负值创建窗口
- **THEN** `maxMessages()` 返回 1

#### Scenario: null 被装饰者被拒绝
- **WHEN** 以 null 被装饰者创建窗口
- **THEN** 抛出 `NullPointerException`，消息包含 `delegate 不能为空`

#### Scenario: 窗口可包裹任意记忆实现
- **WHEN** 以文件记忆作为被装饰者调用 `windowing(delegate, n)`
- **THEN** 持久化仍然发生，同时 `messages()` 被窗口裁剪

#### Scenario: SYSTEM 保留且位于最前
- **WHEN** 反复追加消息直到窗口滑动
- **THEN** 所有 SYSTEM 消息仍按原始顺序出现在结果的最前面

#### Scenario: 默认窗口大小
- **WHEN** 通过 `of()` 构建且 `maxMessages` 小于 1
- **THEN** 被钳制为 1

#### Scenario: 保留全部消息不变
- **WHEN** 反复向窗口记忆追加消息
- **THEN** 底层存储保留每一条消息

#### Scenario: 跨多轮重复读取
- **WHEN** 在追加新消息之后再次读取窗口
- **THEN** 窗口被重新计算以包含最近的 `maxMessages` 条消息

#### Scenario: 装饰后的标识透传
- **WHEN** 使用 `windowing` 包装一个标识为 `s1` 的记忆
- **THEN** 该装饰器的 `id()` 为 `s1`

### Requirement: 记忆的并发保护由 MemoryLock 承担

框架内所有 `ChatMemory` 实现的并发保护 SHALL 经由 `MemoryLock`，SHALL NOT 在实现内部直接使用 `synchronized`。未显式配置锁时，实现 SHALL 使用不引入额外依赖的本地实现，且行为与既有一致。压缩类实现 SHALL 遵守同一条约束。

#### Scenario: 未配置锁时默认可用
- **WHEN** 组装任意 `ChatMemory` 实现且不配置锁
- **THEN** 该记忆可正常工作，且不因此新增运行时依赖

#### Scenario: 配置跨进程锁后互斥范围扩大
- **WHEN** 为记忆配置 `isDistributed()` 为 true 的锁
- **THEN** 对同一资源标识的并发访问在多个实例之间互斥

#### Scenario: 锁实现可被替换而不改接口
- **WHEN** 用另一 `MemoryLock` 实现重新组装同一记忆类型
- **THEN** 无需修改该记忆类或其实现

#### Scenario: 摘要压缩实现同样遵守该约束
- **WHEN** 检查 `SummarizingChatMemory` 的并发保护方式
- **THEN** 其互斥经由 `MemoryLock`，而非自身的 `synchronized`

## ADDED Requirements

### Requirement: 摘要压缩是独立的记忆实现

`ChatMemory` 的能力集合 SHALL 包含 `SummarizingChatMemory` 这一实现，它与 `InMemoryChatMemory`、`MessageWindowChatMemory`、`FileChatMemory` 并列，并 SHALL 可与其它的记忆实现自由组合。压缩 SHALL 只改变 `messages()` 返回的消息列表，SHALL NOT 删除底层原文。

#### Scenario: 与其它实现并列可用
- **WHEN** 以 `SummarizingChatMemory` 作为会话记忆组装 Agent
- **THEN** Agent 正常工作，且压缩对上层透明

#### Scenario: 压缩不改变原文
- **WHEN** 压缩发生之后检查底层记忆
- **THEN** 原文条数未减少

#### Scenario: 组合顺序不破坏功能
- **WHEN** 以摘要包裹窗口，或以窗口包裹摘要
- **THEN** 两种组合下 `messages()` 均可用，且返回条数不超过各自的上限
