## Context

`MessageWindowChatMemory` 通过滑窗限制上下文长度，早期消息会被**直接丢弃**（不进入模型视野）。本变更用「摘要」替代「丢弃」，让早期信息以压缩形态继续可见。

现有约束：

1. **框架不创建线程**：`agent-execution` 规范明确 `ReActAgent` SHALL NOT 创建线程、SHALL NOT 做内部同步。
2. **装饰器模式已有先例**：`MessageWindowChatMemory.windowing(delegate, n)`。
3. **只依赖 Jackson**：摘要需要模型调用，但模型是既有扩展点（`ChatModel`），不引入新依赖。
4. **`memory` 层不依赖 `agent` 层**：`ChatMemory` 当前只依赖 `message` 包，新增类需遵守同样的层次。

参照对象：AgentScope Java 的 `ConversationCompactor`（已阅读源码，见「Decisions」中的引用）。

## Goals / Non-Goals

**Goals**

- 早期消息以**摘要**形态保留在上下文中，而非消失。
- 压缩对上层透明：`ReActAgent` 一行不改。
- 失败可退化：摘要失败不影响对话继续。
- 原文永不丢失。

**Non-Goals**

- 不提供跨会话长期记忆。
- 不提供「模型反查原文」的能力（无 `session_history` 类工具）。
- 不做异步/后台压缩。
- 不做分级压缩（先截断工具结果再摘要）。

## Decisions

### D1：做成装饰器，不改 `ChatMemory` 接口

**决策**：`SummarizingChatMemory implements ChatMemory`，内部持有 `delegate`（原文）与 `ChatModel`。

**理由**：

- 与 `MessageWindowChatMemory` 一致，调用方看到统一的组装风格；
- 不改接口 → 不破坏使用者已有的自定义 `ChatMemory` 实现；
- 两者可组合：先窗口后摘要，或先摘要后窗口。

**代价**：与窗口装饰器叠加时，语义需要使用者理解——窗口裁的是「返回条数」，摘要压的是「早期内容」。

### D2：同步压缩，在 `add()` 时触发

**决策**：`add()` 中判断「未摘要消息数 > triggerMessages」，若是则**同步**执行压缩。

```java
@Override
public void add(Message message) {
    delegate.add(message);
    if (pendingCount() > triggerMessages) {
        compact();
    }
}
```

**理由**：

- 「框架不创建线程」是硬约束，异步必然要线程；
- 触发频率低（每累积 triggerMessages 条才一次），代价可控；
- 在 `add()` 做而不是 `messages()`：后者是热点路径（每次构造请求都调用），同步调模型会拖慢**每一次**推理。

**代价**：跨过阈值的那一次 `add()` 会明显变慢（多一次模型调用）。文档需明确说明。

**替代方案**：在 `messages()` 中惰性压缩——否决，理由如上。

### D3：摘要是一条 **USER** 消息，带固定引导语

**决策**：

```java
Message.user("Here is a summary of the conversation so far:\n\n" + summary)
```

而不是 `Message.system(...)`。

**理由**（依据 AgentScope Java 源码）：

1. 源码中摘要消息的 role 为 `MsgRole.USER`（`ConversationCompactor.buildSummaryMessage`）；
2. 其源码注释指出调用方要在压缩前后**分离并重新前置 SYSTEM 消息**，且 `formatMessagesForSummary` 会**过滤掉 SYSTEM**——说明摘要不参与 SYSTEM 通道；
3. 本项目规范规定「系统提示词 SHALL NOT 写入 `ChatMemory`」。若摘要用 SYSTEM，会与「SYSTEM 只承载框架提示词」的语义混淆。

**代价**：摘要与真实用户输入在 role 上无法区分。缓解：`content` 带固定前缀 `Here is a summary of the conversation so far:`，可被识别。

### D4：产出形态为 `[摘要] + [最近 keepMessages 条]`

**决策**：压缩后 `messages()` 返回：

```
[摘要消息] [尾部第 1 条] [尾部第 2 条] ... [最新一条]
```

**理由**：AgentScope 源码中即为该顺序。

```java
List<Msg> compacted = new ArrayList<>();
compacted.add(summaryMsg);
compacted.addAll(tail);
```

**代价**：摘要会掩盖被压缩区间的原始顺序细节。`fullHistory()` 保留原文供排查。

### D5：`keepMessages` 必须明显小于 `triggerMessages`

**决策**：构造时校验 `keepMessages < triggerMessages`，否则抛 `FishAgentException`。

