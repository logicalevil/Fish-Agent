## ADDED Requirements

### Requirement: 提供两种工具调用模式且可在构建时选择

框架 SHALL 提供 `ToolCallMode.NATIVE_TOOLS` 与 `ToolCallMode.REACT_TEXT`。模式 SHALL 可通过 `FishAgent.Builder.mode(ToolCallMode)` 与 `ReActAgent.Builder.mode(ToolCallMode)` 选择，且 SHALL 默认为 `NATIVE_TOOLS`。null 模式 SHALL 被拒绝。

#### Scenario: 默认模式为原生工具调用
- **WHEN** 构建 Agent 时未调用 `mode(...)`
- **THEN** `ReActAgent.mode()` 返回 `NATIVE_TOOLS`

#### Scenario: null 模式被拒绝
- **WHEN** 调用 `.mode(null)`
- **THEN** 抛出 `NullPointerException`，消息包含 `mode 不能为空`

### Requirement: 原生模式以结构化请求字段下发工具

在 `NATIVE_TOOLS` 模式下，框架 SHALL 以结构化 JSON Schema 条目的形式把工具规格放在请求的 `tools` 字段中，且系统提示词 SHALL 只描述行为准则。

#### Scenario: 首次请求携带工具 Schema
- **WHEN** 原生模式下、注册了一个 `multiply` 工具的 Agent 执行一次 run
- **THEN** 首次模型请求的 `tools()` 列表长度为 1
- **AND** 该条目的 `name()` 为 `multiply`

#### Scenario: 原生模式提示词说明工具可用
- **WHEN** 检查一个含工具的原生模式 Agent 的系统提示词
- **THEN** 它指示模型直接使用其工具调用能力

#### Scenario: 原生模式提示词处理空工具集
- **WHEN** 无工具的原生模式 Agent 构建其系统提示词
- **THEN** 提示词声明当前没有可用工具，并指示模型基于已有知识回答

### Requirement: 文本模式通过系统提示词下发工具

在 `REACT_TEXT` 模式下，框架 SHALL 发送空的 `tools` 列表，并 SHALL 改为把工具清单与要求的输出协议渲染进系统提示词。

#### Scenario: 不发送结构化工具
- **WHEN** 文本模式下的 Agent 执行一次 run
- **THEN** 每次模型请求的 `tools()` 列表均为空

#### Scenario: 提示词记录 ReAct 协议
- **WHEN** 检查文本模式 Agent 的系统提示词
- **THEN** 它包含 `Thought:` / `Action:` / `Action Input:` / `Final Answer:` 标签
- **AND** 它指示模型每次只输出一个步骤，且不要编造 `Observation`

#### Scenario: 提示词渲染工具清单
- **WHEN** 含 `multiply` 工具的文本模式 Agent 构建其系统提示词
- **THEN** 提示词列出该工具的名称、描述与参数 JSON Schema

#### Scenario: 空工具清单被显式渲染
- **WHEN** 无工具的文本模式 Agent 渲染其工具区段
- **THEN** 渲染出的工具描述为 `（无）`

### Requirement: 工具调用的识别方式随模式而异

在 `NATIVE_TOOLS` 模式下，工具调用 SHALL 从助手消息的结构化 `toolCalls()` 集合中读取。在 `REACT_TEXT` 模式下，工具调用 SHALL 由解析助手消息的文本正文产生。

#### Scenario: 原生模式读取结构化调用
- **WHEN** 模型返回一条携带 `multiply` 工具调用的助手消息
- **THEN** 框架调用 `multiply`，且不检查消息正文

#### Scenario: 文本模式从文本中解析工具请求
- **WHEN** 模型返回 `Thought: 需要计算` / `Action: multiply` / `Action Input: {"a": 3, "b": 5}`
- **THEN** 框架以参数 `a=3, b=5` 调用 `multiply`

#### Scenario: 文本模式把最终答案视为循环终止
- **WHEN** 模型的响应被解析为 `FinalAnswer`
- **THEN** 循环终止，且 `text()` 为剥去 `Thought` 包装后的答案正文

#### Scenario: 无法解析的文本模式输出退化为最终答案
- **WHEN** 模型返回既不匹配最终答案模式、也不匹配工具请求模式的文本
- **THEN** 循环终止，且 `text()` 为该原始文本

### Requirement: 工具结果按模式以不同形态回填

在 `NATIVE_TOOLS` 模式下，工具结果 SHALL 以角色为 `tool`、携带工具名的消息追加到记忆。在 `REACT_TEXT` 模式下，结果 SHALL 被包装为形如 `Observation: <结果>` 的 `user` 消息。

#### Scenario: 原生模式使用 tool 角色
- **WHEN** 原生模式的一次 run 回填工具结果
- **THEN** 追加的消息角色为 `tool`，且 `toolName()` 等于该工具的名称

#### Scenario: 文本模式使用 user 角色包装
- **WHEN** 文本模式的一次 run 回填结果 `15`
- **THEN** 追加的消息角色为 `user`，正文以 `Observation: 15` 开头

### Requirement: 文本模式仅在确实请求工具时上报思考内容

在 `REACT_TEXT` 模式下，框架 SHALL 仅对产生工具调用的轮次触发 `AgentListener.onThought`，且 SHALL NOT 在最终答案那一轮触发。

#### Scenario: 工具调用步骤上报思考
- **WHEN** 文本模式的某一轮产生带思考文本 `需要计算` 的工具请求
- **THEN** `onThought("需要计算")` 被调用

#### Scenario: 终止轮不上报思考
- **WHEN** 最后一轮产生最终答案
- **THEN** 该轮不触发 `onThought`
