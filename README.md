# Fish-Agent

> 轻量、可扩展的 Java Agent 框架 —— 原生对接本地 Ollama，内置工具调用、多轮记忆、流式输出与 ReAct 双模式。

Fish-Agent 让你用几行代码把大模型变成能**调用工具、记住上下文**的智能体。整个框架除少量 JSON 处理外只依赖 JDK 自带的 `java.net.http`，便于嵌入任意 Java 应用。

---

## 特性

- **轻依赖**：运行时仅需 Jackson（JSON），无 Spring、无其它重量级依赖。
- **双模式工具调用**：原生 Tool Calling（结构化 `tool_calls`）与文本 ReAct（提示词约定 `Thought / Action / Action Input`）可切换。
- **两种工具写法**：注解式 `@AgentTool`（反射生成 JSON Schema + 自动类型转换）或 Lambda 式 `FunctionTool`。
- **记忆系统**：内存记忆、滑动窗口裁剪、JSONL 文件持久化，可自由组合。
- **流式输出**：SSE/NDJSON 逐 token 回调，文本模式下带「最终答案门控」，不会泄露中间推理。
- **健壮性**：迭代轮数上限、重复调用判死循环、工具异常转为错误文本自愈、参数名容错。
- **可观测**：`Tracer` / `Span` 扩展点，在 run、模型调用、工具执行三处自动埋点；默认零开销，可在监听器里直接读到 traceId。
- **JPMS 友好**：`Automatic-Module-Name: com.zheng.fishagent`。

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 21+（推荐 25：可自动获得虚拟线程无 pinning、紧凑对象头、AOT 启动加速等 JVM 层优化） |
| Maven | 3.9+ |
| Ollama（可选） | 本地服务，默认 `http://localhost:11434`，模型需支持 tools（如 `qwen2.5:7b`） |

## 安装

```xml
<dependency>
    <groupId>com.zheng</groupId>
    <artifactId>fish-agent</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle：

```groovy
implementation 'com.zheng:fish-agent:0.1.0'
```

在发布到公共仓库之前，可先安装到本地仓库：

```powershell
mvn -f pom.xml clean install
```

---

## 快速开始

```java
import com.zheng.fishagent.FishAgent;
import com.zheng.fishagent.agent.Agent;
import com.zheng.fishagent.agent.AgentResult;

public class App {
    public static void main(String[] args) {
        Agent agent = FishAgent.builder()
                .ollama("qwen2.5:7b")     // 本地 Ollama 模型，可换成 .model(自定义实现)
                .toolBean(new MyTools())  // 注册注解式工具
                .session("user-42")       // 带滑动窗口的多轮记忆
                .streaming(true)
                .build();

        AgentResult result = agent.run("北京现在几点？");
        System.out.println(result.text());
    }
}
```

`FishAgent` 是推荐入口门面；需要细粒度控制时，可直接使用 `ReActAgent.builder()`、`OllamaChatModel.builder()` 等原生 Builder。

---

## 定义工具

### 注解式（推荐）

在普通方法上标注 `@AgentTool` 即可被自动扫描；参数用 `@ToolParam` 描述，框架会生成 JSON Schema 并完成参数类型转换。

```java
import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;
import java.util.Map;

public class MyTools {

    @AgentTool("查询指定城市的天气")
    public Map<String, Object> weather(
            @ToolParam("城市名称") String city,
            @ToolParam(value = "温度单位：celsius/fahrenheit", required = false) String unit) {
        return Map.of("city", city, "condition", "晴", "temperature", 25);
    }
}
```

- 方法返回值可以是 `String`、基本类型、`Map`、record 等，框架会自动序列化。
- 参数名默认取 `@ToolParam`，也支持编译期保留的形参名。
- 方法抛出的异常会被转为错误文本回填给模型，让模型有机会自我纠正。

### Lambda 式

适合动态或轻量场景：

```java
import com.zheng.fishagent.tool.FunctionTool;
import com.zheng.fishagent.tool.Tool;

