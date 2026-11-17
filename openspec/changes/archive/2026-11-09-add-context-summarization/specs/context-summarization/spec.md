# context-summarization Specification

## ADDED Requirements

### Requirement: 摘要压缩在写入时同步触发

`SummarizingChatMemory` SHALL 在 `add()` 时判断「尚未被摘要的消息数」，超过 `triggerMessages` 时 SHALL 同步执行一次压缩。框架 SHALL NOT 为此创建线程或定时任务。

#### Scenario: 未超阈值时不压缩
- **WHEN** 不断写入消息但未摘要数始终不超过 `triggerMessages`
- **THEN** 不调用模型生成摘要，`messages()` 直接返回原文

#### Scenario: 超过阈值触发一次压缩
- **WHEN** 未摘要消息数首次超过 `triggerMessages`
- **THEN** 调用一次模型生成摘要，且后续 `messages()` 返回「摘要 + 最近 keepMessages 条」

#### Scenario: 同一批消息不重复压缩
- **WHEN** 压缩完成后再写入少量消息（未再次超阈值）
- **THEN** 不重新生成摘要

#### Scenario: 框架不创建线程
- **WHEN** 完成一次压缩
- **THEN** 执行期间没有由框架创建的线程

### Requirement: 压缩产出为「摘要 + 最近消息」

压缩后 `messages()` SHALL 返回一条摘要消息，其后跟随最近 `keepMessages` 条原文消息，顺序为**摘要在前**。摘要 SHALL 是一条消息，SHALL NOT 展开为多条。

#### Scenario: 产出一条摘要加若干尾部
- **WHEN** 压缩了 20 条消息且 `keepMessages` 为 5
- **THEN** `messages()` 返回 6 条：1 条摘要 + 5 条原文

#### Scenario: 摘要在前
- **WHEN** 检查 `messages()` 的顺序
- **THEN** 第一条是摘要消息，其余为按时间顺序的近期消息

#### Scenario: 尾部消息保持原样
- **WHEN** 检查压缩后保留的尾部消息
- **THEN** 它们的内容与原文逐字一致

### Requirement: 摘要消息使用 USER 角色并带固定前缀

摘要消息 SHALL 使用 `Role.USER`，其内容 SHALL 以固定前缀 `Here is a summary of the conversation so far:` 开头，以便与真实用户输入区分。

#### Scenario: 摘要角色为 USER
- **WHEN** 检查压缩后第一条消息的角色
- **THEN** 为 `USER`

#### Scenario: 摘要内容带可识别前缀
- **WHEN** 检查摘要消息的内容
- **THEN** 以固定前缀开头，前缀之后为摘要正文

### Requirement: 摘要链式累积

再次压缩时，上一次的摘要消息 SHALL 作为输入的一部分参与新的摘要生成，使早期信息不会在二次压缩时丢失。

#### Scenario: 二次压缩并入上次摘要
- **WHEN** 已存在摘要 S1，此后累积消息再次触发压缩
- **THEN** 生成摘要时使用的输入包含 S1 的内容

#### Scenario: 早期信息在多次压缩后仍可见
- **WHEN** 连续触发多次压缩
- **THEN** 最终摘要中仍能体现最早的消息内容

### Requirement: 原文始终完整保留

`SummarizingChatMemory` SHALL 保留全部原始消息，`fullHistory()` SHALL 返回未被压缩的完整历史。压缩 SHALL 只影响 `messages()` 的返回结果。

#### Scenario: 全文可查
- **WHEN** 压缩发生若干次后调用 `fullHistory()`
- **THEN** 返回全部消息，数量等于写入的总条数

#### Scenario: 压缩不删除原文
- **WHEN** 检查底层记忆的消息数
- **THEN** 不因压缩而减少

### Requirement: 摘要失败退化为窗口裁剪

当摘要生成失败（模型抛异常或返回空白）时，`SummarizingChatMemory` SHALL NOT 中断对话，SHALL 退化为「只返回最近 `keepMessages` 条」，并 SHALL 将压缩位置推进以避免重复重试。

#### Scenario: 模型异常时对话不中断
- **WHEN** 摘要所用模型抛出异常
- **THEN** `add()` 正常返回，不向上抛异常

#### Scenario: 失败后仍可继续写入
- **WHEN** 在一次摘要失败之后继续写入消息
- **THEN** 写入正常，且 `messages()` 返回不超过 `keepMessages` 条原文

#### Scenario: 失败状态可观测
- **WHEN** 摘要失败后检查状态
- **THEN** 可通过公开方法得知上一次摘要未成功

### Requirement: 参数在构造时校验

`keepMessages` SHALL 小于 `triggerMessages`，否则 SHALL 在构造时抛出 `FishAgentException`（快速失败）。`triggerMessages` 与 `keepMessages` SHALL NOT 接受小于 1 的值。

#### Scenario: keepMessages 不小于 triggerMessages 时失败
- **WHEN** 以 `triggerMessages=20`、`keepMessages=20` 构造
- **THEN** 抛出 `FishAgentException`

#### Scenario: 非法数值失败
- **WHEN** 以小于 1 的 `triggerMessages` 或 `keepMessages` 构造
- **THEN** 抛出 `FishAgentException`

#### Scenario: 合法参数构造成功
- **WHEN** 以 `triggerMessages=20`、`keepMessages=5` 构造
- **THEN** 构造成功

### Requirement: 可叠加在其它记忆实现之上

`SummarizingChatMemory` SHALL 可包装任意 `ChatMemory` 实现，包括其它装饰器。其 `id()` SHALL 返回被包装记忆的标识。

#### Scenario: 包装文件记忆
- **WHEN** 包装 `FileChatMemory` 并写入消息
- **THEN** 消息正常落盘，且 `messages()` 返回压缩后的结果

#### Scenario: 标识透传
- **WHEN** 包装标识为 `s1` 的记忆
- **THEN** `id()` 返回 `s1`

#### Scenario: 与窗口装饰器组合
- **WHEN** 用 `SummarizingChatMemory` 包装 `MessageWindowChatMemory`
- **THEN** 两者均可正常工作，构造不失败
