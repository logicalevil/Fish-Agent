## ADDED Requirements

### Requirement: 工具异常转为错误文本而非向上抛出

`ToolRegistry.execute(ToolCall)` SHALL 捕获工具实现抛出的任何异常，并返回一个以 `错误：` 开头、后接异常消息的字符串，以便模型观察到失败并自我纠正。Agent 循环 SHALL NOT 因工具失败而中断。

#### Scenario: 失败的工具返回错误文本
- **WHEN** 某个已注册工具抛出 `IllegalStateException("炸了")`
- **THEN** `execute` 返回以 `错误：` 开头的字符串
- **AND** 没有任何异常到达调用方

#### Scenario: 工具失败后循环继续
- **WHEN** 一次 run 中某工具失败，随后模型给出了最终答案
- **THEN** 该 run 正常完成，且 `success()` 为 `true`

#### Scenario: null 工具结果变为空字符串
- **WHEN** 工具返回 null
- **THEN** `execute` 返回空字符串

### Requirement: 未知工具调用回告可用工具集

当工具调用指向未注册的工具时，`execute` SHALL 返回一段错误文本，说明该工具不存在并列出所有已注册工具的名称，且 SHALL NOT 抛异常。

#### Scenario: 未注册的工具名
- **WHEN** 在含三个工具的注册表上以名称 `not_exists` 调用 `execute`
- **THEN** 返回文本包含 `不存在名为 not_exists 的工具`
- **AND** 返回文本列出已注册的工具名

### Requirement: 通用参数名被纠正为声明的参数名

当工具调用恰好提供一个参数、其键为通用占位名（`input`、`action_input`、`argument`、`arguments`、`arg`、`query`、`value`、`params` 或 `request`），且目标工具恰好声明一个参数时，注册表 SHALL 把参数键改写为声明的参数名。其它情况下调用 SHALL 被原样返回。

#### Scenario: 单个通用参数被重映射
- **WHEN** `greet` 声明了唯一参数 `name`，却以 `{"input": "红红"}` 被调用
- **THEN** 规范化后的调用参数为 `{"name": "红红"}`
- **AND** 执行该规范化调用返回 `你好，红红`

#### Scenario: 多参数调用不被改动
- **WHEN** 调用提供了多于一个参数
- **THEN** 调用被原样返回

#### Scenario: 多参数工具不被改动
- **WHEN** 目标工具声明的参数多于一个
- **THEN** 调用被原样返回

#### Scenario: 无法识别的参数名不被改动
- **WHEN** 唯一参数的键不在通用占位名集合中
- **THEN** 调用被原样返回

#### Scenario: 未知工具不被改动
- **WHEN** 调用指向未注册的工具
- **THEN** 调用被原样返回

#### Scenario: 重映射时保留参数顺序
- **WHEN** 某个通用参数被重映射为声明的名称
- **THEN** 结果参数映射保持插入顺序

### Requirement: 重复的相同工具调用被判定为死循环

Agent SHALL 统计一次 run 内每个不同 `toolName|arguments` 签名被请求的次数。一旦该次数超过 `maxRepeatedToolCalls`，Agent SHALL 停止执行该工具、停止本次 run，并 SHALL 返回 `stopReason()` 为 `loop_detected`、`success()` 为 `false` 的结果。

#### Scenario: 超过重复预算后判定死循环
- **WHEN** 模型每一轮都以完全相同的参数请求 `multiply`，且 `maxRepeatedToolCalls` 为 2
- **THEN** 该 run 以 `stopReason() == "loop_detected"` 终止
- **AND** `success()` 为 `false`
- **AND** `iterations()` 为 3（两次允许的执行加上触发判定的那次）

#### Scenario: 同名工具换参数不算死循环
- **WHEN** 模型在不同轮次以不同参数值请求同一工具
- **THEN** 每次调用按不同签名计数，死循环检测不触发

#### Scenario: 死循环终止不抛异常
- **WHEN** 检测到死循环
- **THEN** `run` 正常返回，不抛任何异常

#### Scenario: 打破循环的提示被写入记忆
- **WHEN** 某次调用上检测到死循环
- **THEN** 一条消息被追加到记忆，其内容指示模型停止重复调用直接给出最终答案
- **AND** 该消息被上报给 `AgentListener.onToolResult`

### Requirement: 工具调用失败绝不破坏会话状态

由于工具失败与死循环检测分别以普通工具结果和终止性结果的形式表达，会话记忆 SHALL 保持一致，并可被后续的 `run` 继续复用。

#### Scenario: 死循环终止后记忆仍可用
- **WHEN** 某次 run 因死循环检测而终止，随后在同一 Agent 上发起新的 run
- **THEN** 先前的消息仍在记忆中，新的 run 正常推进
