# conversation-memory Specification

## Purpose

定义会话记忆的契约：记忆抽象接口、进程内实现、滑动窗口裁剪与 JSONL 文件持久化，以及消息与工具调用在持久化时的序列化形式。
## Requirements
### Requirement: ChatMemory 定义会话记忆契约

`ChatMemory` SHALL 暴露 `id()`、`add(Message)`、`addAll(List<Message>)`、`messages()`、`clear()`、`size()`、`isEmpty()`。各实现 SHALL 返回调用方可安全读取而不会改动已存历史的列表。

#### Scenario: 追加并读取消息
- **WHEN** 通过 `addAll` 追加两条消息
- **THEN** `size()` 为 2，且 `messages()` 按插入顺序返回它们

#### Scenario: 清空后记忆为空
- **WHEN** 调用 `clear()`
- **THEN** `isEmpty()` 为 true

#### Scenario: null 消息被忽略
- **WHEN** 调用 `add(null)`
- **THEN** 不抛异常且规模不变

#### Scenario: null 批量值被忽略
- **WHEN** 调用 `addAll(null)`
- **THEN** 不抛异常且记忆不变

### Requirement: 内存记忆是默认实现

`InMemoryChatMemory` SHALL 用线程安全的进程内集合存储消息，且当 Agent 组装时未指定记忆，它 SHALL 作为默认记忆。`InMemoryChatMemory(String id)` SHALL 通过生成 `session-<uuid>` 标识来拒绝 null 或空白 id。其并发保护 SHALL 经由 `MemoryLock` 表达，默认使用 `LocalMemoryLock`。

#### Scenario: 裸 Agent 的默认记忆
- **WHEN** 以 `ReActAgent.builder().model(...).build()` 组装且未指定记忆
- **THEN** `memory()` 返回 `InMemoryChatMemory`

#### Scenario: 空白会话标识生成 id
- **WHEN** 构造 `new InMemoryChatMemory("  ")`
- **THEN** `id()` 以 `session-` 开头

#### Scenario: 并发追加是安全的
- **WHEN** 从多个线程追加消息
- **THEN** 不发生并发修改异常，且所有消息都被保留

#### Scenario: 默认锁不阻塞
- **WHEN** 单线程向默认记忆追加消息
- **THEN** 动作立即完成，无额外等待

### Requirement: 滑动窗口记忆在保留系统消息的前提下限制上下文

`MessageWindowChatMemory` SHALL 装饰另一个 `ChatMemory`，其 `messages()` SHALL 最多返回 `maxMessages` 条非 SYSTEM 消息，同时 SHALL 始终包含全部 SYSTEM 消息，并 SHALL 通过 `fullHistory()` 暴露未被裁剪的完整历史。`windowing` SHALL 允许在已有记忆之上套一层窗口裁剪。其并发保护由注入的 `MemoryLock` 承担。

#### Scenario: 窗口保留最近的非系统消息
- **WHEN** 大小为 3 的窗口收到 1 条 SYSTEM 消息与 6 条 USER 消息
- **THEN** `messages()` 返回 4 条
- **AND** 第一条是 SYSTEM 消息
- **AND** 第二条是第 4 条 USER 消息
- **AND** 最后一条是第 6 条 USER 消息

#### Scenario: 完整历史不受窗口影响
- **WHEN** 在上述例子上调用 `fullHistory()`
- **THEN** 返回全部 7 条已存消息

#### Scenario: 历史短于窗口时整体返回
- **WHEN** 非 SYSTEM 消息数量不超过 `maxMessages`
- **THEN** `messages()` 返回全部这些消息加上全部 SYSTEM 消息

#### Scenario: 写入穿透到被装饰者
- **WHEN** 通过窗口追加一条消息
- **THEN** 被装饰者存储了它，且 `fullHistory()` 反映了该追加

#### Scenario: 窗口大小被抬升为至少 1
- **WHEN** 以 0 或负值创建窗口
- **THEN** `maxMessages()` 返回 1

#### Scenario: null 被装饰者被拒绝
- **WHEN** 以 null 被装饰者创建窗口
- **THEN** 抛出 `NullPointerException`，消息包含 `delegate 不能为空`

#### Scenario: 窗口可包裹任意记忆实现
- **WHEN** 以文件记忆作为被装饰者调用 `windowing(delegate, n)`
- **THEN** 持久化仍然发生，同时 `messages()` 被窗口裁剪