Tool tool = FunctionTool.builder()
        .name("get_weather")
        .description("查询指定城市的天气")
        .parameter("city", "string", "城市名", true)
        .function(args -> "晴，25℃")
        .build();
```

注册到 Agent：

```java
Agent agent = FishAgent.builder()
        .ollama("qwen2.5:7b")
        .tool(tool)                       // 单个 Tool
        .toolBean(new MyTools())          // 注解式 Bean
        .tools(toolA, toolB)              // 批量
        .build();
```

---

## 两种运行模式

| 模式 | 说明 | 适用 |
| --- | --- | --- |
| `ToolCallMode.NATIVE_TOOLS`（默认） | 使用模型原生工具调用能力，结构化返回 | 支持 tools 的模型（qwen2.5、llama3.1 等） |
| `ToolCallMode.REACT_TEXT` | 通过提示词约定文本格式，框架解析 `Thought/Action/Action Input` | 不支持原生工具调用的模型 |

```java
import com.zheng.fishagent.agent.ToolCallMode;

Agent agent = FishAgent.builder()
        .ollama("qwen2.5:7b")
        .toolBean(new MyTools())
        .mode(ToolCallMode.REACT_TEXT)
        .build();
```

文本模式下，中间步骤的 `Thought` 不会推送给使用者，`Final Answer` 才会流式输出。

---

## 记忆

```java
import com.zheng.fishagent.memory.*;

// 1) 内存记忆（默认）
ChatMemory memory = new InMemoryChatMemory("session-1");

// 2) 滑动窗口：上下文只保留最近 N 条，SYSTEM 消息始终保留
ChatMemory windowed = MessageWindowChatMemory.of("session-1", 20);

// 3) 文件持久化（JSONL，一行一条消息），跨进程恢复
ChatMemory file = FileChatMemory.of("session-1");                 // 默认 <工作目录>/.fishagent/sessions
ChatMemory file2 = FileChatMemory.of("session-1", Path.of("data")); // 自定义目录
```

也可以在门面上直接指定：

```java
FishAgent.builder().ollama("qwen2.5:7b").session("user-42", 30).build();
```

---

## 流式与事件监听

`AgentListener` 覆盖了你需要关心的所有过程事件；`run` 时传入即可。

```java
import com.zheng.fishagent.agent.AgentListener;

AgentListener listener = new AgentListener() {
    @Override public void onToken(String token) {
        System.out.print(token);                 // 流式增量文本
    }
    @Override public void onToolCall(ToolCall call) {
        System.out.println("\n[调用工具] " + call.name() + " " + call.arguments());
    }
    @Override public void onToolResult(ToolCall call, String result) {
        System.out.println("[工具结果] " + result);
    }
};

AgentResult result = agent.run("帮我查一下东京的天气", listener);
```

### 组合多个监听器

需要同时做多件事时（如「控制台打印 + 审计日志 + 埋点统计」），不必把逻辑全塞进一个实现里——用 `CompositeAgentListener` 把任意多个 `AgentListener` 合成一个，事件会按传入顺序依次转发：

```java
import com.zheng.fishagent.agent.CompositeAgentListener;

// 监听器 1：流式打印到控制台
AgentListener consoleListener = new AgentListener() {
    @Override public void onToken(String token) {
        System.out.print(token);
    }
};

// 监听器 2：记录工具调用的审计日志
AgentListener auditListener = new AgentListener() {
    @Override public void onToolCall(ToolCall call) {
        auditLog.info("调用工具 {}，参数 {}", call.name(), call.arguments());
    }
    @Override public void onToolResult(ToolCall call, String result) {
        auditLog.info("工具 {} 返回 {}", call.name(), result);
    }
};

// 合成一个：两个监听器都会收到全部事件
AgentListener listeners = new CompositeAgentListener(consoleListener, auditListener);

