# memory-locking Specification

## ADDED Requirements

### Requirement: MemoryLock 是记忆层唯一的互斥扩展点

`MemoryLock` SHALL 是声明 `boolean isDistributed()` 与 `<T> T execute(String resource, Duration timeout, Supplier<T> action)` 的接口。框架内所有 `ChatMemory` 实现的并发保护 SHALL 经由它，SHALL NOT 在记忆实现内部直接依赖 `synchronized` 或底层集合的线程安全语义。

#### Scenario: 接口暴露两个抽象方法
- **WHEN** 检查 `MemoryLock` 的声明
- **THEN** 它恰好暴露 `isDistributed()` 与 `execute(String, Duration, Supplier)` 两个抽象方法

#### Scenario: 临界区的释放由实现配对
- **WHEN** 通过 `execute` 执行的动作抛出异常
- **THEN** 锁仍被释放，且异常原样向上传播

#### Scenario: 动作被真正执行
- **WHEN** 通过 `execute` 传入一个返回值的动作
- **THEN** 返回值为该动作的执行结果

### Requirement: 锁实现自我描述是否跨进程有效

`MemoryLock.isDistributed()` SHALL 返回该实现是否在多个 JVM 之间提供互斥：返回 `false` 表示仅在当前进程内有效，返回 `true` 表示跨进程有效。该值 SHALL 由实现自行声明，SHALL NOT 由框架通过配置推断。

#### Scenario: 内置本地实现返回 false
- **WHEN** 检查 `MemoryLock.local()` 实例的 `isDistributed()`
- **THEN** 返回 `false`

#### Scenario: 自定义实现自行声明
- **WHEN** 用户实现的锁返回 `true`
- **THEN** 框架不对该声明做校验或覆盖，原样透传

### Requirement: 内置本地实现基于 ReentrantLock

框架 SHALL 通过静态工厂 `MemoryLock.local()` 提供唯一的默认实现。该实现 SHALL 按资源标识维护 `ReentrantLock`，SHALL 在 `timeout` 内未取得锁时抛出 `FishAgentException`，且 SHALL NOT 引入 Jackson 之外的运行时依赖。

#### Scenario: 同一资源的并发访问互斥
- **WHEN** 两个线程以同一资源标识进入 `execute`
- **THEN** 两者的临界区不重叠

#### Scenario: 不同资源互不阻塞
- **WHEN** 两个线程以不同资源标识进入 `execute`
- **THEN** 两者可以同时处于临界区内

#### Scenario: 超时抛出框架异常
- **WHEN** 某资源已被占用，另一线程以极短超时进入 `execute`
- **THEN** 抛出 `FishAgentException`，且传入的动作不被执行

#### Scenario: 不新增运行时依赖
- **WHEN** 检查核心模块的运行时依赖
- **THEN** 仍只有 Jackson

### Requirement: 未配置锁时使用内置本地实现

`ReActAgent` 与 `FishAgent` 的构建器 SHALL 提供 `memoryLock(MemoryLock)` 配置项。未配置时 SHALL 回退到 `MemoryLock.local()`，因此不配置锁的既有用法 SHALL 保持可用，行为与变更前等价。

#### Scenario: 未配置锁时开箱可用
- **WHEN** 以 `ReActAgent.builder().model(...).build()` 组装且未配置锁
- **THEN** 记忆可正常读写，且 `isDistributed()` 为 `false`

#### Scenario: 配置自定义锁后生效
- **WHEN** 以自定义 `MemoryLock` 组装 Agent 并执行一次 run
- **THEN** 该锁的 `execute` 被调用，且被保护的资源标识等于记忆的 `id()`

#### Scenario: 传入 null 锁回退默认
- **WHEN** 调用 `memoryLock(null)`
- **THEN** 不抛异常，并使用内置本地实现

### Requirement: LockedChatMemory 装饰后接口语义不变

`LockedChatMemory` SHALL 实现 `ChatMemory`，把 `add`、`messages`、`clear` 三次调用分别包在 `MemoryLock.execute` 内。其 `id()` SHALL 返回被包装记忆的标识。包装后 SHALL NOT 改变 `ChatMemory` 的任何既有语义。

#### Scenario: 装饰后标识透传
- **WHEN** 用 `LockedChatMemory.wrap(delegate, lock)` 包装一个标识为 `s1` 的记忆
- **THEN** 包装结果的 `id()` 为 `s1`

#### Scenario: 读取操作也在锁内
- **WHEN** 调用装饰后的 `messages()`
- **THEN** 该调用经由 `MemoryLock.execute`，而非直接委托

#### Scenario: 锁被复用而非每次新建
- **WHEN** 对同一装饰实例连续调用多次 `add`
- **THEN** 每次都使用构造时注入的同一个 `MemoryLock` 实例

#### Scenario: 已装饰的记忆不被重复包裹
- **WHEN** 把一个已是 `LockedChatMemory` 的实例传给再一层 `wrap`
- **THEN** 不产生嵌套加锁

### Requirement: 锁与装饰器可自由组合

`LockedChatMemory` SHALL 可包装任意 `ChatMemory` 实现，包括其它装饰器。`MessageWindowChatMemory` 与 `LockedChatMemory` SHALL 可任意顺序嵌套，且两者的效果不因顺序而丢失。

#### Scenario: 与窗口装饰器嵌套
- **WHEN** 以 `wrap(windowing(fileMemory, 20), lock)` 组合
- **THEN** 读取时既受窗口裁剪，也经过锁

#### Scenario: 反向嵌套同样可用
- **WHEN** 以 `windowing(wrap(fileMemory, lock), 20)` 组合
- **THEN** 实例构造成功，且 `messages()` 仍返回裁剪后的结果

### Requirement: 分布式实现由使用者提供

框架 SHALL NOT 提供跨进程的 `MemoryLock` 实现，SHALL NOT 依赖 Redisson 或任何分布式协调组件。使用者需要跨实例互斥时，SHALL 自行实现 `MemoryLock` 并声明 `isDistributed()` 为 true。框架 SHALL NOT 对该声明做校验、拦截或告警。

#### Scenario: 核心不依赖分布式组件
- **WHEN** 检查核心模块的依赖树
- **THEN** 不含 Redisson、Jedis、ZooKeeper 或同类组件

#### Scenario: 自定义分布式实现可直接接入
- **WHEN** 使用者实现 `MemoryLock` 并在构建时通过 `memoryLock(...)` 注入
- **THEN** 框架直接使用该实现，不要求任何额外注册或适配

#### Scenario: 框架不校验实现真伪
- **WHEN** 使用者的实现声明 `isDistributed()` 为 true 但实际未提供跨进程互斥
- **THEN** 框架照常运行，不抛异常、不记录告警——该声明的正确性由使用者负责