#### Scenario: SYSTEM 保留且位于最前
- **WHEN** 反复追加消息直到窗口滑动
- **THEN** 所有 SYSTEM 消息仍按原始顺序出现在结果的最前面

#### Scenario: 默认窗口大小
- **WHEN** 通过 `of()` 构建且 `maxMessages` 小于 1
- **THEN** 被钳制为 1

#### Scenario: 保留全部消息不变
- **WHEN** 反复向窗口记忆追加消息
- **THEN** 底层存储保留每一条消息

#### Scenario: 跨多轮重复读取
- **WHEN** 在追加新消息之后再次读取窗口
- **THEN** 窗口被重新计算以包含最近的 `maxMessages` 条消息

#### Scenario: 装饰后的标识透传
- **WHEN** 使用 `windowing` 包装一个标识为 `s1` 的记忆
- **THEN** 该装饰器的 `id()` 为 `s1`

### Requirement: 文件记忆以 JSONL 持久化会话

`FileChatMemory` SHALL 向 `<目录>/<安全会话标识>.jsonl` 每行追加一个 JSON 对象，默认目录为相对于工作目录的 `.fishagent/sessions`，SHALL 在构造时重新加载已有内容，并 SHALL 通过 `file()` 暴露解析后的路径。其进程内互斥 SHALL 由注入的 `MemoryLock` 承担，而 SHALL NOT 依赖方法级的 `synchronized`。

#### Scenario: 消息在重新加载后依然存在
- **WHEN** 追加一条用户消息、一条携带工具调用的助手消息、一条工具结果消息，随后以相同的会话标识与目录重新创建记忆
- **THEN** `messages()` 返回 3 条消息
- **AND** 第一条是用户文本
- **AND** 第二条保留工具调用名与参数
- **AND** 第三条保留工具名

#### Scenario: 默认目录布局
- **WHEN** 调用 `FileChatMemory.of("session-1")` 且不传目录参数
- **THEN** `file()` 解析为相对于工作目录的 `.fishagent/sessions/session-1.jsonl`

#### Scenario: 会话标识为文件系统做净化
- **WHEN** 会话标识包含 `[a-zA-Z0-9_.-]` 之外的字符
- **THEN** 这些字符在文件名中被替换为 `_`

#### Scenario: 空白会话标识回退为默认名
- **WHEN** 会话标识为 null 或空白
- **THEN** 文件名为 `default.jsonl`

#### Scenario: 父目录按需创建
- **WHEN** 目标目录尚不存在而消息被追加
- **THEN** 目录链被创建，文件被写入

#### Scenario: 清空删除底层文件
- **WHEN** 在写入消息之后调用 `clear()`
- **THEN** `isEmpty()` 为 true，且会话文件不再存在

#### Scenario: 文件缺失时历史为空
- **WHEN** 为文件尚不存在的会话创建记忆
- **THEN** `messages()` 无错返回空列表

#### Scenario: 文件中的空行被跳过
- **WHEN** 会话文件在记录之间存在空行
- **THEN** 加载成功，且空行不产生消息

#### Scenario: 文件损坏以框架异常暴露
- **WHEN** 会话文件中的某一行不是合法 JSON
- **THEN** 抛出 `FishAgentException`，消息包含 `读取会话文件失败`

#### Scenario: 写入失败以框架异常暴露
- **WHEN** 消息无法写入磁盘
- **THEN** 抛出 `FishAgentException`，消息包含 `写入会话文件失败`

#### Scenario: 从原始映射批量导入
- **WHEN** 以一组原始消息映射调用 `importMessages`
- **THEN** 每个映射被转换为 `Message` 并按顺序追加

#### Scenario: 替换默认锁为跨进程实现
- **WHEN** 以 `LockedChatMemory` 包裹文件记忆并注入跨进程 `MemoryLock`
- **THEN** 记忆访问经由该锁，而文件仍按 JSONL 格式持久化

### Requirement: 消息通过可移植的映射表示完成往返

`Message` SHALL 通过 `toMap()` 序列化、通过 `fromMap(Map)` 反序列化，且不丢失角色、正文、工具调用或工具名。工具调用 SHALL 以 `id`、`name`、`arguments` 序列化。当相应内容不存在时，`toolCalls` 与 `toolName` 键 SHALL 被省略。

#### Scenario: 普通消息往返
- **WHEN** 一条用户消息被序列化后再反序列化
- **THEN** 得到的消息等于原消息

#### Scenario: 工具调用消息往返
- **WHEN** 一条携带工具调用的助手消息被序列化后再反序列化
- **THEN** 工具调用的名称与参数映射被保留

