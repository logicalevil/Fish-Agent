# Fish-Agent 项目上下文

## 定位

Fish-Agent 是一个**轻量、可扩展的 Java Agent 框架 / SDK**，原生对接本地 Ollama 大模型。它以 jar 形式发布，供其它 Java 应用嵌入使用；仓库内自带一个可运行的控制台示例（`ConsoleChat`）。

- 坐标：`com.zheng:fish-agent:0.1.0`
- 许可证：MIT
- 模块名：`Automatic-Module-Name: com.zheng.fishagent`

**不是** Web 应用、不是独立服务、不是 CLI 工具。没有 Spring，没有容器，没有配置文件加载机制——一切通过 Builder 以编程方式组装。

## 核心设计原则

1. **极简依赖**：运行时只依赖 Jackson（JSON 编解码），其余全部使用 JDK 自带的 `java.net.http`。任一新增依赖都需要充分的理由。
2. **唯一入口 + 原生 Builder 并存**：`FishAgent.builder()` 是推荐入口；需要细粒度控制时直接用 `ReActAgent.builder()`、`OllamaChatModel.builder()`。
3. **扩展点用接口，不用库**：`ChatModel`（模型）、`ChatMemory`（记忆）、`Tool`（工具）、`Tracer`（追踪）、`AgentListener`（事件）都是接口 SPI，默认实现零开销。
4. **失败分层**：
   - 工具执行失败 → 转成错误文本回填给模型（可自愈）
   - 模型 / IO 失败 → 抛 `FishAgentException`（可重试）
   - 预算耗尽 / 死循环 → 通过 `AgentResult.stopReason()` 标记（可判断）
5. **状态显式**：`ReActAgent` 有状态（绑定会话记忆），非线程安全，框架内部不创建线程、不做同步。

## 技术栈

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| Java 编译目标 | 21（`maven.compiler.release`） | 使用者运行时最低门槛 |
| 推荐运行时 | JDK 25 | 可获得虚拟线程去 pinning、紧凑对象头、AOT 启动加速 |
| Maven | 3.9+ | |
| Jackson | 2.18.2 | 唯一运行时依赖 |
| JUnit Jupiter | 5.11.4 | 测试 |
| 本地模型 | Ollama，默认 `http://localhost:11434` | 模型需支持 tools，如 `qwen2.5:7b` |

编译期开启 `-parameters`，用于保留方法形参名（`@ToolParam` 缺省时依赖它生成 Schema）。

## 模块划分

```
com.zheng.fishagent
├── FishAgent            统一入口门面（静态工厂 + 嵌套 Builder）
├── core/                FishAgentException
├── message/             Role / Message / ToolCall（不可变值对象）
├── llm/                 ChatModel / ChatRequest / ChatResponse / StreamListener / TokenUsage
│   └── ollama/          OllamaChatModel（唯一的 HTTP 实现）
├── tool/                Tool / ToolSpec / FunctionTool / MethodTool / ToolRegistry / ToolSchemaGenerator
│   └── annotation/      @AgentTool / @ToolParam
├── memory/              ChatMemory / InMemoryChatMemory / MessageWindowChatMemory / FileChatMemory
├── prompt/              ReActPrompt / ReActOutputParser / ReActStep
├── agent/               Agent / ReActAgent / ToolCallMode / AgentResult
│                        AgentListener / CompositeAgentListener
│                        Tracer / Span / Spans / SimpleTracer
├── util/                Json（共享 ObjectMapper）
└── example/             ConsoleChat / DemoTools / Calculator（演示，非框架核心）
```

## 构建与测试

```powershell
mvn -f pom.xml clean test                    # 运行 35 个单元测试
mvn -f pom.xml clean package                 # 产出 jar + sources jar + javadoc jar
mvn -f pom.xml clean install                 # 安装到本地仓库
mvn -f pom.xml exec:java                     # 启动控制台示例（需本地 Ollama）
mvn -f pom.xml exec:java "-Dexec.args=--mode=react --model=qwen2.5:7b"
mvn -f pom.xml -Prelease clean deploy        # 发布到 Maven Central（需 GPG）
```

测试全部离线可跑：`FakeChatModel` 按脚本返回响应，`OllamaChatModelTest` 用 JDK 内置的 `com.sun.net.httpserver` 起 HTTP 桩。**没有测试依赖真实模型或网络**，这一点必须保持。

## 规范约定

- 规范位于 `openspec/specs/<capability>/spec.md`，用 `### Requirement:` + `#### Scenario:` 结构。
- Scenario 必须是**可验证的行为**，不是愿望。规范里的具体数字（消息条数、token 数、迭代数）都来自实际断言。
- 规范记录**现状**；对已知取舍要写明理由与边界，不要粉饰成理想行为。
- 语言：正文用中文，`SHALL` / `MUST` / `WHEN` / `THEN` 保留英文（对齐 RFC 2119 的强度语义，也便于检索）。
- 现有 12 份规范的能力清单：

| 能力 | 覆盖范围 |
| --- | --- |
| `facade-assembly` | 门面构建、模型解析优先级、默认值、快速失败 |
| `agent-execution` | ReAct 主循环、终止条件、三种结束原因、记忆写入时序 |
| `tool-calling-modes` | 原生 Tool Calling 与文本 ReAct 的差异矩阵 |
| `tool-registry` | 工具注册、去重、注解扫描、反射调用、Lambda 工具 |
| `tool-schema-generation` | Java 类型 ↔ JSON Schema 双向映射与参数转换 |
| `tool-call-resilience` | 工具异常兜底、未知工具、死循环检测、参数名纠正 |
| `conversation-memory` | 记忆抽象与三种实现（内存 / 窗口 / JSONL 文件） |
| `ollama-model-adapter` | Ollama 协议适配、同步与流式解析、错误映射 |
| `agent-events` | 事件契约、触发时序、组合监听器语义 |
| `streaming-answer-gate` | 文本模式的流式最终答案门控 |
| `observability-tracing` | Tracer / Span 扩展点、ID 层级、零开销默认 |
| `error-semantics` | 何时抛异常、何时转文本回填、何时用结果标记 |

## 关键不变量

改动代码时不应破坏这些结构：

1. **唯一终止条件**：某一轮模型没有请求任何工具 → 该轮内容即最终答案。三个出口（正常 / 轮数耗尽 / 死循环）共用同一个收尾路径。
2. **模式差异收敛在三处**：`resolveToolCalls`、`observation`、`invoke`。循环主体两种模式共用，不要把模式判断散落到别处。
3. **系统提示词不进记忆**：每次请求临时前置，避免被滑动窗口当作普通消息裁剪。
4. **系统提示词快照冻结**：首次使用后缓存不再失效，因此所有工具须在首次 `run` 之前注册完毕。
5. **工具失败不中断循环**：`ToolRegistry.execute` 必须兜住所有异常并返回错误文本。

## 已知取舍（不要误判为 bug）

| 取舍 | 现状 | 理由 |
| --- | --- | --- |
| 非线程安全 | `ReActAgent` 绑定单个 `ChatMemory`，无内部同步 | 并发安全留给调用方：每会话一个实例，或自行加锁 |
| 提示词快照冻结 | 运行期新增工具对模型不可见 | 避免每次 run 重算提示词；正常用法下工具集构建期固定 |
| 监听器异常不隔离 | `CompositeAgentListener` 中一个抛异常会中断后续转发 | 与普通 Java 调用语义一致，问题立刻暴露；需隔离者自行 try/catch |
| 无结构化错误码 | `FishAgentException.wrap()` 只保留消息与 cause | 当前能力边界，补错误码体系属新功能 |
