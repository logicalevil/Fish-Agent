# conversation-memory Specification

## MODIFIED Requirements

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

### Requirement: 记忆的并发保护由 MemoryLock 承担

框架内所有 `ChatMemory` 实现的并发保护 SHALL 经由 `MemoryLock`，SHALL NOT 在实现内部直接使用 `synchronized`。未显式配置锁时，实现 SHALL 使用不引入额外依赖的本地实现，且行为与既有一致。

#### Scenario: 未配置锁时默认可用
- **WHEN** 组装任意 `ChatMemory` 实现且不配置锁
- **THEN** 该记忆可正常工作，且不因此新增运行时依赖

#### Scenario: 配置跨进程锁后互斥范围扩大
- **WHEN** 为记忆配置 `isDistributed()` 为 true 的锁
- **THEN** 对同一资源标识的并发访问在多个实例之间互斥

#### Scenario: 锁实现可被替换而不改接口
- **WHEN** 用另一 `MemoryLock` 实现重新组装同一记忆类型
- **THEN** 无需修改该记忆类或其实现