#### Scenario: 可选键被省略
- **WHEN** 一条消息既无工具调用也无工具名
- **THEN** 其映射只包含 `role` 与 `content`

#### Scenario: 未知角色退化为 user
- **WHEN** 映射携带无法识别的角色值或缺失角色
- **THEN** 得到的消息角色为 `USER`

#### Scenario: 角色值忽略大小写匹配
- **WHEN** 映射携带角色 `ASSISTANT` 或 `assistant`
- **THEN** 两者都反序列化为 `Role.ASSISTANT`

### Requirement: 消息是不可变值对象

`Message` SHALL 不可变：构造要求角色非 null，null 正文被规范化为空串，工具调用列表被防御性复制，相等性与哈希值由角色、正文、工具调用与工具名共同决定。

#### Scenario: null 角色被拒绝
- **WHEN** 以 null 角色构建 `Message`
- **THEN** 抛出 `NullPointerException`，消息包含 `role 不能为空`

#### Scenario: null 正文被规范化
- **WHEN** 以 null 正文构建 `Message`
- **THEN** `content()` 为空字符串

#### Scenario: 修改来源列表不影响消息
- **WHEN** 以可变工具调用列表构建消息，随后修改该列表
- **THEN** 消息的 `toolCalls()` 保持不变

#### Scenario: 空值判定计入工具调用
- **WHEN** 一条消息正文为空但至少有一个工具调用
- **THEN** `isBlank()` 为 false

### Requirement: 工具调用携带稳定标识与不可变参数

`ToolCall` SHALL 是含 `id`、`name`、`arguments` 的 record。null 或空白的 id SHALL 被替换为生成的 UUID。参数映射 SHALL 被防御性复制且不可修改。

#### Scenario: 缺失的 id 被生成
- **WHEN** 以 null id 创建 `ToolCall`
- **THEN** `id()` 为非空白标识

#### Scenario: 参数映射不可变
- **WHEN** 修改工具调用返回的参数映射
- **THEN** 抛出 `UnsupportedOperationException`

#### Scenario: 便捷工厂提供 id
- **WHEN** 使用 `ToolCall.of(name, arguments)`
- **THEN** 所得调用的 id 为非空白生成值

### Requirement: 记忆的并发保护由 MemoryLock 承担

框架内所有 `ChatMemory` 实现的并发保护 SHALL 经由 `MemoryLock`，SHALL NOT 在实现内部直接使用 `synchronized`。未显式配置锁时，实现 SHALL 使用不引入额外依赖的本地实现，且行为与既有一致。压缩类实现 SHALL 遵守同一条约束。

#### Scenario: 未配置锁时默认可用
- **WHEN** 组装任意 `ChatMemory` 实现且不配置锁
- **THEN** 该记忆可正常工作，且不因此新增运行时依赖

#### Scenario: 配置跨进程锁后互斥范围扩大
- **WHEN** 为记忆配置 `isDistributed()` 为 true 的锁
- **THEN** 对同一资源标识的并发访问在多个实例之间互斥

#### Scenario: 锁实现可被替换而不改接口
- **WHEN** 用另一 `MemoryLock` 实现重新组装同一记忆类型
- **THEN** 无需修改该记忆类或其实现

#### Scenario: 摘要压缩实现同样遵守该约束
- **WHEN** 检查 `SummarizingChatMemory` 的并发保护方式
- **THEN** 其互斥经由 `MemoryLock`，而非自身的 `synchronized`

### Requirement: 摘要压缩是独立的记忆实现

`ChatMemory` 的能力集合 SHALL 包含 `SummarizingChatMemory` 这一实现，它与 `InMemoryChatMemory`、`MessageWindowChatMemory`、`FileChatMemory` 并列，并 SHALL 可与其它的记忆实现自由组合。压缩 SHALL 只改变 `messages()` 返回的消息列表，SHALL NOT 删除底层原文。

#### Scenario: 与其它实现并列可用
- **WHEN** 以 `SummarizingChatMemory` 作为会话记忆组装 Agent
- **THEN** Agent 正常工作，且压缩对上层透明

#### Scenario: 压缩不改变原文
- **WHEN** 压缩发生之后检查底层记忆
- **THEN** 原文条数未减少

#### Scenario: 组合顺序不破坏功能
- **WHEN** 以摘要包裹窗口，或以窗口包裹摘要
- **THEN** 两种组合下 `messages()` 均可用，且返回条数不超过各自的上限

