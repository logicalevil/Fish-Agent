## ADDED Requirements

### Requirement: ChatModel 是唯一的厂商接入点

`ChatModel` SHALL 声明用于阻塞调用的 `call(ChatRequest)`、用于流式调用的 `stream(ChatRequest, StreamListener)`，以及默认的 `modelName()` 与 `healthy()`。接入新的模型厂商 SHALL 只需实现该接口。

#### Scenario: 自定义模型接入
- **WHEN** 调用方实现 `ChatModel` 并传给 `FishAgent.builder().model(custom)`
- **THEN** Agent 把每次模型调用都路由到该实现，无需改动框架

#### Scenario: 默认模型名
- **WHEN** 实现未覆写 `modelName()`
- **THEN** 返回该实现的简单类名

#### Scenario: 健康检查上报连通性
- **WHEN** 调用 `healthy()` 且底层的 `call` 抛异常
- **THEN** `healthy()` 返回 `false`，不向外传播该失败

#### Scenario: 模型可响应时健康检查通过
- **WHEN** 调用 `healthy()` 且 `call` 正常返回
- **THEN** `healthy()` 返回 `true`

### Requirement: Ollama 适配器面向原生 chat 端点

`OllamaChatModel` SHALL 以 `Content-Type: application/json; charset=utf-8` 向 `<baseUrl>/api/chat` 发送 JSON `POST` 请求，SHALL 默认基础地址为 `http://localhost:11434`，并 SHALL 去除所配置基础地址的尾部斜杠。

#### Scenario: 默认基础地址
- **WHEN** 未指定基础地址构建适配器
- **THEN** `baseUrl()` 为 `http://localhost:11434`

#### Scenario: 尾部斜杠被规范化
- **WHEN** 以 `http://host:11434///` 构建适配器
- **THEN** `baseUrl()` 为 `http://host:11434`

#### Scenario: 模型名必填
- **WHEN** 未指定模型构建适配器
- **THEN** 抛出 `NullPointerException`，消息包含 `model 不能为空`

### Requirement: 请求体遵循 Ollama 线格式

适配器 SHALL 构造包含 `model`、`messages`、`stream` 的请求体，SHALL 仅在请求声明了工具时包含 `tools`，并 SHALL 把采样参数按 Ollama 的键名（`temperature`、`top_p`、`num_predict`、`stop`）归入 `options` 对象，与适配器默认值及调用方提供的额外选项合并。发送前 null 选项值 SHALL 被剔除。

#### Scenario: model 与 stream 标志总是发送
- **WHEN** 为模型 `test-model` 发起同步调用
- **THEN** 请求体的 `model` 为 `test-model`，`stream` 为 `false`

#### Scenario: 流式调用设置流式标志
- **WHEN** 发起流式调用
- **THEN** 请求体的 `stream` 为 `true`

#### Scenario: 请求未声明工具时省略 tools
- **WHEN** 发送一个不含工具的请求
- **THEN** 请求体不含 `tools` 键

#### Scenario: 工具以 function 形式发送
- **WHEN** 请求声明了 `multiply` 工具
- **THEN** 请求体中首个工具条目的 `type` 为 `function`
- **AND** `function.name` 为 `multiply`，`function.parameters` 携带 JSON Schema

#### Scenario: 适配器默认值被应用
- **WHEN** 以 `temperature(0.3)` 构建适配器
- **THEN** 请求体的 `options.temperature` 为 `0.3`

#### Scenario: 请求级参数覆盖适配器默认值
- **WHEN** 适配器默认温度为 `0.3` 而请求指定了另一个值
- **THEN** 发送请求级的值

#### Scenario: 请求级采样参数使用 Ollama 键名
- **WHEN** 请求设置了 `topP`、`maxTokens` 与 `stop` 序列
- **THEN** 请求体分别携带 `options.top_p`、`options.num_predict`、`options.stop`

#### Scenario: 调用方的消息顺序被保留
- **WHEN** 请求先提供一条系统消息再提供一条用户消息
- **THEN** `messages[0].role` 为 `system` 且携带所给正文，`messages[1].role` 为 `user`

### Requirement: 助手的工具调用序列化进线格式的 tool_calls 字段

携带工具调用的助手消息 SHALL 序列化出 `tool_calls` 数组，其条目包含 `function.name` 与对象形式的 `function.arguments`。

