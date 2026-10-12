# observability-tracing Specification

## Purpose

定义链路追踪扩展点的契约：在哪些结构边界产生 span、span 的 ID 层级与生命周期、环境上下文如何传递，以及默认零开销的保证。

## Requirements

### Requirement: Tracer 是在结构边界上被调用的追踪扩展点

`Tracer` SHALL 是声明 `Span start(String name, Span parent)` 的函数式接口。框架 SHALL 只在这三个结构边界上调用它：`agent.run`、`model.call`、`tool.execute`。Tracer SHALL NOT 负责维护「当前 span」。

#### Scenario: 产生三个 span 边界
- **WHEN** 配置了 tracer 的一次 run 执行了一次工具调用
- **THEN** 名为 `agent.run`、`model.call`、`tool.execute` 的 span 被创建

#### Scenario: 父 span 由框架提供
- **WHEN** 在一次 run 中为 `model.call` 调用 `start`
- **THEN** `parent` 参数是外层的 `agent.run` span

#### Scenario: 根操作收到空实现父节点
- **WHEN** 为 `agent.run` 调用 `start`
- **THEN** `parent` 参数为 `Span.NOOP`

### Requirement: 追踪默认关闭且零开销

未配置 tracer 时，框架 SHALL 使用 `Tracer.NOOP`，它 SHALL 返回 `Span.NOOP` 且不生成标识、不向环境栈压入任何内容。`ReActAgent.builder()` SHALL 默认采用该行为。

#### Scenario: 默认配置不产生任何追踪
- **WHEN** 构建 Agent 时未调用 `tracer(...)`，并执行一次 run
- **THEN** 回调内 `Span.current()` 返回 `Span.NOOP`，其 `traceId()` 与 `spanId()` 均为空串

#### Scenario: 空 span 的操作是惰性的
- **WHEN** 对 `Span.NOOP` 调用 `attribute`、`end` 或 `close`
- **THEN** 什么都不发生，且不抛异常

#### Scenario: tracer 返回 null 会关闭该 span
- **WHEN** 某个 tracer 的 `start` 返回 null
- **THEN** 框架视该 span 为不存在，不把它压入环境栈

### Requirement: Span 暴露标识、属性与生命周期

`Span` SHALL 暴露 `traceId()`、`spanId()`、`parentSpanId()`、`attribute(String, Object)`、`end()`，以及等价于 `end()` 的默认 `close()`。`attribute` SHALL 返回该 span 以便链式调用。`end()` SHALL 幂等。

#### Scenario: 标识层级
- **WHEN** 在根 span 内创建一个子 span
- **THEN** 子 span 的 `traceId()` 等于根的 `traceId()`
- **AND** 子 span 的 `parentSpanId()` 等于根的 `spanId()`

#### Scenario: 根 span 没有父节点
- **WHEN** 检查一个根 span
- **THEN** `parentSpanId()` 为 null

#### Scenario: 属性可链式设置
- **WHEN** 调用 `span.attribute("tool", "weather").end()`
- **THEN** 两次调用作用于同一个 span

#### Scenario: 工具 span 记录工具名
- **WHEN** 一次被追踪的 run 中某个工具被执行
- **THEN** `tool.execute` span 携带名为 `tool`、值为该工具名的属性

### Requirement: 当前 span 通过线程本地的环境栈获取

框架 SHALL 维护每线程的活动 span 栈，并通过 `Span.current()` 暴露最内层的 span。关闭 span SHALL 先出栈再调用 `end()`。当栈清空后线程本地变量 SHALL 被移除，使线程池中的线程不会残留过期上下文。

#### Scenario: 最内层 span 即当前 span
- **WHEN** 在一次工具执行（嵌套于模型调用，再嵌套于 run）内部调用 `Span.current()`
- **THEN** 返回的 span 是 `tool.execute` span

#### Scenario: 子 span 关闭后父节点恢复
- **WHEN** 工具执行完成、控制权回到模型调用
- **THEN** `Span.current()` 返回 `model.call` span

#### Scenario: run 结束后栈被清理
- **WHEN** 一次 run 在某线程上完成，随后在该线程上于任何框架操作之外调用 `Span.current()`
- **THEN** 返回 `Span.NOOP`

#### Scenario: 异常路径上 span 仍被关闭
- **WHEN** 一次 run 抛出异常
- **THEN** 已开启的 span 仍被关闭，环境栈被正确展开

### Requirement: 内置 SimpleTracer 生成基于 UUID 的标识

`SimpleTracer` SHALL 生成由 UUID 派生的十六进制标识，SHALL 为嵌套 span 复用父节点的 `traceId`，并 SHALL 支持通过 `create()` 为每次 run 生成新 trace id，或通过 `create(String)` 用固定 trace id 跨越多次 run。

#### Scenario: 嵌套 span 共享 trace id
- **WHEN** 使用 `SimpleTracer.create()` 的 run 执行一次模型调用
- **THEN** 模型 span 的 `traceId()` 等于 run span 的 `traceId()`

#### Scenario: 不同 run 得到不同 trace id
- **WHEN** 连续两次 run 都使用 `SimpleTracer.create()`
- **THEN** 它们的根 span 具有不同的 `traceId()`

#### Scenario: 固定 trace id 跨越多次 run
- **WHEN** 两次 run 都使用 `SimpleTracer.create("trace-abc")`
- **THEN** 两个根 span 的 `traceId()` 都为 `trace-abc`

#### Scenario: 空白固定 trace id 退化为生成值
- **WHEN** 使用 `SimpleTracer.create("  ")`
- **THEN** 使用一个生成的、非空白的 trace id

#### Scenario: span 渲染包含标识与属性
- **WHEN** 在设置属性后调用某个 `SimpleTracer` span 的 `toString()`
- **THEN** 渲染结果包含 span 名、trace id、span id、父 span id 与属性
