## ADDED Requirements

### Requirement: 门面构建器把模型、工具与记忆组装成 Agent

`FishAgent.builder()` SHALL 返回一个构建器，把模型、工具注册表、记忆实现与行为参数组装成可直接运行的 `Agent`。门面 SHALL 是推荐入口，同时底层原生构建器（`ReActAgent.builder()`、`OllamaChatModel.builder()`）SHALL 保持可直接使用，以满足更细粒度的控制需求。

#### Scenario: 用自定义模型与注解工具组装 Agent
- **WHEN** 调用 `FishAgent.builder().model(model).toolBean(new MathTools()).session("facade-test").streaming(false).build()`
- **THEN** `build()` 返回一个 `Agent`，其 `memory().id()` 为 `"facade-test"`
- **AND** 随后的 `run("1+2 等于几")` 使用传入的模型以及从注解 Bean 注册的工具

#### Scenario: 组装一个由本地 Ollama 模型支撑的 Agent
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").build()`
- **THEN** 生成的 Agent 以模型名 `qwen2.5:7b` 向 `http://localhost:11434` 发送请求

#### Scenario: 组装一个指向自定义地址 Ollama 的 Agent
- **WHEN** 调用 `FishAgent.builder().ollama("http://my-host:11434", "qwen2.5:7b").build()`
- **THEN** 生成的 Agent 向 `http://my-host:11434` 发送请求

### Requirement: 未指定模型时构建器快速失败

当既未调用 `model(ChatModel)` 也未调用 `ollama(String)` 时，`FishAgent.Builder.build()` SHALL 抛出 `FishAgentException`，且消息包含 `未指定模型`。SHALL NOT 返回任何半成品 `Agent`。

#### Scenario: 未指定任何模型就构建
- **WHEN** 调用 `FishAgent.builder().build()` 且未调用 `model(...)` 或 `ollama(...)`
- **THEN** 抛出 `FishAgentException`
- **AND** 异常消息包含 `未指定模型`

#### Scenario: 模型名为 null 在配置阶段即被拒绝
- **WHEN** 调用 `FishAgent.builder().ollama((String) null)`
- **THEN** 抛出 `NullPointerException`，消息包含 `模型名不能为空`

### Requirement: 自定义模型优先于 Ollama 快捷方式

当 `model(ChatModel)` 与 `ollama(String)` 同时被配置时，`build()` SHALL 使用显式传入的 `ChatModel`，且 SHALL NOT 构造 Ollama 适配器。

#### Scenario: 两种模型来源同时被配置
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").model(myCustomModel).build()`
- **THEN** 生成的 Agent 把所有模型调用路由到 `myCustomModel`

### Requirement: 门面暴露框架的默认值

`FishAgent.Builder` SHALL 默认使用原生工具调用、启用流式输出、最多 8 轮模型调用、同一工具调用最多重复 3 次，并以 `http://localhost:11434` 作为 Ollama 基础地址。

#### Scenario: 最小配置下的默认值
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").build()` 且不做其它配置
- **THEN** Agent 运行在原生工具调用模式且已启用流式输出
- **AND** 单次 `run` 最多执行 8 轮模型调用

#### Scenario: 默认会话记忆保留 20 条窗口
- **WHEN** 调用 `FishAgent.builder().ollama("qwen2.5:7b").session("user-42").build()`
- **THEN** 生成的记忆最多返回最近 20 条非 SYSTEM 消息，外加全部 SYSTEM 消息

### Requirement: 门面提供工具注册快捷方法

`FishAgent.Builder` SHALL 提供 `tool(Tool)`、`tools(Tool...)`、`toolBean(Object)`、`tools(ToolRegistry)` 用于注册工具；`FishAgent.toolRegistry()` SHALL 返回一个新的空注册表，供偏好先增量注册再组装的调用方使用。

#### Scenario: 通过预先构建的注册表注册工具
- **WHEN** 调用方用 `FishAgent.toolRegistry()` 得到 `ToolRegistry`、在其上注册工具、再传给 `.tools(registry)`
- **THEN** 组装出的 Agent 暴露上述全部工具

#### Scenario: 容忍 null 注册表
- **WHEN** 调用 `FishAgent.builder().tools((ToolRegistry) null)`
- **THEN** 不抛异常，且该次调用不贡献任何工具

### Requirement: 门面快捷方法可独立构造模型

`FishAgent.ollamaModel(String)` 与 `FishAgent.ollamaModel(String baseUrl, String modelName)` SHALL 返回由 Ollama 适配器支撑的 `ChatModel`，可脱离 Agent 组装独立使用。

#### Scenario: 创建独立的 Ollama 模型
- **WHEN** 调用 `FishAgent.ollamaModel("qwen2.5:7b")`
- **THEN** 返回的 `ChatModel` 其 `modelName()` 为 `qwen2.5:7b`

### Requirement: 行为准则与系统提示词遵循明确的优先级

`instructions(String)` SHALL 把行为准则追加到框架内置的 ReAct 格式说明之后。`systemPrompt(String)` SHALL 完全接管系统提示词，且优先级高于 `instructions(String)`。

#### Scenario: 行为准则与内置格式说明拼接
- **WHEN** 调用 `.instructions("涉及时间的问题必须调用工具")`
- **THEN** 生成的系统提示词同时包含传入的准则与框架的模式相关格式说明

#### Scenario: 系统提示词覆盖内置说明
- **WHEN** 调用 `.systemPrompt("自定义提示词")`
- **THEN** `Agent.systemPrompt()` 精确返回 `自定义提示词`