#### Scenario: 助手工具调用序列化
- **WHEN** 请求包含一条助手消息，携带 `a=6, b=7` 的 `multiply` 调用
- **THEN** 序列化消息的 `tool_calls[0].function.name` 为 `multiply`
- **AND** `tool_calls[0].function.arguments.a` 为 `6`

#### Scenario: 工具结果消息以 tool 角色序列化
- **WHEN** 请求包含一条名为 `multiply`、正文为 `42` 的 `tool` 消息
- **THEN** 序列化消息的 `role` 为 `tool`，`content` 为 `42`

#### Scenario: tool_name 附加可配置
- **WHEN** 以 `sendToolName(true)` 构建适配器并发送带工具名的工具消息
- **THEN** 序列化消息包含 `tool_name`

#### Scenario: tool_name 可被抑制
- **WHEN** 以 `sendToolName(false)` 构建适配器
- **THEN** 序列化的工具消息省略 `tool_name` 键

### Requirement: 同步响应被解析为 ChatResponse

`call` SHALL 把 `message.content`、`message.tool_calls`、`done_reason`、`prompt_eval_count`、`eval_count` 解析为含 `Message`、`TokenUsage` 与结束原因的 `ChatResponse`。缺失的结束原因 SHALL 默认为 `stop`。

#### Scenario: 正文与工具调用被解析
- **WHEN** 服务端返回一条携带 `a=6, b=7` 的 `multiply` 工具调用的助手消息
- **THEN** 响应消息的首个工具调用名为 `multiply`，且数字参数 `a` 等于 6

#### Scenario: token 用量被解析
- **WHEN** 响应携带 `prompt_eval_count` 31 与 `eval_count` 12
- **THEN** `usage().promptTokens()` 为 31，`usage().completionTokens()` 为 12，`usage().totalTokens()` 为 43

#### Scenario: 结束原因被解析
- **WHEN** 响应携带 `done_reason` 为 `stop`
- **THEN** `finishReason()` 为 `stop`

#### Scenario: 以 JSON 字符串形式给出的参数被容忍
- **WHEN** 服务端把工具调用参数返回为字符串 `"{\"a\":2,\"b\":3}"`
- **THEN** 解析出的参数映射包含 `a=2` 与 `b=3`

#### Scenario: 无法解析的字符串参数回退到 input 键
- **WHEN** 服务端把工具调用参数返回为非 JSON 字符串
- **THEN** 参数映射为 `{"input": <原始字符串>}`

#### Scenario: 缺少名称的工具调用被跳过
- **WHEN** 某个工具调用条目的函数名缺失或为空白
- **THEN** 该条目从解析出的工具调用列表中省略

#### Scenario: 缺失参数变为空映射
- **WHEN** 某个工具调用条目没有参数节点
- **THEN** 其参数映射为空

### Requirement: 流式响应按 NDJSON 逐行解析

`stream` SHALL 逐行消费响应体，忽略空行，对每个非空正文片段触发 `StreamListener.onToken`，累积完整正文，聚合工具调用，并从携带 `done: true` 的终止分块读取用量与结束原因。成功时 SHALL 以聚合后的响应触发 `onComplete`；失败时 SHALL 触发 `onError` 并重新抛出。

#### Scenario: 增量 token 被转发
- **WHEN** 服务端依次流式返回正文片段 `你好` 与 `，世界`
- **THEN** `onToken` 先以 `你好`、再以 `，世界` 被调用

#### Scenario: 聚合后的正文被返回
- **WHEN** 流结束
- **THEN** 返回的响应消息正文为 `你好，世界`

#### Scenario: 空行被跳过
- **WHEN** 响应体在分块之间包含空行
- **THEN** 它们既不产生 token 也不产生解析错误

#### Scenario: 空正文片段不产生回调
- **WHEN** 某个分块携带空字符串正文
- **THEN** 不为它调用 `onToken`

#### Scenario: 工具调用从流中聚合
- **WHEN** 某个流式分块携带参数为 JSON 字符串的 `multiply` 工具调用
- **THEN** 返回的响应消息包含该工具调用及其解析后的参数

