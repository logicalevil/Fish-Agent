## Context

本变更要解决的是「同一份 `ChatMemory` 被多个 Agent（跨 JVM 实例）并发访问」时的互斥问题。触发它的现实场景是**多实例部署**：同一个会话被负载均衡分发到不同节点。

现有约束（来自 `openspec/project.md` 与既有规范）：

1. **轻依赖**：运行时只依赖 Jackson。任一新增依赖都需要充分理由。
2. **非线程安全是刻意取舍**：`ReActAgent` 不做内部同步，并发安全留给调用方。
3. **扩展点用接口，不用库**：`ChatModel` / `ChatMemory` / `Tool` / `Tracer` / `AgentListener` 都是接口 SPI。
4. **装饰器模式已有先例**：`MessageWindowChatMemory` 就是 `ChatMemory` 的装饰器，新抽象应与之一致。

当前记忆层的并发保护是各自为政的：`FileChatMemory` 用方法级 `synchronized`，`InMemoryChatMemory` 依赖 `CopyOnWriteArrayList`。两者都只在单 JVM 内有效，且无法被替换。

## Goals / Non-Goals

**Goals**

- 框架内**所有**记忆层的并发保护都经过同一个接口，不再出现 `synchronized` 与 COW 各自为政。
- 单机场景零配置、零新增依赖、行为与今天等价。
- 多实例场景可由使用者**自行实现**跨进程锁并接入，无需框架提供任何分布式组件。
- 锁实现能**自我描述**作用域（是否跨进程），供业务侧判断。

**Non-Goals**

- ❌ 框架提供跨进程锁实现（不做 `RedissonMemoryLock`）
- ❌ 引入 Redisson 或任何分布式协调组件作为依赖
- ❌ 拆分 `fish-agent-redisson` 模块 / 重组仓库为多模块
- ❌ 提供 `noop()` 实现（`local()` 已足够廉价，`noop()` 是危险的便利）
- ❌ 框架对 `isDistributed()` 的声明做校验、拦截或告警
- ❌ 改变 `ChatMemory` 接口签名
- ❌ 改造 `ReActAgent` 的循环结构

## Decisions

### D1：抽象放在「记忆」层，而非「Agent」层

**决策**：锁的作用范围是 `ChatMemory` 的读写，不是 `Agent.run` 的整个生命周期。

**理由**：锁要保护的资源是记忆。若锁在 Agent 层，会带来两个问题：

1. 粒度太粗——`Agent` 本身无状态可言，绑定的是记忆；锁在 Agent 上等于锁在「使用记忆的人」而非「记忆」。
2. 无法覆盖「多个 Agent 共享同一份记忆」的场景——那正是本变更的动机。两个 Agent 各自的锁互不相识，仍会并发写同一份记忆。

**代价**：一次 run 内会多次进入临界区（模型调用前后、工具结果回填时）。若使用者接入的跨进程锁 RTT 较高，需自行评估开销。

**已考虑但否决的替代方案**：把锁提到 run 级，让同会话完全串行。语义上更严格（后一个 Agent 必基于前一个的完整结果推理），但牺牲全部并发度，且锁持有时间包含模型调用（秒级到数十秒），对租约型分布式锁极不友好。使用者如有此需求，可在自己的 `MemoryLock` 实现中把资源标识从「记忆 id」改为「会话 id」并延长超时——框架层不需要为此改变抽象位置。

### D2：接口只暴露「执行临界区」一个方法

**决策**：

```java
public interface MemoryLock {
    boolean isDistributed();
    <T> T execute(String resource, Duration timeout, Supplier<T> action);
}
```

而不是暴露 `lock(key)` / `unlock(key)`。

**理由**：`lock`/`unlock` 的配对由调用方负责，是最容易出错的设计——`finally` 漏写、异常路径漏 unlock、嵌套调用重复 unlock 都是经典事故。`execute` 把配对责任收进实现内部，调用方无法写错。

**代价**：`Supplier` 只能返回带值结果，`void` 操作需要写成 `() -> { ...; return null; }`。保持接口最小，暂不提供 `Runnable` 重载。

### D3：`isDistributed()` 作为自描述能力

**决策**：把「是否跨进程有效」做成接口方法，由实现自我声明；框架**不做**任何校验或告警。

**理由**：使用者诉求是「每个用这个框架的都需要标注锁是由哪个客户端实现的」。但用文档要求人标注不可靠——没有机制强制。

把它做成接口方法后，业务侧可以自行判断与决策（例如在多实例环境启动时校验），而框架保持中立。

**为什么不做构建期告警**：框架无法知道使用者是不是多实例部署，`false` 在单机场景完全正常；且无法验证使用者声明的真伪（返回 `true` 但实际没锁是可能的）。告警会被单机用户淹没，也挡不住刻意误报。因此只提供声明能力，不做拦截。

### D4：默认实现是 `MemoryLock.local()`，不是 Redisson

**决策**：框架仅通过静态工厂 `MemoryLock.local()` 提供一个基于 `ReentrantLock` 的本地实现，作为未配置时的默认值。

**理由**：

