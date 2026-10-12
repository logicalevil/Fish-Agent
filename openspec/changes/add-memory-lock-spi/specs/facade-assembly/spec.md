# facade-assembly Specification

## MODIFIED Requirements

### Requirement: 门面暴露框架的默认值

`FishAgent.Builder` SHALL 默认使用原生工具调用、启用流式输出、最多 8 轮模型调用、同一工具调用最多重复 3 次，并以 `http://localhost:11434` 作为 Ollama 基础地址。记忆锁 SHALL 默认为不引入额外依赖的本地实现。

#### Scenario: 最小配置下的默认值
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").build()` 且不做其它配置
- **THEN** Agent 运行在原生工具调用模式且已启用流式输出
- **AND** 单次 `run` 最多执行 8 轮模型调用

#### Scenario: 默认会话记忆保留 20 条窗口
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").session("user-42").build()`
- **THEN** 生成的记忆最多返回最近 20 条非 SYSTEM 消息，外加全部 SYSTEM 消息

#### Scenario: 默认锁为本地实现
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").build()` 且未配置锁
- **THEN** 使用的 `MemoryLock.isDistributed()` 为 false

### Requirement: 门面支持配置记忆锁

`FishAgent.Builder` SHALL 提供 `memoryLock(MemoryLock)`，把锁传递给 `ReActAgent`，并 SHALL 通过 `LockedChatMemory` 包裹配置的记忆。未调用时 SHALL 使用默认本地实现，且 SHALL NOT 因此改变既有的记忆语义。

#### Scenario: 配置跨进程锁
- **WHEN** 调用 `.memoryLock(redissonMemoryLock).build()`
- **THEN** 组装出的 Agent 在记忆访问时使用该锁

#### Scenario: 未配置锁时行为不变
- **WHEN** 不调用 `memoryLock(...)`
- **THEN** 记忆的读写结果与配置前完全一致

#### Scenario: 记忆标识在装饰后透传
- **WHEN** 调用 `.session("user-42").memoryLock(lock).build()`
- **THEN** `memory().id()` 仍为 `"user-42"`
