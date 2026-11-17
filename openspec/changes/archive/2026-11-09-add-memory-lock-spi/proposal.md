## Why

Fish-Agent 的记忆层目前只有**单 JVM 内**的并发保护，且各自为政：

- `InMemoryChatMemory` 用 `CopyOnWriteArrayList`，单条 `add` 原子，但「读—改—写」复合操作（如 `MessageWindowChatMemory` 的裁剪、一次 run 内连续写入多条消息）在多线程下仍会交错；
- `FileChatMemory` 用方法级 `synchronized`，只在当前进程内有效。

当同一份记忆被**多实例部署**的多个 Agent 并发访问时，上述保护全部失效，可能出现消息丢失或顺序错乱。而 `project.md` 已把「框架内部不做同步、`ReActAgent` 非线程安全」定为刻意取舍——并发安全目前被完全推给调用方，调用方除了「每会话一个实例」之外没有别的抓手。

本变更引入一个**记忆层的锁扩展点**，让「同一份记忆的互斥」可被抽象、可被替换。两个原则：

1. **统一抽象**：框架内所有记忆的并发保护都走同一个接口，不再散落 `synchronized` / COW 各自为政；
2. **实现交由使用者**：框架只提供一个零依赖的本地默认实现保证开箱可用，跨进程实现完全由使用者自行提供——框架不引入 Redisson，也不提供任何分布式组件。

## What Changes

- **新增 `MemoryLock` 接口**（`com.zheng.fishagent.memory.lock`）：声明「跨进程是否有效」与「带超时的临界区执行」两项能力。
- **新增静态工厂 `MemoryLock.local()`**：基于 `ReentrantLock` 的本地实现，`isDistributed()` 返回 `false`，作为未配置时的默认值。
- **新增 `LocalMemoryLock`**：`local()` 的实现类，零新增依赖。
- **新增 `LockedChatMemory` 装饰器**：把任意 `ChatMemory` 包成加锁版本，接口不变，与现有 `MessageWindowChatMemory` 的装饰器风格一致。
- **`FileChatMemory` 的 `synchronized` 改由 `MemoryLock` 承担**：行为等价，但可替换为跨进程实现；既有构造签名保留。
- **`InMemoryChatMemory` 接入 `MemoryLock`**：语义上统一，底层仍保留免锁读结构，不引入额外开销。
- **`ReActAgent` / `FishAgent` 增加 `memoryLock(...)` 配置项**：未配置时沿用 `MemoryLock.local()`，行为与今天完全一致。

明确**不做**的事：

- ❌ 框架**不提供**跨进程锁实现（无 `RedissonMemoryLock`）
- ❌ **不引入** Redisson 或任何分布式协调组件依赖
- ❌ **不拆分** `fish-agent-redisson` 模块，**不重组**仓库为多模块布局（原 C 方案取消）
- ❌ 不改 `ChatMemory` 接口签名
- ❌ 不提供 `noop()` 实现
- ❌ 框架不对 `isDistributed()` 的声明做校验、拦截或告警
- ❌ 不改造 `ReActAgent` 的循环结构

## Capabilities

### New Capabilities

- `memory-locking`：记忆层锁扩展点的契约——`MemoryLock` 接口语义、`isDistributed()` 自描述能力、`LockedChatMemory` 的包裹语义、`local()` 默认实现的行为与零开销保证、跨进程实现由使用者自行接入的边界。

### Modified Capabilities

- `conversation-memory`：现有需求假定 `ChatMemory` 实现自带并发保护（`synchronized` / COW）。需补充「并发保护来自 `MemoryLock`，而非实现自身」，并明确默认实现与替换路径。
- `agent-execution`：现有需求写着「框架 SHALL NOT 做内部同步」。引入锁扩展点后需精确化边界——框架仍不**自行**同步，但允许在调用方配置的前提下于记忆访问处加锁。
- `facade-assembly`：`FishAgent.Builder` 与 `ReActAgent.Builder` 新增 `memoryLock(...)`，需补充默认值与解析规则。

## Impact

**代码**：

- 新增：`memory/lock/MemoryLock.java`、`memory/lock/LocalMemoryLock.java`、`memory/LockedChatMemory.java`
- 修改：`memory/InMemoryChatMemory.java`、`memory/FileChatMemory.java`、`agent/ReActAgent.java`、`FishAgent.java`
- 测试：新增 `LocalMemoryLockTest`、`LockedChatMemoryTest`，改造 `FileChatMemoryTest` 验证去掉 `synchronized` 后并发仍正确

**依赖与构建**：

- **依赖零变化**：核心仍为 Jackson 2.18.2 + JUnit 5.11.4，**不引入 Redisson**
- **构建方式零变化**：仍为 `mvn -f pom.xml test`，**不重组仓库布局**
- **API 兼容性**：不是破坏性变更——未配置锁时回退 `MemoryLock.local()`，与现有行为等价；既有构造签名全部保留

**风险**：

| 风险 | 说明 |
|---|---|
| 使用者自行实现的跨进程锁若租约配置不当，可能导致锁在临界区内失效 | 框架无法防止。在 README 中明确要求「租约时长 > 单次临界区最大耗时」 |
| `isDistributed()` 的声明可能不实 | 记为使用者责任。框架刻意不校验——校验无法真正生效，反而给人虚假的安全感 |
| 移除 `FileChatMemory.synchronized` 可能引入回归 | 通过保留既有构造签名（回退 `local()`）+ 补并发写入测试来缓解 |
| 一次 run 内频繁进出临界区，跨进程锁 RTT 开销显著 | 装饰器只包住单次记忆操作；使用者可在自己的实现中做批处理优化 |
