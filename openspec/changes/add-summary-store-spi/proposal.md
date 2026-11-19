## Why

`SummarizingChatMemory` 目前把三件事压在一个类里：**判断何时压缩**、**调模型生成摘要**、**保存摘要文本与游标**。其中第三件事以三个私有字段（`summary`、`summarizedUpTo`、`lastCompactionSucceeded`）的形式直接挂在实例上，导致两个具体问题：

1. **摘要状态无法跨会话实例共享**。同一会话在多个实例间轮转（分布式部署、滚动重启）时，每个实例都从「无摘要」重新开始，缓存等于没有；
2. **无法替换存储介质**。若要把摘要放进 Redis，只能复制整个类或改字段可见性，没有扩展点。

本变更按记忆设计文档 1.7 节把摘要拆成三件独立的小东西（`Summarizer` / `SummaryState` / `SummaryStore`），让「摘要存哪」成为可替换的 SPI，同时**保持 `SummarizingChatMemory` 的对外行为完全不变**——这是零依赖、零破坏的架构准备。

## What Changes

- **新增 `SummaryState`**（`com.zheng.fishagent.memory.summary`）：不可变 record，承载摘要正文、已摘要游标、上次是否成功三项状态；提供序列化用的 `toMap()` / `fromMap()`，便于后续 Redis/MySQL 实现复用。
- **新增 `Summarizer` 接口**：把「消息列表 + 旧摘要 → 新摘要文本」抽成纯函数式扩展点。框架提供 `ModelSummarizer`（即现有逻辑：调 `ChatModel`、渲染消息、截断工具结果、链式累积）。
- **新增 `SummaryStore` 接口**：按会话标识读写 `SummaryState`，含 `load` / `save` / `clear`。默认实现为 `InMemorySummaryStore`（零依赖，进程内）。
- **改造 `SummarizingChatMemory`**：内部改为持有 `Summarizer` + `SummaryStore`，三个状态字段移入 `SummaryState` 并由 store 承载；新增接收 `Summarizer` / `SummaryStore` 的工厂重载。
- **保留全部既有 API**：`of(delegate, model)`、`of(delegate, model, trigger, keep)`、`summary()`、`lastCompactionSucceeded()`、`triggerMessages()`、`keepMessages()`、`fullHistory()`、`delegate()`、`SUMMARY_PREFIX` 全部保留，行为不变。
- **`ReActAgent` / `FishAgent` 不改动**：它们只通过 `ChatMemory` 接口使用记忆，拆分对其透明。

明确**不做**的事：

- ❌ 不引入 Redis / MySQL 驱动（存储实现在独立模块，本变更只定义接口与内存默认实现）
- ❌ 不改 `ChatMemory` 接口签名
- ❌ 不改变压缩触发时机、产出形态、摘要角色与前缀、链式累积语义、失败回退行为
- ❌ 不引入并发控制（仍由 `MemoryLock` 承担，见 `memory-locking`）
- ❌ 不实现跨会话的长期记忆

## Capabilities

### New Capabilities

- `summary-store`: 摘要状态的存储扩展点契约——`SummaryState` 的字段与序列化、`SummaryStore` 的读写语义与默认内存实现、`Summarizer` 的输入输出约定，以及「store 失效不得中断对话」的降级边界。

### Modified Capabilities

- `context-summarization`: 现有需求描述的是 `SummarizingChatMemory` 的整体行为（触发、产出、链式累积、失败回退）。需补充：摘要状态经由 `SummaryStore` 承载、`Summarizer` 为可替换扩展点，且上述行为在状态从 store 重新加载后依然成立。

## Impact

**代码**：

- 新增：`memory/summary/SummaryState.java`、`memory/summary/Summarizer.java`、`memory/summary/ModelSummarizer.java`、`memory/summary/SummaryStore.java`、`memory/summary/InMemorySummaryStore.java`
- 修改：`memory/SummarizingChatMemory.java`（拆分内部实现，保持公开 API 与行为）
- 测试：新增 `SummaryStateTest`、`InMemorySummaryStoreTest`、`ModelSummarizerTest`；改造 `SummarizingChatMemoryTest` 增加「状态经 store 往返后行为不变」的用例

**依赖与构建**：

- 依赖零变化（仍为 Jackson 2.18.2 + JUnit 5.11.4）
- 构建方式不变（`mvn -f pom.xml test`）
- 不重组仓库为多模块布局

**API 兼容性**：

- 非破坏性变更。新增类型皆为新增；`SummarizingChatMemory` 既有工厂与访问器全部保留，默认使用 `InMemorySummaryStore`，行为与改动前等价。

**风险**：

| 风险 | 说明 |
|---|---|
| 拆分引入行为漂移 | 通过保留既有测试全集 + 新增「经 store 往返」用例来约束；默认 store 为内存，路径与今天一致 |
| `SummaryState` 序列化格式未来需兼容 | 以 `toMap()` 显式列字段（不依赖反射），并在 spec 中固定键名与缺失键的默认值 |
| 使用者误以为换 store 就能跨进程共享 | 文档需写明：跨进程共享还要求 `MemoryLock` 使用分布式实现，否则并发读写不受保护 |
