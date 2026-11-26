# error-semantics Specification

## Purpose

定义框架的失败处理契约：哪些情况抛 FishAgentException、哪些情况转成错误文本回填给模型、哪些情况只用结果标记而不抛异常。

## Requirements

### Requirement: FishAgentException 是统一的运行时异常类型

`FishAgentException` SHALL 继承 `RuntimeException`，SHALL 提供仅消息与消息加原因两个构造器，并 SHALL 提供用于格式化消息的 `of(String format, Object... args)` 与产出形如 `<消息>: <原因消息>` 且保留 cause 的 `wrap(String message, Throwable cause)`。

#### Scenario: 格式化工厂
- **WHEN** 调用 `FishAgentException.of("HTTP %d%s", 500, "，响应：...")`
- **THEN** 消息为格式化后的字符串

#### Scenario: 包装保留原因
- **WHEN** 调用 `wrap("工具 add 执行失败", cause)`
- **THEN** 消息为 `工具 add 执行失败: <原因消息>`
- **AND** `getCause()` 返回原始 cause

#### Scenario: 异常是非受检的
- **WHEN** 从没有 throws 子句的方法中抛出 `FishAgentException`
- **THEN** 代码可编译，因为该类型是非受检的

### Requirement: 配置与装配阶段的失败直接抛出

使 Agent 或模型不可用的状况 SHALL 在配置阶段抛出 `FishAgentException`，而不是等到运行期才失败。

#### Scenario: 缺少模型
- **WHEN** 构建 Agent 时未提供模型
- **THEN** `build()` 抛出 `FishAgentException`，消息包含 `未指定模型`

#### Scenario: 注解扫描一无所获
- **WHEN** 注册一个不含 `@AgentTool` 方法的 Bean
- **THEN** 抛出 `FishAgentException`，消息指明该 Bean 的类

#### Scenario: 工具名重复
- **WHEN** 两个工具使用同一名称
- **THEN** 抛出 `FishAgentException`，消息指明该重复名称

#### Scenario: 必填构造参数缺失或为空白
- **WHEN** 模型、记忆被装饰者、工具执行函数等必需协作者为 null，或必需名称为 null/空白
- **THEN** 抛出 `NullPointerException`，消息以中文指明出错的参数

### Requirement: 工具执行失败转为文本，绝不抛出

工具实现内部的失败 SHALL NOT 以异常形式抵达 Agent 循环。它们 SHALL 被转换为错误文本回填给模型，使模型能在同一次 run 内自我纠正。

#### Scenario: 工具抛出运行时异常
- **WHEN** 某个工具实现抛出异常
- **THEN** 工具结果为以 `错误：` 开头的字符串，且循环继续

#### Scenario: 工具抛出框架异常
- **WHEN** 某个工具实现抛出 `FishAgentException`
- **THEN** 适用同样的转换，且循环继续

#### Scenario: 未知工具名
- **WHEN** 模型请求一个未注册的工具
- **THEN** 结果文本说明该工具不存在并列出可用工具，且循环继续

### Requirement: 运行期失败先上报监听器再向外传播

源自模型或框架内部的异常 SHALL 先上报给 `AgentListener.onError`，随后被重新抛出，使调用方能够决定重试或对外呈现。框架 SHALL NOT 吞掉它们。

#### Scenario: 模型失败被上报并重新抛出
- **WHEN** 模型在一次 run 中抛异常
- **THEN** `onError` 收到该异常
- **AND** 同一异常逃出 `run`

#### Scenario: 调用方可在失败后重试
- **WHEN** 调用方捕获传播出的异常并再次调用 `run`
- **THEN** 该 Agent 仍可用，重试正常执行

### Requirement: 降级结果以返回值表达而非抛出

预算耗尽与死循环检测 SHALL 通过 `AgentResult.stopReason()` 与 `AgentResult.success()` 上报，而非以异常形式表达，使调用方能够区分「没有产出答案」与「调用失败」。

#### Scenario: 迭代耗尽是结果而非异常
- **WHEN** 迭代预算耗尽
- **THEN** 不抛异常，`stopReason()` 为 `max_iterations`，`success()` 为 false

#### Scenario: 死循环检测是结果而非异常
- **WHEN** 重复的工具调用超出其预算
- **THEN** 不抛异常，`stopReason()` 为 `loop_detected`，`success()` 为 false

#### Scenario: 调用方可区分结果类型
- **WHEN** 调用方检查返回结果
- **THEN** `success()` 配合 `stopReason()` 足以区分正常作答、预算耗尽与死循环检测

### Requirement: 持久化失败直接抛出

触及文件系统的记忆实现 SHALL 把 I/O 失败转换为消息可操作、指明操作与文件的 `FishAgentException`，而不是泄漏 `IOException`。

#### Scenario: 读取失败的提示
- **WHEN** 会话文件无法读取或解析
- **THEN** 抛出 `FishAgentException`，消息包含 `读取会话文件失败` 与文件路径

#### Scenario: 写入失败的提示
- **WHEN** 会话文件无法写入
- **THEN** 抛出 `FishAgentException`，消息包含 `写入会话文件失败` 与文件路径

#### Scenario: 清空失败的提示
- **WHEN** 会话文件无法删除
- **THEN** 抛出 `FishAgentException`，消息包含 `清空会话文件失败`

### Requirement: 参数转换失败是显式的

当模型提供的参数无法转换为声明的 Java 类型时，框架 SHALL 抛出指明该值与目标类型的 `FishAgentException`。该异常随后 SHALL 在抵达 Agent 循环之前被工具注册表转换为错误文本。

#### Scenario: 非法枚举值指明类型
- **WHEN** 值与目标枚举的任何常量都不匹配
- **THEN** 消息指明该值与枚举的简单名

#### Scenario: 非法数字文本指明取值
- **WHEN** 把非数字字符串转换为数字类型
- **THEN** 消息包含 `无法转换为数字`

#### Scenario: 表达式求值失败是显式的
- **WHEN** 内置计算器收到空表达式、括号不匹配或无法识别的记号
- **THEN** 抛出 `FishAgentException`，消息描述该问题
