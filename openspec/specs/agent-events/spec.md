# agent-events Specification

## Purpose

定义 Agent 执行过程中的事件回调契约：有哪些事件、按什么顺序触发、多个监听器如何组合。用于把执行过程对接到控制台输出、SSE 推送、审计日志与埋点统计。

## Requirements

### Requirement: AgentListener 以可选回调暴露完整运行生命周期

`AgentListener` SHALL 声明 `onStart(String)`、`onThought(String)`、`onToken(String)`、`onMessage(Message)`、`onToolCall(ToolCall)`、`onToolResult(ToolCall, String)`、`onFinish(AgentResult)`、`onError(Throwable)`，每个都有空默认实现，以便调用方只覆写所需的部分。SHALL 提供无状态的空实现 `AgentListener.NOOP`。

#### Scenario: 部分实现即可编译
- **WHEN** 调用方只实现 `onToken`
- **THEN** 该类无需实现其余回调即可编译

#### Scenario: 提供空实现监听器
- **WHEN** 把 `AgentListener.NOOP` 传给某次 run
- **THEN** 任何回调都不产生效果，run 正常推进

#### Scenario: null 监听器被替换为空实现
- **WHEN** 调用 `run(input, null)`
- **THEN** 不发生 `NullPointerException`，run 正常完成

### Requirement: 一次 run 的事件顺序遵循明确约定

对于先执行工具调用再作答的 run，框架 SHALL 按以下顺序触发事件：`onStart`；随后对每个工具调用轮次触发 `onMessage`（助手消息），再对每次调用触发 `onToolCall` 与 `onToolResult`；随后对最终助手消息触发 `onMessage`；最后触发 `onFinish`。

#### Scenario: 最先触发开始事件
- **WHEN** 任意 run 开始
- **THEN** 在任何其它回调之前，以用户输入触发 `onStart`

#### Scenario: 助手消息先于其工具事件
- **WHEN** 某一轮产生工具调用
- **THEN** 携带该助手消息的 `onMessage` 先于 `onToolCall` 被调用

#### Scenario: 工具调用先于其结果
- **WHEN** 某个工具被执行
- **THEN** 以规范化后的调用触发 `onToolCall`，先于该调用的 `onToolResult`

#### Scenario: 成功路径上最后触发结束事件
- **WHEN** run 以最终答案完成
- **THEN** 以返回的 `AgentResult` 触发一次 `onFinish`，之后不再有任何回调

#### Scenario: 每条终止路径都触发结束事件
- **WHEN** run 因迭代耗尽或死循环检测而终止
- **THEN** 仍然触发 `onFinish`，并携带相应的 `stopReason`

### Requirement: 工具事件携带规范化后的调用

`onToolCall` SHALL 收到经过通用参数名规范化之后的工具调用，使监听器观察到的是工具实际声明的参数名。

#### Scenario: 监听器看到重映射后的参数
- **WHEN** 模型对声明了 `city` 的单参数工具提供了 `{"input": "北京"}`
- **THEN** `onToolCall` 收到的调用其参数为 `{"city": "北京"}`

### Requirement: 错误先经 onError 上报再向外传播

当运行时异常逃出 run 时，框架 SHALL 以该异常触发 `onError`，随后 SHALL 重新抛出它，使监听器能够记录失败，同时调用方仍保留重试或对外呈现的能力。

#### Scenario: 先触发错误回调再重新抛出
- **WHEN** 模型在一次 run 中抛出运行时异常
- **THEN** `onError` 以该异常被调用
- **AND** 同一异常从 `run` 向外传播

#### Scenario: 以结果表达的失败不触发 onError
- **WHEN** run 以 `max_iterations` 或 `loop_detected` 结束
- **THEN** 不触发 `onError`，因为这些结果通过返回值表达

### Requirement: CompositeAgentListener 把事件扇出给多个监听器

`CompositeAgentListener` SHALL 按注册顺序把每个事件转发给全部被组合的监听器，SHALL 同时接受 `List<AgentListener>` 与变参两种构造形式，SHALL 忽略 null 元素，且 SHALL 在构造后不可变。

#### Scenario: 事件按顺序到达每个监听器
- **WHEN** 组合两个监听器且某次 run 触发 `onToken("a")`
- **THEN** 两个监听器都收到 `onToken("a")`，先注册的先收到

#### Scenario: null 元素被忽略
- **WHEN** 用含 null 的列表构造组合监听器
- **THEN** 不发生异常，且只有非 null 的监听器收到事件

#### Scenario: 空组合等价于空实现
- **WHEN** 用零个监听器构造组合监听器
- **THEN** 每个回调都无副作用地返回

#### Scenario: 构造是防御性的
- **WHEN** 组合监听器创建之后修改来源列表
- **THEN** 组合监听器的转发行为不变

#### Scenario: 组合可以嵌套
- **WHEN** 把一个组合监听器作为元素传给另一个组合监听器
- **THEN** 事件穿过两层传播到叶子监听器

#### Scenario: 抛异常的监听器中断后续转发
- **WHEN** 某个被组合的监听器在回调中抛异常
- **THEN** 该异常立即传播给调用方
- **AND** 注册在其后的监听器不会收到该事件

### Requirement: 追踪上下文可在监听器内无参获取

`AgentListener` 的实现 SHALL 能够通过 `Span.current()` 读取当前 span，而无需在回调签名中传递任何参数。

#### Scenario: 监听器读取环境中的 span
- **WHEN** 监听器在 `onToolCall` 内调用 `Span.current()`
- **THEN** 它观察到外层操作的 span，且其 `traceId()` 与所在 run 的 trace 一致

#### Scenario: 任何 run 之外环境 span 为空实现
- **WHEN** 在任何框架操作之外调用 `Span.current()`
- **THEN** 返回 `Span.NOOP`
