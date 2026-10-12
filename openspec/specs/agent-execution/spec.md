# agent-execution Specification

## Purpose

定义一次 run 的完整执行契约：ReAct 主循环如何推进、什么条件下终止、三种结束原因各自意味着什么、消息如何写入记忆、多轮 token 用量如何累计。

## Requirements

### Requirement: 一次 run 执行 ReAct 循环直到模型不再请求工具

`Agent.run(String)` SHALL 执行一个循环：每轮恰好发起一次模型调用，执行模型请求的工具，把结果回填，然后继续下一轮。只要某次模型响应不包含工具调用，循环 SHALL 立即终止；该响应的正文 SHALL 作为最终答案。

#### Scenario: 模型先调用工具再作答
- **WHEN** 模型先返回一个 `multiply` 工具调用，在观测结果被回填后又返回文本 `结果是 42。`
- **THEN** `run("6 乘 7 等于几？")` 的 `text()` 为 `结果是 42。`
- **AND** `iterations()` 为 2
- **AND** `success()` 为 `true`
- **AND** `stopReason()` 为 `"stop"`

#### Scenario: 模型不使用任何工具直接作答
- **WHEN** 模型的首次响应不包含任何工具调用
- **THEN** 本次 run 恰好在一轮之后结束
- **AND** `iterations()` 为 1

### Requirement: 记忆中的消息顺序保证「调用在前、结果在后」

在同一轮内，框架 SHALL 先把助手消息（含其工具调用）写入记忆，再写入任何工具结果消息，从而保证后续模型的上下文中永远不会出现「工具结果排在其调用之前」的非法时序。

#### Scenario: 原生模式下完成一轮工具调用的记忆布局
- **WHEN** 原生模式下一次 run 完成且包含一次工具调用
- **THEN** 记忆中按顺序包含 4 条消息：`user`、`assistant`（携带工具调用）、`tool`（携带结果）、`assistant`（携带最终答案）

#### Scenario: 第二次模型请求包含工具观测结果
- **WHEN** 检查原生模式下一次 run 的第二次模型请求
- **THEN** 其最后一条消息的角色为 `tool`，`toolName()` 等于被调用的工具名，正文等于该工具的返回值

### Requirement: run 在首次模型调用之前写入用户输入

非空的用户输入 SHALL 在构造首次模型请求之前追加到会话记忆中。null 或空白输入 SHALL NOT 写入记忆，从而允许本次 run 基于既有上下文继续。

#### Scenario: 非空输入被记录
- **WHEN** 调用 `run("帮我查一下东京的天气")`
- **THEN** 构造请求时从记忆中读到的第一条消息是正文与之一致的 `user` 消息

#### Scenario: 空白输入不触碰记忆
- **WHEN** 调用 `run("   ")`
- **THEN** 该次调用不会向记忆追加任何 `user` 消息

### Requirement: 迭代预算耗尽时终止且不抛异常

当循环达到 `maxIterations` 而仍未出现不含工具调用的响应时，run SHALL 终止，`stopReason()` SHALL 为 `max_iterations`，`success()` SHALL 为 `false`，且 SHALL NOT 抛异常。返回的文本 SHALL 是一个尽力而为的兜底值，取自最近一条有内容的助手消息。

#### Scenario: 迭代预算耗尽
- **WHEN** 模型持续请求工具且 `maxIterations` 为 3
- **THEN** `run` 正常返回，`stopReason()` 为 `"max_iterations"`
- **AND** `success()` 为 `false`
- **AND** `iterations()` 为 3

#### Scenario: 无可复用内容时兜底文本为空
- **WHEN** 迭代预算耗尽且记忆中不存在任何非空白的助手消息
- **THEN** `text()` 为空字符串

### Requirement: 迭代与重复调用预算的下限为 1

`maxIterations` 与 `maxRepeatedToolCalls` SHALL 各自被约束为至少 1，以保证至少尝试一次模型调用，且死循环检测不会在工具的首次调用上触发。

#### Scenario: 迭代预算为 0 时被抬升为 1
- **WHEN** 以 `maxIterations(0)` 构建 Agent
- **THEN** `maxIterations()` 返回 1

### Requirement: token 用量跨轮次累计

