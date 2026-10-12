## Why

Fish-Agent 已经是一个功能完整的 Java Agent 框架（35 个主源文件、35 个测试、v0.1.0），但全部行为契约只以代码、Javadoc 和一份 README 的形式存在。没有规范层意味着：改动无法被评审为「是否破坏了既有契约」，AI 协作者也无法在不读完整个代码库的情况下理解系统应有的行为。本次变更把**已实现的现有行为**固化为 OpenSpec 基线规范，作为后续所有变更的评审基准。

这不是新功能开发。规范内容全部从现有实现与测试中提取，不改动任何生产代码。

## What Changes

- 新增 11 份能力规范（`openspec/specs/<capability>/spec.md`），覆盖框架的对外契约：
  facade 组装、ReAct 循环、两种工具调用模式、工具注册与 Schema 生成、工具容错、记忆、Ollama 适配、事件回调、流式门控、链路追踪、异常语义。
- 新增 `openspec/project.md`：项目定位、技术栈、模块划分、构建与测试命令、规范编写约定。
- 新增 `AGENTS.md`：AI 协作者进入本仓库的入口说明，指向 OpenSpec 工作流。
- 修正 `README.md` 与代码不一致的两处：`ChatModel` 的方法名（应为其实际的 `call` / `stream` / `modelName`，而非 `chat` / `name`），以及项目结构清单中缺失的追踪类与 `AgentMode`。
- 删除两处死代码：空的 `module-info.java` 与空的 `com/zheng/Main.java`（零字节文件，无编译产物意义，只造成误导）。

明确**不做**的事：不重构 `ReActAgent`（683 行单类）、不引入新依赖、不改动公开 API 签名、不补测试。

## Capabilities

### New Capabilities

- `facade-assembly`：`FishAgent` 统一入口门面的构建契约——模型解析优先级、默认值、快速失败、工具与记忆的注册方式。
- `agent-execution`：一次 `run` 的 ReAct 主循环——终止条件、三种结束原因、迭代与 token 累计、记忆写入时序。
- `tool-calling-modes`：原生 Tool Calling 与文本 ReAct 两种模式的完整差异矩阵（工具下发方式、调用识别、结果回填、流式行为）。
- `tool-registry`：工具的注册、去重、查找、注解扫描与方法反射调用。
- `tool-schema-generation`：Java 类型到 JSON Schema 的映射，以及模型入参到 Java 类型的反向转换。
- `tool-call-resilience`：工具调用失败路径的处理——工具异常兜底、未知工具、死循环检测、通用参数名纠正。
- `conversation-memory`：`ChatMemory` 抽象与三种实现（内存、滑动窗口、JSONL 文件持久化）。
- `ollama-model-adapter`：Ollama `/api/chat` 协议适配——请求体构造、同步与 NDJSON 流式解析、错误映射、模型列表查询。
- `agent-events`：`AgentListener` 事件契约——事件种类、触发时序、`CompositeAgentListener` 的转发语义。
- `streaming-answer-gate`：文本模式下的最终答案门控——如何在流式输出中过滤掉中间推理步骤。
- `observability-tracing`：`Tracer` / `Span` 扩展点——span 层级结构、ID 生成、环境上下文与零开销默认值。
- `error-semantics`：`FishAgentException` 的使用边界——哪些情况抛出、哪些情况转为文本回填。

### Modified Capabilities

无。本次为纯增量：仓库中尚不存在任何规范，因此所有能力均为新建，不涉及既有需求的修改。

## Impact

**代码**：`README.md`（修正两处不一致）、删除 `src/main/java/module-info.java` 与 `src/main/java/com/zheng/Main.java`。生产逻辑零改动。

**新增文件**：`openspec/project.md`、`openspec/specs/**`（11 份）、`AGENTS.md`。

**依赖与构建**：无变化。仍为 Jackson 2.18.2 + JUnit 5.11.4，编译目标 JDK 21。

**风险**：低。规范需忠实于现有实现——若规范与代码冲突，以代码为准并修规范；本次不做「规范驱动重构」。唯一需要留意的判断点是「记录现状」与「记录应有行为」的区分：对于已知的设计取舍（如监听器异常不隔离、系统提示词快照冻结、`ReActAgent` 非线程安全），规范记录现状并显式标注为已知约束，而非写成理想行为，避免规范变成一份无法通过的愿景文档。
