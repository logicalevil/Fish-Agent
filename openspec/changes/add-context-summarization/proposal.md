## Why

`MessageWindowChatMemory` 用「滑窗」控制上下文长度：超过窗口的早期消息**直接从上下文中消失**。这对短对话够用，但有明显缺陷：

- **信息硬丢失**。窗口一滑，早期结论、用户偏好、已确认的事实都不再进入模型视野，模型会「忘记」此前达成的共识；
- **无法恢复**。虽然 `fullHistory()` 保留了原文，但框架没有让模型感知到「早前有对话」的机制，模型不会主动去查。

长会话（如多轮排障、连续任务编排）因此退化为「只有最近几轮有效」。

本变更引入**摘要压缩**：当累积消息超过阈值时，把早期消息交给模型压成一条摘要，随后的上下文变成「摘要 + 最近若干条原文」。早期信息以**压缩形态**继续可见，而不是消失。

设计参照 AgentScope Java 已验证的做法，并遵循本项目既有的两条约束：

1. **框架不创建线程**（`agent-execution` 规范）→ 采用**同步压缩**，在写入时判断触发；
2. **装饰器模式**（与 `MessageWindowChatMemory` 一致）→ 做成 `ChatMemory` 的装饰器，不改接口。

## What Changes

- **新增 `SummarizingChatMemory`**（`com.zheng.fishagent.memory`）：`ChatMemory` 的装饰器，持有底层记忆（原文）与一个 `ChatModel`（用于生成摘要）。
- **触发方式**：`add()` 时若「未摘要的消息数」超过 `triggerMessages`，同步执行一次摘要压缩。
- **产出形态**：压缩后 `messages()` 返回 `[摘要消息] + [最近 keepMessages 条原文]`；摘要消息为 **USER 角色**，`content` 前缀固定的引导语。
- **链式累积**：再次压缩时，上一次的摘要会作为输入的一部分参与新的摘要，避免早期信息在二次压缩时丢失。
- **原文保留**：底层记忆始终保存全部消息，通过 `fullHistory()` 读取。
- **失败回退**：摘要调用模型失败时，退化为「只保留最近 keepMessages 条」（即窗口语义），不中断对话。
- **`FishAgent` / `ReActAgent` 不改动**：它们照常调用 `memory.messages()`，压缩对上层透明。

明确**不做**的事：

- ❌ 不创建线程、不引入定时任务（保持「框架不创建线程」的约束）
- ❌ 不改 `ChatMemory` 接口签名
- ❌ 不提供跨会话的长期记忆（`MEMORY.md` 那类）
- ❌ 不实现「会话日志检索」能力（模型无法反向查询原文）
- ❌ 不做分级压缩（不先截断工具参数/结果，直接摘要）

## Capabilities

### New Capabilities

- `context-summarization`：摘要压缩的契约——触发条件、产出形态、摘要消息的角色与内容约定、链式累积语义、失败回退行为。

### Modified Capabilities

- `conversation-memory`：现有需求描述 `ChatMemory` 的三种实现与组合方式，需补充 `SummarizingChatMemory` 的实现说明，并明确「压缩只改变参与上下文的消息列表，不删除原文」。

`agent-execution` 与 `facade-assembly` **不受影响**——摘要发生在记忆层，`ReActAgent` 的循环结构、提示词处理、写入时序均不改变。

## Impact

**代码**：

- 新增：`memory/SummarizingChatMemory.java`
- 新增：`memory/SummaryConfig.java`（触发阈值、保留条数等参数的载体）
- 修改：`memory/ChatMemory.java`（仅在 Javadoc 中补充「实现可做压缩」的说明，不改签名）
- 测试：新增 `SummarizingChatMemoryTest`，使用 `FakeChatModel` 脚本化摘要返回，保证离线可跑

**依赖与构建**：

- 依赖**零变化**（仍为 Jackson + JUnit 5）
- 构建方式不变

**API 兼容性**：

- 非破坏性变更。`SummarizingChatMemory` 是新增类型，既有用法不受影响。

**风险**：

| 风险 | 说明 |
|---|---|
| 同步压缩带来一次明显的响应延迟 | 每跨过阈值时，该次 `add()` 会多出一次模型调用（数秒）。缓解：参数可配，默认阈值取大一些（20 条），使触发频率低 |
| 摘要是有损的 | 20 条压成 1 条必然丢失细节。缓解：`fullHistory()` 保留原文；文档中明确说明这是有损操作 |
| 摘要模型返回空内容 | 需按失败回退处理，退化为窗口裁剪 |
| 链式累积可能让摘要过长 | 每次压缩都把旧摘要并入输入，摘要本身会缓慢增长。缓解：提示词要求输出简短；必要时可限制摘要长度 |