AgentResult result = agent.run("帮我查一下东京的天气", listeners);
```

要点：

- **按序转发**：先注册的先收到事件；传入的 `null` 元素会被忽略。
- **可嵌套**：`CompositeAgentListener` 本身也是 `AgentListener`，可以再被组合，形成树状结构（如按业务分组）。
- **异常不隔离**：任一监听器抛异常会中断本次转发并向上抛出，排在其后的监听器收不到该事件；需要「一个坏监听器不影响其他」时，请在各实现内部自行 `try/catch`。
- **不可变**：构造时对传入集合做防御性拷贝，之后再修改原集合不影响已组合的实例。

要单独使用底层模型流式接口时：

```java
ChatModel model = FishAgent.ollamaModel("qwen2.5:7b");
model.stream(request, token -> System.out.print(token)); // StreamListener 是函数式接口
```

---

## 可观测性（链路追踪）

框架在三个结构边界自动埋点：一次 `run`、一次模型调用、一次工具执行。**默认零开销**——不配置时全部是空实现，不生成 ID、不压栈。

```java
import com.zheng.fishagent.agent.SimpleTracer;
import com.zheng.fishagent.agent.Span;
import com.zheng.fishagent.message.ToolCall;

Agent agent = FishAgent.builder()
        .ollama("qwen2.5:7b")
        .tracer(SimpleTracer.create())      // 每次 run 一个新 traceId
        .build();

AgentListener listener = new AgentListener() {
    @Override public void onToolCall(ToolCall call) {
        // 无需传参，直接读当前 span
        Span span = Span.current();
        System.out.println("[" + span.traceId() + "] 调用工具 " + call.name());
    }
};

agent.run("北京现在几点？", listener);
```

要点：

- **三个 span 边界**：`agent.run` → `model.call` → `tool.execute`，层层嵌套；`tool.execute` 会自动带上 `tool` 属性。
- **ID 层级**：一次逻辑请求内所有 span 共享 `traceId()`；子 span 的 `parentSpanId()` 指向父 span 的 `spanId()`；根 span 的 `parentSpanId()` 为 `null`。
- **`Span.current()`**：框架内部不创建线程，`run` 与回调始终在同一线程上，因此回调里读到的必然是本次运行的 span。若你在回调中自行切换线程，需要把 span 作为值显式带过去。
- **traceId 粒度**：`SimpleTracer.create()` 每次 run 新生成；`SimpleTracer.create("trace-abc")` 用固定值，让跨多次 run 的同一逻辑请求归入同一条链路（空白值会退化为随机生成）。
- **自定义实现**：实现 `Tracer` 接口并注入即可接入日志或 OpenTelemetry。`Tracer` 只管按 `parent` 算 ID 并返回 span，**不要**自己维护「当前 span」——压栈出栈由框架统一管理。
- **栈的清理**：栈空时线程本地变量会被移除，线程池复用时不会残留上一次的上下文。
- **默认关闭**：不调用 `.tracer(...)` 时 `Tracer.NOOP` 生效，`Span.current()` 恒返回 `Span.NOOP`（`traceId()` / `spanId()` 为空串）。

> 行为契约见 `openspec/specs/observability-tracing/spec.md`。

---

## 结果与用量

`AgentResult` 是一个 record，包含：

| 字段 | 含义 |
| --- | --- |
| `text()` | 最终回答 |
| `messages()` | 截至本次运行结束的完整会话消息 |
| `iterations()` | 实际发生的模型调用轮数 |
| `usage()` | token 用量（`promptTokens` / `completionTokens` / `totalTokens`） |
| `stopReason()` | 结束原因：`stop` / `max_iterations` / `loop_detected` |
| `success()` | 是否正常得到最终回答 |

```java
AgentResult result = agent.run("算一下 (128*37+96)/4");
System.out.println(result.text());
System.out.println("轮数=" + result.iterations() + "，tokens=" + result.usage().totalTokens());
```

---

## 自定义模型

接入其它厂商只需实现 `ChatModel`：

```java
import com.zheng.fishagent.llm.*;

