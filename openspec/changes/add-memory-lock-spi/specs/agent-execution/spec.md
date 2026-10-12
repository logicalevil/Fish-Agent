# agent-execution Specification

## MODIFIED Requirements

### Requirement: Agent 实例有状态且非线程安全

`ReActAgent` SHALL 绑定单个 `ChatMemory` 实例，并 SHALL 被标注为不可并发使用。框架 SHALL NOT 创建线程，SHALL NOT 自行发起同步。当调用方配置了 `MemoryLock` 时，记忆的互斥 SHALL 由该锁承担，而非由框架在 `ReActAgent` 内自行加锁。

#### Scenario: 记忆按 Agent 实例绑定
- **WHEN** 以不同的会话标识组装两个 Agent
- **THEN** 每个 Agent 只读写自己的记忆

#### Scenario: 框架不创建线程
- **WHEN** 完成一次 run
- **THEN** 执行期间没有由框架创建的线程，回调与 run 位于同一线程

#### Scenario: 未配置锁时行为与既有一致
- **WHEN** 构建 Agent 时未调用 `memoryLock(...)`
- **THEN** 记忆访问不引入额外的跨进程开销，且 `MemoryLock.isDistributed()` 为 `false`

#### Scenario: 配置锁后记忆访问经过该锁
- **WHEN** 以自定义 `MemoryLock` 构建 Agent 并执行一次 run
- **THEN** 该锁的 `execute` 被调用，且被保护的资源标识等于记忆的 `id()`
