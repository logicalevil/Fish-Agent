## Context

`SummarizingChatMemory` 是 `ChatMemory` 的装饰器，负责把早期消息压成一条摘要。它当前把三类职责混在一起：

```
SummarizingChatMemory
├─ 何时压缩        add() 里比较 pending 数与 triggerMessages
├─ 怎么压缩        generateSummary() / render() / truncate() / SUMMARY_PROMPT_TEMPLATE
└─ 压缩到什么状态   summary / summarizedUpTo / lastCompactionSucceeded  ← 私有字段
```

第三条是问题的根源。这三个字段表征的是「这份会话的摘要进度」，属于**会话级数据**；但它们的生命周期被绑死在实例上：

- 实例销毁即丢失，重新构造时 `summary == null`，压缩从零开始；
- 同一会话在多个实例间轮转时，各实例各持一份互不相干的进度；
- 想换成 Redis 存放，没有插入点。

约束来自项目既定原则：

- **零依赖**：核心运行时只有 Jackson，不能为此引入 Redis/MySQL 客户端；
- **行为不变**：`AGENTS.md` 规定「只改实现、行为不变 → 直接改代码」，本变更属于重构；
- **不创建线程**：压缩仍在 `add()` 中同步完成；
- **默认开箱可用**：不配置任何东西时，`SummarizingChatMemory.of(delegate, model)` 必须照常工作。

## Goals / Non-Goals

**Goals:**

- 把摘要状态从实例字段抽成**可序列化的值对象** `SummaryState`；
- 把「摘要状态存哪」抽成 SPI `SummaryStore`，默认实现为进程内内存，行为与今天等价；
- 把「怎么生成摘要」抽成 SPI `Summarizer`，默认实现 `ModelSummarizer` 即现有逻辑；
- 保持 `SummarizingChatMemory` 的**全部公开 API 与可观察行为不变**，既有测试原样通过。

**Non-Goals:**

- 不提供 Redis / MySQL 的 `SummaryStore` 实现（后续独立模块或调用方自实现）；
- 不改变压缩触发时机、产出形态、摘要角色与固定前缀、链式累积、失败回退语义；
- 不引入并发控制（跨进程互斥仍由 `memory-locking` 的 `MemoryLock` 承担）；
- 不为 `SummaryState` 引入版本协商或迁移机制——本变更只固定当前格式；
- 不做摘要的增量/分片压缩。

## Decisions

### 决策 1：三件套的职责切分

```
SummarizingChatMemory（装饰器外壳，行为契约的守门人）
        │
        ├──► Summarizer（纯生成：List<Message> + 旧摘要 → 新摘要文本）
        │        └── ModelSummarizer（默认：调 ChatModel）
        │
        └──► SummaryStore（持久化：sessionId → SummaryState）
                 └── InMemorySummaryStore（默认：进程内）
```

`SummarizingChatMemory` 自身不再持有任何摘要状态字段，只在每次操作时向 store 读取、必要时写回。

**为什么不是反过来（让 store 知道 summarizer）**：store 的职责单纯是存取，不应理解「何时该压缩」。触发判断留在装饰器里，store 保持无逻辑，这样任何存储实现（Redis/MySQL）都只需实现 CRUD。

**考虑过的替代方案**：

| 方案 | 否决理由 |
| --- | --- |
| 只抽 `SummaryStore`，`Summarizer` 留作私有方法 | 无法替换摘要提示词/模型策略，而企业场景常需换成更便宜的模型 |
| 把三者合成一个 `SummaryStrategy` 接口 | 接口过宽，使用者为换存储就得实现全部三个方法 |
| 让 `SummarizingChatMemory` 继承抽象基类 | Java 单继承，堵死使用者自身继承体系 |

### 决策 2：`SummaryState` 用 record + 显式 `toMap()`

```java
public record SummaryState(String summary, int summarizedUpTo, boolean lastCompactionSucceeded)
```

