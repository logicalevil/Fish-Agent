## Context

Fish-Agent 是一个已完成 v0.1.0 的 Java 框架，本次任务是把它的**现有行为**固化为 OpenSpec 基线规范。这里的「设计」不是在提案新架构，而是记录**已经做出的架构决策及其理由**，以便后续变更知道哪些结构是有意为之、不能顺手改掉。

约束：不改动任何生产逻辑；规范必须与代码一致；不得把已知取舍粉饰成理想行为。

## Goals / Non-Goals

**Goals**
- 为每个对外可观察的行为建立一条可测的 Requirement + Scenario。
- 记录关键取舍的**理由**，让后续变更能判断「这是 bug 还是设计」。
- 让 AI 协作者无需通读 35 个源文件即可理解系统契约。

**Non-Goals**
- 不重构 `ReActAgent`、不拆分模块、不新增抽象层。
- 不引入 OpenTelemetry、Spring、HTTP 客户端等新依赖。
- 不修改公开 API 签名，不新增功能。
- 不为「应该怎样」的愿望写规范——只写「现在怎样」。

## Decisions

### D1：规范粒度按「能力」而非「包」

**决策**：12 份规范按能力切分（`agent-execution`、`tool-registry`、`conversation-memory` …），而非按 Java 包（`agent`、`tool`、`memory` …）切分。

**理由**：包结构是实现的偶然产物。例如「工具容错」横跨 `ToolRegistry`、`ReActAgent` 和 `ToolSchemaGenerator` 三个包；若按包切分，同一个行为契约会被拆到三处，评审时无法完整看到。能力切分让「一个能力 = 一份可独立评审的契约」。

**代价**：一份规范可能引用多个类，读者需要跨包跳转。可接受——规范本就该描述行为而非文件布局。

### D2：把已知取舍写成规范，并显式标注为约束

**决策**：对四项已知的设计取舍，规范记录其**现状**而非理想行为：

| 取舍 | 规范中的表述 | 理由 |
|---|---|---|
| `ReActAgent` 非线程安全 | 「SHALL NOT be safe for concurrent use」+「框架内部不创建线程、不做同步」 | 并发安全需要锁或每会话实例，当前刻意留给调用方决定。若规范写成「必须线程安全」，就变成了一条永远无法通过的愿望 |
| 系统提示词快照冻结 | 「SHALL NOT invalidate this cache」+「工具必须在首次 run 前注册完毕」 | 每次 run 重算提示词需要版本化缓存，而工具集在正常用法下是构建期固定的。冻结换来的是零重复拼装 |
| 监听器异常不隔离 | 「A throwing listener aborts the remaining forwards」 | 与普通 Java 调用语义一致，让问题立刻暴露；隔离会把异常藏起来。需要隔离的调用方自行 try/catch |
| `wrap()` 丢失结构化错误码 | 规范只规定消息格式与 cause 保留 | 这是当前实现的真实能力边界。补错误码体系属于新功能，不在本次范围 |

**理由**：规范若只写理想状态，就会在第一次评审时被现实证伪，然后被整体忽略。写清「现状 + 理由 + 边界」才能成为有用的评审基准。

**代价**：规范读起来不如「愿望文档」漂亮。这是刻意的。

### D3：Scenario 必须映射到真实存在的测试或可观察行为

**决策**：每条 Scenario 都从现有 35 个测试的断言、或从代码中可被直接观察的行为推导而来，不写「将来应该验证」的场景。

**理由**：Scenario 是未来测试用例的来源。写出无法验证的场景会让规范与代码脱节。作为对照，规范中的数字（4 条消息、112 tokens、3 次迭代、窗口保留 4 条）全部来自实际断言的精确值，而不是「大约」「若干」这类模糊表述。

**代价**：规范记录的是当前实现细节中的具体数字，未来实现优化时这些数字可能需要修改——但那正是规范变更应该被评审的时刻。

### D4：门控器（FinalAnswerGate）独立成能力规范

**决策**：把流式门控从 `agent-execution` 中拆出，成为独立的 `streaming-answer-gate`。

**理由**：门控的算法复杂度（跨 token 边界匹配、Action 分支重置缓冲、已下发游标）与主循环无关，且它是「用户会不会看到中间推理」这一可直接观察的体验契约。混在主循环规范里会被迭代预算、token 累计等条目淹没。