- 「默认」意味着开箱即用、不配任何东西就能跑。若默认需要 Redisson，则核心 jar 必须依赖 Redisson（含 Netty、reactor 等传递依赖），直接违背 `project.md` 的「运行时只依赖 Jackson」。
- 与现有行为等价：`FileChatMemory` 现在用 `synchronized`，`ReentrantLock` 是其升级版（多了超时能力，正好匹配 `execute` 签名）。因此**不是破坏性变更**。
- 使用者需要跨实例互斥时自行实现并注入，框架不提供也不依赖任何分布式组件。

**为什么用静态工厂而非独立类**：既然默认行为需要有一个实现（否则「不配也能用」无法成立），把它作为接口的静态工厂暴露出来，比藏在 `ReActAgent` 的私有内部类里更好——使用者写自己的锁时可参照它，测试与示例也能直接复用。

**为什么不提供 `noop()`**：`local()` 的开销已经很低（`ConcurrentHashMap` 查一次 + `ReentrantLock` 加解锁）。`noop()` 是危险的便利——容易在排查性能问题时随手使用，然后在生产环境忘记换回来。

### D5：分布式实现完全交给使用者

**决策**：框架不提供 `RedissonMemoryLock`，不做 `fish-agent-redisson` 模块，不重组仓库。

**理由**：使用者的诉求是「锁的实现交给用户配置」。分布式锁的选择高度依赖使用者的基础设施（Redis 客户端版本、连接池管理方式、是否已有 Redisson 实例），框架无法给出一个不冲突的默认。

使用者自行实现时，需要注意（写进 README 而非规范）：

- **不要自建 RedissonClient**，复用应用已有的实例，避免连接池重复与传递依赖版本冲突
- 租约时长必须大于单次临界区的最大耗时，否则锁可能在临界区内失效
- 若用公平锁，Redisson 不支持显式 `leaseTime`，只能依赖看门狗续期，存在「JVM 长暂停导致续期失败」的窗口

**代价**：使用者需要写更多代码。这是「把实现交给用户」的必然成本。

### D6：用装饰器接入，不改 `ChatMemory` 接口

**决策**：新增 `LockedChatMemory.wrap(delegate, lock)`，`ChatMemory` 接口签名不变。

**理由**：

- 与 `MessageWindowChatMemory.windowing(delegate, ...)` 的模式一致；
- 不破坏使用者已有的自定义 `ChatMemory` 实现（若改接口，所有实现都要跟着改，是破坏性变更）；
- 两者可自由组合：`LockedChatMemory.wrap(MessageWindowChatMemory.windowing(FileChatMemory.of(...), 20), lock)`。

**代价**：包装层级可能变深。缓解：`LockedChatMemory.toString()` 暴露被包装对象与锁类型。

### D7：`FileChatMemory` 去掉 `synchronized`，改为经锁访问

**决策**：`FileChatMemory` 的 `synchronized` 移除，读写经注入的 `MemoryLock`。

**理由**：满足「统一抽象」——框架内不再有绕过接口的同步手段。行为等价（默认仍是进程内互斥），但可替换。

**代价**：`FileChatMemory` 的构造签名需要新增接收 `MemoryLock` 的重载。**必须保持既有构造可用**（回退 `MemoryLock.local()`），否则是破坏性变更。

### D8：`InMemoryChatMemory` 保留免锁读的底层结构

**决策**：`InMemoryChatMemory` 接入 `MemoryLock`，但底层仍用 `CopyOnWriteArrayList`。

**理由**：诚实面对性能——COW 的读取本就无需加锁，为它套一层 `ReentrantLock` 只是形式上的统一。接入锁的价值在于「使用者能替换成跨进程实现」与「语义统一」，而非实际互斥。

**规范中如实写明**：该实现在默认本地锁下，其互斥效果来自底层 COW 而非锁本身。这是刻意的实现自由，不粉饰为「锁生效了」。

## 接口草案

```java
package com.zheng.fishagent.memory.lock;

/**
 * 记忆层的互斥扩展点。
 * 框架提供本地实现作为默认值；跨进程实现由使用者自行提供。
 */
public interface MemoryLock {

    /** 跨 JVM 是否有效。false 表示仅在当前进程内互斥。 */
    boolean isDistributed();

    /**
     * 在锁内执行动作。资源的获取与释放由实现负责配对。
     *
     * @param resource 被保护的资源标识，通常是 ChatMemory.id()
     * @param timeout  获取锁的最长等待时间
     * @throws FishAgentException 获取锁失败或超时
     */
    <T> T execute(String resource, Duration timeout, Supplier<T> action);

    /** 默认实现：按资源标识维护 ReentrantLock，进程内互斥。isDistributed() 返回 false。 */
    static MemoryLock local() {
        return new LocalMemoryLock();
    }
}
```

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| 使用者实现跨进程锁时租约配置不当，导致锁在临界区内失效 | 在 README 明确要求「租约 > 单次临界区最大耗时」；不在框架层做自动猜测 |
| `isDistributed()` 声明可能不实（返回 true 但无实际互斥） | 明确记为使用者责任；框架不校验（见 D3，校验无法真正生效反而给人虚假安全感）|
| 一次 run 内频繁进出临界区，跨进程锁 RTT 开销显著 | 装饰器只包住单次记忆操作；若开销不可接受，使用者可在自己的实现中做批处理 |
| 移除 `FileChatMemory.synchronized` 可能引入回归 | 保留既有构造签名（回退 `local()`），并补并发写入测试 |
| 装饰器层级变深，调试困难 | `LockedChatMemory.toString()` 包含底层标识与锁类型 |
