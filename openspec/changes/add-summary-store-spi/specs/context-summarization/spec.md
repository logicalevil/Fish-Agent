# context-summarization Specification (delta)

## ADDED Requirements

### Requirement: 摘要进度经由 SummaryStore 承载

`SummarizingChatMemory` SHALL 把摘要进度（摘要正文、已摘要游标、上次是否成功）存放在 `SummaryStore` 提供的中，SHALL NOT 以实例字段作为摘要状态的唯一载体。压缩前 SHALL 从 store 读取当前状态，压缩后 SHALL 写回。

#### Scenario: 状态来自 store 而非实例字段
- **WHEN** 用一个预先保存了摘要状态的 `SummaryStore` 构造 `SummarizingChatMemory`
- **THEN** `messages()` 返回「该摘要 + 最近 keepMessages 条原文」，而非从无摘要状态开始

#### Scenario: 压缩结果写回 store
- **WHEN** 一次压缩成功完成
- **THEN** store 中该会话的状态包含新摘要与非零游标

#### Scenario: 换实例不丢摘要
- **WHEN** 两个 `SummarizingChatMemory` 实例共享同一个 `SummaryStore` 与同一会话标识
- **THEN** 在第一个实例压缩后，第二个实例读取到相同的摘要状态

### Requirement: 摘要生成经由可替换的 Summarizer

`SummarizingChatMemory` SHALL 通过 `Summarizer` 生成摘要，SHALL NOT 把提示词与模型调用逻辑内联为唯一路径。未显式注入时 SHALL 默认使用 `ModelSummarizer`，其行为与原实现等价。

#### Scenario: 默认使用 ModelSummarizer
- **WHEN** 以 `of(delegate, model)` 构造
- **THEN** 摘要通过该 `model` 生成，产出形态与改动前一致

#### Scenario: 可注入自定义 Summarizer
- **WHEN** 注入一个返回固定文本的自定义 `Summarizer`
- **THEN** `summary()` 返回该文本，且不调用底层 `ChatModel`

#### Scenario: 自定义 Summarizer 的失败被降级
- **WHEN** 注入的 `Summarizer` 返回 null 或空白
- **THEN** 不中断对话，退化为窗口裁剪，且 `lastCompactionSucceeded()` 为 false

## MODIFIED Requirements

### Requirement: 摘要压缩在写入时同步触发

`SummarizingChatMemory` SHALL 在 `add()` 时判断「尚未被摘要的消息数」，超过 `triggerMessages` 时 SHALL 同步执行一次压缩。框架 SHALL NOT 为此创建线程或定时任务。判断所用的游标 SHALL 来自 `SummaryStore` 中的当前状态。

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

#### Scenario: 游标来自 store
- **WHEN** store 中已保存了非零游标的状态
- **THEN** `add()` 的触发判断以该游标为起点，而非从零开始

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

#### Scenario: 状态从 store 恢复后产出形态不变
- **WHEN** 摘要状态由 store 加载而非本次实例内生成
- **THEN** `messages()` 仍返回「1 条摘要 + 最近 keepMessages 条原文」

### Requirement: 摘要链式累积

再次压缩时，上一次的摘要消息 SHALL 作为输入的一部分参与新的摘要生成，使早期信息不会在二次压缩时丢失。上一次的摘要 SHALL 从 `SummaryStore` 读取。

#### Scenario: 二次压缩并入上次摘要
- **WHEN** 已存在摘要 S1，此后累积消息再次触发压缩
- **THEN** 生成摘要时使用的输入包含 S1 的内容

#### Scenario: 早期信息在多次压缩后仍可见
- **WHEN** 连续触发多次压缩
- **THEN** 最终摘要中仍能体现最早的消息内容

#### Scenario: 跨实例的链式累积
- **WHEN** 摘要 S1 由实例 A 写入共享 store，实例 B 随后触发压缩
- **THEN** 实例 B 的压缩输入包含 S1 的内容

### Requirement: 摘要失败退化为窗口裁剪

当摘要生成失败（模型抛异常或返回空白）时，`SummarizingChatMemory` SHALL NOT 中断对话，SHALL 退化为「只返回最近 `keepMessages` 条」，并 SHALL 将压缩位置推进以避免重复重试。该失败状态 SHALL 写入 `SummaryStore`。

#### Scenario: 模型异常时对话不中断
- **WHEN** 摘要所用模型抛出异常
- **THEN** `add()` 正常返回，不向上抛异常

#### Scenario: 失败后仍可继续写入
- **WHEN** 在一次摘要失败之后继续写入消息
- **THEN** 写入正常，且 `messages()` 返回不超过 `keepMessages` 条原文

#### Scenario: 失败状态可观测
- **WHEN** 摘要失败后检查状态
- **THEN** 可通过公开方法得知上一次摘要未成功

#### Scenario: 失败状态持久化
- **WHEN** 一次压缩失败之后重新 load store 中的状态
- **THEN** `lastCompactionSucceeded` 为 false，且游标已推进