返回的 `AgentResult.usage()` SHALL 等于本次 run 期间每一次模型调用所报告用量的总和。

#### Scenario: 两轮的用量被累加
- **WHEN** 首次模型调用报告 100 个输入 token 与 10 个输出 token，第二次报告 1 与 1
- **THEN** `usage().totalTokens()` 为 112

### Requirement: AgentResult 是不可变快照

`AgentResult` SHALL 是一个 record，暴露 `text`、`messages`、`iterations`、`usage`、`stopReason`、`success`。其 `messages` SHALL 是构造时取得的不可变防御性副本，因此后续对底层记忆的修改 SHALL NOT 影响已返回的结果。

#### Scenario: 结果消息不受后续记忆变更影响
- **WHEN** 取得某个结果后，再次 `run` 向同一记忆追加了消息
- **THEN** 先前结果中的 `messages()` 仍只反映其自身结束时的状态

#### Scenario: 空字段被规范化
- **WHEN** 以 null 的 `messages` 或 null 的 `usage` 构造 `AgentResult`
- **THEN** `messages()` 为空列表，`usage()` 为 `TokenUsage.EMPTY`

### Requirement: 结束原因常量稳定且可机器识别

`AgentResult.STOP`、`AgentResult.MAX_ITERATIONS`、`AgentResult.LOOP_DETECTED` SHALL 分别取值 `stop`、`max_iterations`、`loop_detected`。正常完成时 SHALL 透传模型自身的 `finishReason`（当其非空白），缺失时回退为 `stop`。

#### Scenario: 透传模型给出的结束原因
- **WHEN** 最终模型响应携带的 `finishReason` 为 `length`
- **THEN** `stopReason()` 为 `length`

#### Scenario: 空白结束原因退化为 stop
- **WHEN** 最终模型响应携带的 `finishReason` 为 null 或空白
- **THEN** `stopReason()` 为 `stop`

### Requirement: Agent 实例有状态且非线程安全

`ReActAgent` SHALL 绑定单个 `ChatMemory` 实例，并 SHALL 被标注为不可并发使用。框架 SHALL NOT 创建线程，SHALL NOT 做内部同步。

#### Scenario: 记忆按 Agent 实例绑定
- **WHEN** 以不同的会话标识组装两个 Agent
- **THEN** 每个 Agent 只读写自己的记忆

### Requirement: Agent 暴露自身配置并支持重置

`Agent` SHALL 暴露 `memory()`、`systemPrompt()`、`tools()`、`run(String, AgentListener)`，并 SHALL 提供默认的 `reset()` 用于清空会话记忆。

#### Scenario: 重置清空对话历史
- **WHEN** 在一次完成的 run 之后调用 `reset()`
- **THEN** `memory().isEmpty()` 为 true

### Requirement: 系统提示词惰性计算并在首次使用后冻结

系统提示词 SHALL 由已注册的工具集与模式推导，在首次访问时计算并缓存。框架 SHALL NOT 使该缓存失效，因此所有工具必须在首次 `run` 之前注册完毕。

#### Scenario: 提示词反映首次使用时点的工具集
- **WHEN** 注册两个工具后读取 `systemPrompt()`
- **THEN** 返回的提示词描述这两个工具

#### Scenario: 首次使用后新增的工具不被反映
- **WHEN** 在系统提示词已计算之后注册新工具
- **THEN** `systemPrompt()` 返回先前缓存的值，且不包含新工具的描述

### Requirement: 系统提示词按请求前置且从不落库到记忆

系统提示词 SHALL 在构造每次模型请求时前置，且 SHALL NOT 写入 `ChatMemory`，从而避免被窗口裁剪逻辑当作普通对话消息处理。

#### Scenario: 记忆中没有由 Agent 添加的系统消息
- **WHEN** 一次 run 结束并检查记忆
- **THEN** 由 Agent 写入的消息只有用户输入、助手消息与工具结果

#### Scenario: 请求上下文的顺序为「系统提示词 → 历史」
- **WHEN** 检查为某次模型调用构造的请求
- **THEN** 其第一条消息角色为 `system` 且携带当前系统提示词，随后按时间顺序跟随记忆中的消息
