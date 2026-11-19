# streaming-answer-gate Specification

## Purpose

定义文本 ReAct 模式下流式输出门控的契约：如何从混杂着推理步骤的 token 流中识别并只下发最终答案，避免中间思考泄露给使用者。

## Requirements

### Requirement: 文本模式流式输出抑制中间推理

在 `REACT_TEXT` 流式模式下，框架 SHALL 只把最终答案文本转发给 `AgentListener.onToken`，扣留 `Thought`、`Action` 与 `Action Input` 片段，使中间推理永不抵达使用者。在 `NATIVE_TOOLS` 流式模式下，全部 token SHALL 被原样转发。

#### Scenario: 只有最终答案抵达监听器
- **WHEN** 一次文本模式流式 run 先产生思考/动作步骤，随后产生最终答案 `答案是 16。`
- **THEN** 全部 `onToken` 载荷拼接后等于 `答案是 16。`

#### Scenario: 上报文本与返回结果一致
- **WHEN** 同一次 run 完成
- **THEN** `AgentResult.text()` 等于拼接后的 token 流

#### Scenario: 原生模式转发全部 token
- **WHEN** 一次原生模式流式 run 产生 token
- **THEN** 每个 token 都被无缓冲地转发给 `onToken`

### Requirement: 门只对显式的最终答案标签开启

门 SHALL 缓冲传入的 token 并搜索 `Final Answer` 标签，容忍前导空白、引用符、列表符、Markdown 加粗标记，以及半角与全角冒号。标签之后的文本 SHALL 被立即转发。

#### Scenario: 加粗标签配全角冒号开启门
- **WHEN** 流式文本包含 `**Final Answer：** 答案是 16。`
- **THEN** 门开启，` 答案是 16。` 被转发

#### Scenario: 标签跨 token 边界切分仍被识别
- **WHEN** 标签以任意偏移切成多个 token 到达
- **THEN** 门恰好在标签末尾开启一次

#### Scenario: 无标签则不转发任何内容
- **WHEN** 流式内容从未包含 `Final Answer` 标签
- **THEN** 不发生任何 `onToken` 回调

### Requirement: 工具调用步骤重置门缓冲

当缓冲文本在未出现更早的 `Final Answer` 标签的情况下匹配到 `Action` 标签时，门 SHALL 丢弃缓冲区并继续等待，使后续步骤的思考文本不会与上一步的残留拼接、从而误判出不存在的最终答案。

#### Scenario: 动作步骤丢弃缓冲
- **WHEN** 某个含 `Action:` 的步骤被缓冲
- **THEN** 缓冲区被清空，且该步骤不转发任何 token

#### Scenario: 上一步残留无法开启门
- **WHEN** 上一步被丢弃而下一步包含最终答案
- **THEN** 只有下一步的答案文本被转发

### Requirement: 每段已发送内容只被转发一次

门 SHALL 记录已转发的边界位置，并 SHALL 在每次 token 到达时只转发新追加的后缀，使文本既不重复也不遗漏。

#### Scenario: 跨 token 边界不重复
- **WHEN** 答案以许多小 token 交付
- **THEN** 拼接后的输出中每个字符恰好出现一次

#### Scenario: 空 token 与 null token 被忽略
- **WHEN** 收到 null 或空的 token
- **THEN** 不产生回调，且缓冲区不变

### Requirement: 门的作用域限定于单次模型调用

一个门实例 SHALL 只服务一次流式模型调用，且 SHALL NOT 跨轮次复用。

#### Scenario: 每轮使用全新的门
- **WHEN** 一次文本模式 run 执行两次流式模型调用
- **THEN** 每次调用使用各自独立的状态，使第一次调用的输出不会泄漏到第二次
