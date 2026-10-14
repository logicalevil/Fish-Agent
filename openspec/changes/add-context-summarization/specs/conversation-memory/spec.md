# conversation-memory Specification

## MODIFIED Requirements

### Requirement: 窗口记忆装饰器保留完整历史

`MessageWindowChatMemory` SHALL 实现 `ChatMemory`，只返回最近 `maxMessages` 条非 SYSTEM 消息，始终保留 SYSTEM 消息，并通过 `fullHistory()` 暴露未经裁剪的完整历史。`windowing` SHALL 允许在已有记忆之上套一层窗口裁剪。其并发保护由注入的 `MemoryLock` 承担。

#### Scenario: 已有示例
- **WHEN** 一个消息多于窗口大小的记忆被读取
- **THEN** 仅最近的 `maxMessages` 条非 SYSTEM 消息加上所有 SYSTEM 消息被返回

#### Scenario: 未裁剪历史
- **WHEN** 在裁剪之后调用 `fullHistory()`
- **THEN** 返回完整的消息历史（其大小可能超过窗口大小）

#### Scenario: SYSTEM 保留且位于最前
- **WHEN** 反复追加消息直到窗口滑动
- **THEN** 所有 SYSTEM 消息仍按原始顺序出现在结果的最前面

#### Scenario: 窗口不变式
- **WHEN** 使用 `maxMessages` 为 3 的窗口
- **THEN** `messages()` 恰好返回 3 条非 SYSTEM 消息以及所有 SYSTEM 消息

#### Scenario: 小于窗口大小的记忆原样返回
- **WHEN** 消息数少于窗口大小时读取
- **THEN** 所有非 SYSTEM 消息原样返回

#### Scenario: 默认窗口大小
- **WHEN** 通过 `of()` 构建且 `maxMessages` 小于 1
- **THEN** 被钳制为 1

#### Scenario: 保留全部消息不变
- **WHEN** 反复向窗口记忆追加消息
- **THEN** 底层存储保留每一条消息

#### Scenario: 跨多轮重复读取
- **WHEN** 在追加新消息之后再次读取窗口
- **THEN** 窗口被重新计算以包含最近的 `maxMessages` 条消息

#### Scenario: 与文件记忆组合
- **WHEN** 使用 `windowing(FileChatMemory.of("s1", dir), 3)` 并追加消息
- **THEN** 底层文件包含所有消息，同时 `messages()` 返回不超过 3 条非 SYSTEM 消息

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

### Requirement: 摘要压缩是独立的记忆实现

`ConversationMemory` 的能力集合 SHALL 包含 `SummarizingChatMemory` 这一实现，它与 `InMemoryChatMemory`、`MessageWindowChatMemory`、`FileChatMemory` 并列，并 SHALL 可与其它的记忆实现自由组合。压缩 SHALL 只改变 `messages()` 返回的消息列表，SHALL NOT 删除底层原文。

#### Scenario: 与其它实现并列可用
- **WHEN** 以 `SummarizingChatMemory` 作为会话记忆组装 Agent
- **THEN** Agent 正常工作，且压缩对上层透明

#### Scenario: 压缩不改变原文
- **WHEN** 压缩发生之后检查底层记忆
- **THEN** 原文条数未减少

#### Scenario: 组合顺序不破坏功能
- **WHEN** 以摘要包裹窗口，或以窗口包裹摘要
- **THEN** 两种组合下 `messages()` 均可用，且返回条数不超过各自的上限
