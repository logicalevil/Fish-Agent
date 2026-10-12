## ADDED Requirements

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

`InMemoryChatMemory` SHALL 用线程安全的进程内集合存储消息，且当 Agent 组装时未指定记忆，它 SHALL 作为默认记忆。`InMemoryChatMemory(String id)` SHALL 通过生成 `session-<uuid>` 标识来拒绝 null 或空白 id。

#### Scenario: 裸 Agent 的默认记忆
- **WHEN** 以 `ReActAgent.builder().model(...).build()` 组装且未指定记忆
- **THEN** `memory()` 返回 `InMemoryChatMemory`

#### Scenario: 空白会话标识生成 id
- **WHEN** 构造 `new InMemoryChatMemory("  ")`
- **THEN** `id()` 以 `session-` 开头

#### Scenario: 并发追加是安全的
- **WHEN** 从多个线程追加消息
- **THEN** 不发生并发修改异常，且所有消息都被保留

### Requirement: 滑动窗口记忆在保留系统消息的前提下限制上下文

`MessageWindowChatMemory` SHALL 装饰另一个 `ChatMemory`，其 `messages()` SHALL 最多返回 `maxMessages` 条非 SYSTEM 消息，同时 SHALL 始终包含全部 SYSTEM 消息，并 SHALL 通过 `fullHistory()` 暴露未被裁剪的完整历史。

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

### Requirement: 文件记忆以 JSONL 持久化会话

`FileChatMemory` SHALL 向 `<目录>/<安全会话标识>.jsonl` 每行追加一个 JSON 对象，默认目录为相对于工作目录的 `.fishagent/sessions`，SHALL 在构造时重新加载已有内容，并 SHALL 通过 `file()` 暴露解析后的路径。

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