#### Scenario: 用量与结束原因取自 done 分块
- **WHEN** 最后一个分块携带 `done: true`、`done_reason` 为 `stop`、`prompt_eval_count` 10、`eval_count` 5
- **THEN** `usage().totalTokens()` 为 15，`finishReason()` 为 `stop`

#### Scenario: 缺失 done_reason 时保持默认
- **WHEN** 终止分块携带 `done: true` 但没有 `done_reason`
- **THEN** `finishReason()` 保持 `stop`

#### Scenario: 容忍 null 监听器
- **WHEN** 调用 `stream(request, null)`
- **THEN** 不抛异常，且仍返回聚合后的响应

#### Scenario: 完成后触发完成回调
- **WHEN** 流成功结束
- **THEN** `onComplete` 以聚合后的响应被调用一次

#### Scenario: 出错时先触发错误回调再重新抛出
- **WHEN** 流因运行时异常失败
- **THEN** `onError` 以该异常被调用
- **AND** 该异常被重新抛给调用方

### Requirement: 服务端上报的错误被转为框架异常

适配器 SHALL 把 `200..299` 之外的任何 HTTP 状态视为失败，并 SHALL 在响应状态成功但正文含 `error` 字段时同样视为失败，两种情况均抛出 `FishAgentException` 并携带服务端消息。

#### Scenario: HTTP 错误状态
- **WHEN** 服务端以状态 500 与正文 `{"error":"model not found"}` 响应
- **THEN** 抛出 `FishAgentException`，消息包含 `500` 与 `model not found`

#### Scenario: 200 响应中的 error 字段
- **WHEN** 服务端以状态 200 与正文 `{"error":"pull model first"}` 响应
- **THEN** 抛出 `FishAgentException`，消息包含 `pull model first`

#### Scenario: 流式分块内的 error 字段
- **WHEN** 某个流式分块携带 `error` 字段
- **THEN** 抛出指明服务端错误的 `FishAgentException`

### Requirement: 连接失败产生可操作的提示信息

当底层 HTTP 尝试以 `IOException` 失败时，适配器 SHALL 抛出 `FishAgentException`。对于连接被拒，消息 SHALL 指明所配置的基础地址并建议启动服务；对于其它失败，消息 SHALL 包含底层原因文本。

#### Scenario: 连接被拒的提示
- **WHEN** 连接被拒绝
- **THEN** 异常消息包含所配置的基础地址与文本 `ollama serve`

#### Scenario: 一般 IO 失败的提示
- **WHEN** 尝试因其它 I/O 原因失败
- **THEN** 异常消息包含原始失败文本

#### Scenario: 中断恢复中断标志
- **WHEN** 调用过程中调用线程被中断
- **THEN** 抛出 `FishAgentException`，且线程的中断状态被保留

### Requirement: 模型列表查询面向本地 Ollama 实例

`OllamaChatModel.listModels()` SHALL 向 `<baseUrl>/api/tags` 发起 `GET` 并返回已安装模型的名称，当 `name` 缺失时 SHALL 回退到 `model` 字段。

#### Scenario: 列出已安装模型
- **WHEN** 服务端在 `models` 下返回两个条目
- **THEN** 返回的列表包含这两个名称

#### Scenario: 名称回退到 model 字段
- **WHEN** 某个条目没有 `name` 但有 `model` 值
- **THEN** 使用该 `model` 值

#### Scenario: 查询失败被转换
- **WHEN** tags 请求失败
- **THEN** 抛出 `FishAgentException` 而非 `IOException`

### Requirement: 适配器接受注入的 HTTP 客户端与超时

`OllamaChatModel.Builder` SHALL 允许提供自定义 `HttpClient` 与 `requestTimeout`。默认超时 SHALL 为同步调用 5 分钟，流式响应 SHALL 不施加逐请求超时，默认客户端 SHALL 使用 HTTP/1.1 与 10 秒连接超时。

#### Scenario: 使用自定义客户端
- **WHEN** 调用方提供 `HttpClient`
- **THEN** 适配器通过该实例发起所有请求

#### Scenario: 默认超时作用于同步调用
- **WHEN** 未配置超时并发起同步调用
- **THEN** 请求携带 5 分钟超时

#### Scenario: 流式调用无请求超时
- **WHEN** 发起流式调用
- **THEN** 请求在不施加逐请求超时的情况下发出，以免长响应被切断
