## 1. 值对象与接口

- [ ] 1.1 新增 `memory/summary/SummaryState.java`：record（`summary`、`summarizedUpTo`、`lastCompactionSucceeded`）
- [ ] 1.2 实现 `SummaryState.toMap()`：显式写入三个固定键，不用反射
- [ ] 1.3 实现 `SummaryState.fromMap(Map)`：null/空映射与缺失键回退为初始值（null、0、true）
- [ ] 1.4 提供 `SummaryState.initial()` 静态工厂
- [ ] 1.5 新增 `memory/summary/Summarizer.java`：`summarize(List<Message>, String previousSummary)`
- [ ] 1.6 新增 `memory/summary/SummaryStore.java`：`load` / `save` / `clear`

## 2. 默认实现

- [ ] 2.1 新增 `memory/summary/InMemorySummaryStore.java`：`ConcurrentHashMap` 支撑，零新依赖
- [ ] 2.2 `load` 对未知会话返回 `SummaryState.initial()`，不返回 null
- [ ] 2.3 `clear` 只移除指定会话，不影响其它会话
- [ ] 2.4 新增 `memory/summary/ModelSummarizer.java`：迁移 `generateSummary` / `render` / `label` / `truncate` 与提示词模板
- [ ] 2.5 `ModelSummarizer` 保留工具结果截断（500 字符）与链式累积（旧摘要并入输入）
- [ ] 2.6 `ModelSummarizer` 返回 null 或空白表示失败，不抛受检异常

## 3. 改造 SummarizingChatMemory

- [ ] 3.1 移除三个状态字段（`summary`、`summarizedUpTo`、`lastCompactionSucceeded`），改为从 `SummaryStore` 读取
- [ ] 3.2 构造器持有 `Summarizer` 与 `SummaryStore`
- [ ] 3.3 保留既有工厂 `of(delegate, model)` 与 `of(delegate, model, trigger, keep)`，默认 `ModelSummarizer` + `InMemorySummaryStore`
- [ ] 3.4 新增工厂重载：接收自定义 `Summarizer` 与/或 `SummaryStore`
- [ ] 3.5 `add()` 的触发判断改用 store 中的游标
- [ ] 3.6 `compact()` 成功后写回 store；失败时也写回（推进游标 + `lastCompactionSucceeded=false`）
- [ ] 3.7 `messages()` 从 store 读取摘要，产出形态保持「摘要 + 最近 keepMessages 条」
- [ ] 3.8 `clear()` 同时清空 delegate 与 store 中的该会话状态
- [ ] 3.9 保留访问器 `summary()`、`lastCompactionSucceeded()`、`triggerMessages()`、`keepMessages()`、`fullHistory()`、`delegate()`，改为读 store
- [ ] 3.10 store 的 `load` / `save` 抛异常时吞掉异常并降级为初始状态，不中断对话
- [ ] 3.11 更新类 Javadoc，说明摘要状态由 `SummaryStore` 承载

## 4. 测试

- [ ] 4.1 `SummaryStateTest`：往返、固定键名、缺失键回退、null 映射回退
- [ ] 4.2 `InMemorySummaryStoreTest`：未保存返回初始状态、保存可读回、clear、会话隔离、并发不抛异常
- [ ] 4.3 `ModelSummarizerTest`：角色渲染前缀、工具结果截断、旧摘要参与输入、不创建线程
- [ ] 4.4 改造 `SummarizingChatMemoryTest`：验证「预置 store 状态后可读到摘要」
- [ ] 4.5 新增用例：压缩结果写回 store
- [ ] 4.6 新增用例：两个实例共享 store 与同一会话时，摘要互通
- [ ] 4.7 新增用例：注入固定返回的 `Summarizer` 时不调用 `ChatModel`
- [ ] 4.8 新增用例：store 在 `load`/`save` 抛异常时对话不中断，且 `lastCompactionSucceeded()` 为 false
- [ ] 4.9 回归：既有 `SummarizingChatMemoryTest` 全部用例保持通过
- [ ] 4.10 确认全套测试离线可跑、不依赖网络与真实模型

## 5. 文档

- [ ] 5.1 README「上下文摘要压缩」章节补充：摘要状态可由 `SummaryStore` 承载
- [ ] 5.2 README 说明「跨实例共享摘要需注入同一个 `SummaryStore`，且并发保护需配合分布式 `MemoryLock`」
- [ ] 5.3 `openspec/project.md` 模块划分补充 `memory/summary/` 子包
- [ ] 5.4 确认「运行时不新增依赖」并说明依据

## 6. 归档

- [ ] 6.1 `openspec validate add-summary-store-spi`
- [ ] 6.2 确认每条 Scenario 有对应测试或可验证手段
- [ ] 6.3 `openspec archive add-summary-store-spi`