public class MyChatModel implements ChatModel {

    /** 同步（阻塞）调用。 */
    @Override
    public ChatResponse call(ChatRequest request) {
        // 把 request.messages() / request.tools() 转成厂商格式，发请求，再转回 ChatResponse
        return new ChatResponse(Message.assistant("回答"), TokenUsage.EMPTY, "stop");
    }

    /** 流式调用：逐段回调 onToken，返回聚合后的完整响应（含工具调用）。 */
    @Override
    public ChatResponse stream(ChatRequest request, StreamListener listener) {
        ChatResponse response = call(request);
        listener.onToken(response.text());
        return response;
    }

    /** 模型标识，用于日志与元信息展示；不覆写则默认取类名。 */
    @Override
    public String modelName() {
        return "my-model";
    }
}

Agent agent = FishAgent.builder().model(new MyChatModel()).build();
```

`ChatRequest` 是不可变对象，用 `ChatRequest.builder()` 构建；其中 `messages()` 已按「系统提示词 → 历史消息」排好序，`tools()` 在原生模式下携带结构化 Schema、在文本模式下为空。`ChatResponse` 是 record，字段为 `message` / `usage` / `finishReason`。

---

## 运行示例

仓库自带一个可直接运行的控制台示例（含时间、计算、天气示例工具）：

```powershell
mvn -f pom.xml test          # 运行单元测试
mvn -f pom.xml exec:java      # 启动控制台聊天
```

通过参数切换模型与模式：

```powershell
mvn -f pom.xml exec:java "-Dexec.args=--mode=react --model=qwen2.5:7b"
```

---

## 项目结构

```
src/main/java/com/zheng/fishagent/
├── FishAgent.java                 统一入口门面
├── core/       FishAgentException
├── message/    Role / Message / ToolCall
├── llm/        ChatModel / ChatRequest / ChatResponse / StreamListener / TokenUsage
│   └── ollama/ OllamaChatModel
├── tool/       Tool / ToolSpec / FunctionTool / MethodTool / ToolRegistry / ToolSchemaGenerator
│   └── annotation/ @AgentTool / @ToolParam
├── memory/     ChatMemory / InMemory / MessageWindow / FileChatMemory
├── prompt/     ReActPrompt / ReActOutputParser / ReActStep
├── agent/      Agent / ReActAgent / ToolCallMode / AgentResult
│               AgentListener / CompositeAgentListener
│               Tracer / Span / Spans / SimpleTracer
└── example/    ConsoleChat / DemoTools / Calculator
```

## 规范与变更流程

本项目的**行为契约**记录在 `openspec/` 下，而非仅存在于代码里：

```
openspec/
├── project.md      项目定位、技术栈、约定
├── specs/          已实现能力的正式规范（12 份，基线）
└── changes/        进行中的变更提案
    └── archive/    已归档的变更
```

规范用 Requirement + Scenario 描述「系统应该做什么」，每份对应一个能力（如 `agent-execution`、`tool-registry`、`conversation-memory`）。改动行为前先读对应规范，改完行为要同步更新规范。

```powershell
openspec list --specs                    # 查看现有能力规范
openspec show agent-execution --type spec # 查看某份规范
openspec new change <name>               # 新建变更提案
openspec validate --all                  # 校验提案与规范
openspec archive <name>                  # 完成后归档，规范并入 openspec/specs/
```

需要 OpenSpec CLI：`npm install -g @fission-ai/openspec`。

## 构建与发布

```powershell
mvn -f pom.xml clean package                 # 产出 jar + sourcess jar + javadoc jar
mvn -f pom.xml clean install                 # 安装到本地仓库

# 发布到 Maven Central（需在 settings.xml 配置 central 账号与 GPG）
mvn -f pom.xml -Prelease clean deploy
```

## License

本项目基于 [MIT License](LICENSE) 发布。