- `summary == null` 表示「从未成功压缩过」，与现有语义一一对应；
- 使用**显式 `toMap()` / `fromMap()`**，不用 Jackson 反射：
  - 核心已有 Jackson，但反射序列化对 record 字段改名不友好，且缺失键行为不可控；
  - 显式映射能精确定义「键缺失时的默认值」，这对 Redis/MySQL 实现者是可依赖的契约。

**考虑过的替代方案**：直接让 record 实现 `Serializable`。否决——Java 序列化跨版本脆弱，且不便于人工排查 Redis 中的内容。

### 决策 3：默认 store 为内存实现，而非「必须注入」

`of(delegate, model)` 与 `of(delegate, model, trigger, keep)` 保持原签名，内部默认 `new InMemorySummaryStore()`。

**为什么**：`AGENTS.md` 要求行为不变才能直接重构。强制注入 `SummaryStore` 会让所有现有调用方编译失败，属于破坏性变更；而本变更的目标是**为后来者铺路**，不是现在就换存储。

代价：每个 `SummarizingChatMemory` 实例各有一个 store，跨实例共享需显式传入同一个 store。这一点写进 Javadoc 与 spec。

### 决策 4：store 失效不得中断对话

`load` / `save` 抛异常时，装饰器**吞掉异常并降级**为「视为无摘要状态」（即退化为窗口裁剪），与现有「摘要失败不回中断对话」的策略一致。

**理由**：摘要压缩整体上是**优化手段**。存储不可用（Redis 抖动）不应让用户的对话直接失败——这与 `ToolRegistry.execute` 吞异常返回错误文本是同一设计哲学。

### 决策 5：`summarizedUpTo` 语义保持不变

现有语义是「delegate 中的下标」，而非「自增计数」。拆分后仍以 delegate 的真实下标存储，因为：

- 它必须和 `delegate.messages().size()` 比较才能算出 pending 数；
- 若改成自增计数，`clear()` 时的重置逻辑会与 delegate 的实际清空产生错位。

`SummaryState` 中的 `summarizedUpTo` 因此是**绑定具体 delegate 位置的游标**，spec 中需写明「仅在同一 delegate 上有效」。

## Risks / Trade-offs

| 风险 | 缓解 |
| --- | --- |
| 拆分导致行为漂移 | 保留既有 `SummarizingChatMemoryTest` 全集不动；新增「状态经 store 往返后行为一致」用例；默认 store 为内存，路径与今天完全一致 |
| `SummaryState` 的 map 格式将来需演进 | 现在只固定键名与缺失默认值；不做版本号，等真有第二版再加，避免过度设计 |
| 使用者以为换个 store 就能跨实例共享 | Javadoc 与 README 写明：跨进程共享还需 `MemoryLock` 用分布式实现，否则并发读写无保护 |
| `summarizedUpTo` 与 delegate 错位（例如换了底层记忆） | 在 spec 中声明该游标只在「同一 delegate 实例」上有意义；store 的 key 建议包含会话标识 |
| 每次 `add`/`messages` 都读一次 store，内存实现带来额外调用开销 | 内存实现为一次 Map 查找，可忽略；若将来 store 是 Redis，由使用者在实现侧做本地缓存 |

## Migration Plan

本变更**非破坏性**，不需要迁移：

1. 新增 5 个类型（`SummaryState` / `Summarizer` / `ModelSummarizer` / `SummaryStore` / `InMemorySummaryStore`）；
2. 改造 `SummarizingChatMemory` 内部实现，公开 API 不变；
3. 既有测试全部保留，另增新类型的单元测试；
4. 回滚策略：删除新增类型、还原 `SummarizingChatMemory` 即可，无数据格式变更。

## Open Questions

- 暂无阻塞项。后续若增加 Redis 实现，需再决定 store 的 key 是「会话 id」还是「会话 id + extractor 版本」。