**理由**：若两者接近（如都是 20），会出现「压缩完立即又触发」的抖动。AgentScope 的默认值（`triggerMessages=50`、`keepMessages=20`）也保持了 2.5 倍差距。

**默认值**：`triggerMessages=20`、`keepMessages=5`。

### D6：链式累积——上一次的摘要参与下一次摘要

**决策**：压缩时，输入包含「上一次的摘要消息」+「本次待压缩的消息」。

**理由**：否则二次压缩会让「摘要 S1」本身被压缩掉，早期信息彻底丢失：

```
第 1 次: [m1..m20] → S1          视图: [S1][m21..]
第 2 次: [m21..m40] → S2         视图: [S2][m41..]     ← S1 不见了
正确:    [S1][m21..m40] → S2     视图: [S2][m41..]     ← S1 的信息并入 S2
```

AgentScope 源码注释印证：

> Keep prior summaries in the summarization input so each compaction builds on the previous one.

**代价**：摘要文本会缓慢增长。缓解：提示词中要求输出控制在若干句内。

### D7：摘要失败退化为窗口裁剪

**决策**：模型调用抛异常或返回空白时，**不抛异常**，改为「只保留最近 `keepMessages` 条」。

**理由**：摘要是**优化手段**，不是正确性所需。失败时退化成既有行为（窗口裁剪）即可，没有理由让对话失败。

同时更新内部状态：把「已摘要到第几条」推进到当前位置，避免每次 `add()` 都重试导致持续变慢。

**代价**：一次静默降级。在 `toString()` 中暴露「上次摘要是否成功」，便于诊断。

### D8：不改 `ReActAgent`、不动规范中的循环与写入时序

**决策**：`SummarizingChatMemory` 仅实现 `ChatMemory`，`ReActAgent` 完全不变。

**理由**：压缩的本质是「改变 `messages()` 的返回值」，属于记忆层职责。若把压缩放进 `ReActAgent` 循环，会同时违反：

- 「系统提示词按请求前置且从不落库到记忆」
- 「记忆中的消息顺序保证调用在前、结果在后」
- 「轮数语义」（摘要调用不应计入 `maxIterations`）

**代价**：无法在「模型调用前」这一精确时机触发，只能按消息条数触发。可接受。

### D9：`memory` 层不依赖 `agent` 层

**决策**：`SummarizingChatMemory` 只依赖 `com.zheng.fishagent.message.Message` 与 `com.zheng.fishagent.llm.ChatModel`，不依赖 `agent` 包。

**理由**：保持既有分层（`ChatMemory` 现在只依赖 `message`）。`ChatModel` 属于 `llm` 层，被 `memory` 依赖不产生环。

**代价**：使用者需要自己把 `ChatModel` 实例传进 `SummarizingChatMemory` 的构造器（框架不会自动注入）。属于可接受的显式装配成本。

## 接口草案

```java
public final class SummarizingChatMemory implements ChatMemory {

    /** 摘要消息的固定前缀，用于识别「这条是摘要」而非真实用户输入。 */
    public static final String SUMMARY_PREFIX = "Here is a summary of the conversation so far:";

    /**
     * @param delegate         底层记忆（原文全量保留）
     * @param model            用于生成摘要的模型；可以是更轻量的模型
     * @param triggerMessages  未摘要消息数超过该值时触发压缩
     * @param keepMessages     压缩后保留的最近消息条数，必须小于 triggerMessages
     */
    public static SummarizingChatMemory of(ChatMemory delegate, ChatModel model,
                                           int triggerMessages, int keepMessages);

    @Override public String id();
    @Override public void add(Message message);
    @Override public List<Message> messages();     // [摘要] + [最近 keepMessages 条]
    @Override public void clear();

    /** 未被压缩的完整原文。 */
    public List<Message> fullHistory();

    /** 当前摘要内容；从未成功压缩过时为 null。 */
    public String summary();

    /** 上一次摘要是否成功，便于诊断降级情况。 */
    public boolean lastCompactionSucceeded();
}
```

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| 同步压缩导致偶发延迟 | 阈值默认 20，触发频率低；README 明确提示该行为 |
| 摘要有损 | `fullHistory()` 保留原文；文档说明这是有损操作 |
| 链式累积使摘要增长 | 提示词要求简短输出 |
| 摘要与用户输入 role 相同，难以区分 | 固定前缀 `SUMMARY_PREFIX` |
| 摘要模型的输出质量影响效果 | 支持传入独立（更轻量或更强）的模型 |
| 与 `MessageWindowChatMemory` 叠加时语义不清 | 文档中给出推荐组合方式：外层摘要、内层窗口 |