### D5：删除两个零字节文件

**决策**：删除 `src/main/java/module-info.java` 与 `src/main/java/com/zheng/Main.java`。

**理由**：
- `module-info.java` 为空文件。空的模块描述符无法通过 JPMS 校验，对 jar 打包无贡献（模块名实际由 manifest 的 `Automatic-Module-Name` 提供）。保留它会让读者误以为项目已模块化。
- `com/zheng/Main.java` 是空的脚手架残留，不在任何包的公开 API 中，也无编译产物意义。

**替代方案**：保留并加注释说明「占位」。否决——零字节文件加注释仍是噪音，且 git 历史足以保留痕迹。

**风险**：若用户本地未提交的改动依赖这两个文件（例如正在填 `module-info`），删除会有冲突。缓解：本次改动会明确列出这两个删除项，用户可在合并前撤回。

### D6：修正 README 而非保留漂移

**决策**：修正 README 中 `ChatModel` 的方法名（`chat`/`name` → `call`/`stream`/`modelName`），并补齐项目结构清单中缺失的追踪类。

**理由**：README 是使用者首先读到的契约文档。示例代码与实际接口不符会让使用者直接编译失败——这是比规范缺失更直接的伤害。规范与 README 指向同一个接口时必须一致。

### D7：规范语言与代码注释一致——中文正文 + 英文关键字

**决策**：Requirement 与 Scenario 的正文用中文描述，`SHALL` / `MUST` / `WHEN` / `THEN` 等规范关键字保留英文。

**理由**：项目既有代码注释、Javadoc、README 全为中文，规范混用中文正文保持一致性。而 `SHALL`/`MUST` 是规范语义的通用标记，翻译成「应」「必须」会损失 RFC 2119 的明确强度区分，也让 grep 与工具链处理变难。

## Architecture Snapshot

本次规范所固化的分层结构（后续变更应以此为基线）：

```
FishAgent（门面，唯一推荐入口）
    │  组装 model + tools + memory + 行为参数
    ▼
ReActAgent（唯一 Agent 实现，有状态）
    ├── 主循环：invoke → resolveToolCalls → executeTool → observation
    ├── ToolCallMode 分支收敛在 resolveToolCalls / observation / invoke 三处
    ├── FinalAnswerGate（文本模式的流式门控）
    └── Spans（ambient ThreadLocal，默认 NOOP 零开销）
         │
         ├── ChatModel ──► OllamaChatModel（java.net.http，唯一 HTTP 依赖）
         ├── ToolRegistry ──► FunctionTool / MethodTool ──► ToolSchemaGenerator
         ├── ChatMemory ──► InMemory / MessageWindow(decorator) / File(JSONL)
         └── AgentListener ──► 事件出口；CompositeAgentListener 做扇出
```

**关键不变量**（这些结构边界是本次规范的核心，改动它们意味着改契约）：

1. **唯一终止条件**：某一轮模型没有请求任何工具 → 该轮内容即最终答案。三个出口（正常 / 轮数耗尽 / 死循环）共用同一个 `finish()`。
2. **模式差异收敛在三处**：`resolveToolCalls`、`observation`、`invoke`。循环主体两种模式共用。
3. **失败分层**：工具失败 → 文本回填（可自愈）；模型/IO 失败 → 抛异常（可重试）；预算耗尽/死循环 → 结果标记（可判断）。
4. **零依赖原则**：Jackson 之外只用 JDK 自带的 `java.net.http`。追踪、记忆等扩展点都用接口而非库。
5. **提示词不进记忆**：系统提示词每次请求临时前置，避免被窗口裁剪逻辑污染。

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| 规范与代码后续发生漂移（本次是人工提取，非从代码生成） | 规范中已写的具体数字（消息条数、token 数、迭代数）都来自现有测试断言，测试通过即规范成立；后续应把新增 Scenario 直接落成测试 |
| 12 份规范可能过度切分，导致小改动需要同时更新多份 | 能力边界按「可独立评审」划定；若后续发现某两份总是同时变更，应在下一次变更中合并 |
| 记录现状而非理想行为，可能让人误以为框架「就该这样」 | 每条 D2 中的取舍都在规范正文里写明了理由与边界，而非仅列条条框框 |
| 删除零字节文件可能与用户本地未提交改动冲突 | 已作为独立、明确的删除项列出，便于单独撤回 |
