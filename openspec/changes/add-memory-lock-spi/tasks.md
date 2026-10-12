## 1. 锁接口与默认实现

- [ ] 1.1 新增 `memory/lock/MemoryLock.java`：`isDistributed()` + `execute(String, Duration, Supplier<T>)`
- [ ] 1.2 在 `MemoryLock` 上提供静态工厂 `local()`
- [ ] 1.3 新增 `memory/lock/LocalMemoryLock.java`：按资源标识维护 `ReentrantLock`（`ConcurrentHashMap` 缓存），`isDistributed()` 返回 false
- [ ] 1.4 实现超时语义：`tryLock(timeout)` 失败抛 `FishAgentException`，消息含资源标识与超时时长
- [ ] 1.5 确保异常路径下锁一定释放（`try/finally`，且仅在持有时 `unlock`）
- [ ] 1.6 确认 `timeout` 为 null 或负数时的行为（按不等待处理，立即尝试一次）

## 2. 装饰器

- [ ] 2.1 新增 `memory/LockedChatMemory.java`：`wrap(ChatMemory, MemoryLock)` 静态工厂
- [ ] 2.2 `add` / `messages` / `clear` 分别包进 `execute`，资源标识用被包装记忆的 `id()`
- [ ] 2.3 `id()` 透传底层
- [ ] 2.4 `toString()` 包含底层标识与锁类型（便于排查装饰链）
- [ ] 2.5 若传入的已是 `LockedChatMemory`，避免重复包裹
- [ ] 2.6 构造时对 null 锁与 null 记忆做校验（快速失败）

## 3. 记忆实现接入锁

- [ ] 3.1 改造 `FileChatMemory`：移除方法级 `synchronized`，改为经注入的 `MemoryLock` 访问内部 cache 与文件
- [ ] 3.2 `FileChatMemory` 新增接收 `MemoryLock` 的构造重载；既有签名保留并回退 `MemoryLock.local()`
- [ ] 3.3 改造 `InMemoryChatMemory`：接收可选的 `MemoryLock`，默认 `local()`，保留 `CopyOnWriteArrayList`
- [ ] 3.4 确认 `MessageWindowChatMemory` 与 `LockedChatMemory` 两种嵌套顺序都能工作
- [ ] 3.5 确保所有既有构造签名可用，不产生编译期破坏

## 4. Agent 与门面接入

- [ ] 4.1 `ReActAgent.Builder` 新增 `memoryLock(MemoryLock)`，默认 `MemoryLock.local()`
- [ ] 4.2 `ReActAgent` 构造时用 `LockedChatMemory.wrap` 包裹配置的记忆（已包裹则不重复）
- [ ] 4.3 `FishAgent.Builder` 新增 `memoryLock(MemoryLock)`，透传给 `ReActAgent`
- [ ] 4.4 传入 null 时回退默认，不抛异常
- [ ] 4.5 未配置锁时，`memory()` 的语义与改动前完全一致（回归验证）

## 5. 测试

- [ ] 5.1 `LocalMemoryLockTest`：同一资源并发互斥；不同资源互不阻塞；超时抛 `FishAgentException`；动作抛异常时锁仍释放；`local()` 每次返回新实例但语义一致
- [ ] 5.2 `LockedChatMemoryTest`：`id()` 透传；`add` / `messages` / `clear` 均经过锁（用计数锁验证）；装饰后语义与裸记忆一致；重复包裹被避免
- [ ] 5.3 `MemoryLockCompositionTest`：两种嵌套顺序行为一致
- [ ] 5.4 改造 `FileChatMemoryTest`：验证移除 `synchronized` 后并发写入仍正确
- [ ] 5.5 新增 `RecordingMemoryLock` 测试替身：记录 `execute` 的调用与资源标识
- [ ] 5.6 回归验证：未配置锁时既有测试全部通过
- [ ] 5.7 确认全套测试仍不依赖网络与真实模型

## 6. 文档

- [ ] 6.1 README 新增「记忆并发保护」章节：默认行为、如何替换为跨进程实现、`isDistributed()` 的含义
- [ ] 6.2 README 写明给使用者的三条注意事项：复用已有 Redis 客户端、租约 > 临界区耗时、公平锁的续期风险
- [ ] 6.3 更新 `openspec/project.md`（若其模块划分描述受影响）

## 7. 归档

- [ ] 7.1 `openspec validate add-memory-lock-spi`
- [ ] 7.2 确认所有 Scenario 有对应测试
- [ ] 7.3 `openspec archive add-memory-lock-spi`
