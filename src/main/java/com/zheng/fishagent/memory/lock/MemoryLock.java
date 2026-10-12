package com.zheng.fishagent.memory.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 记忆层的互斥扩展点。
 * <p>
 * 框架内所有 {@link com.zheng.fishagent.memory.ChatMemory} 实现的并发保护都经由本接口，
 * 记忆实现自身不再依赖 {@code synchronized} 或底层集合的线程安全语义。
 * <p>
 * 框架只提供基于 {@link java.util.concurrent.locks.ReentrantLock} 的本地实现
 * （{@link #local()}），它仅在<b>当前 JVM 内</b>提供互斥。当同一份记忆会被多个实例
 * 并发访问时，需要自行实现本接口并提供跨进程互斥，然后在构建 Agent 时通过
 * {@code memoryLock(...)} 注入。
 * <p>
 * <b>实现注意事项</b>：
 * <ul>
 *   <li>{@link #execute} 必须在异常路径下也释放锁（用 {@code try/finally}）；</li>
 *   <li>获取锁失败或超时应抛 {@link com.zheng.fishagent.core.FishAgentException}，
 *       不要静默跳过临界区；</li>
 *   <li>若使用带租约的分布式锁，租约时长必须大于单次临界区的最大耗时，
 *       否则锁可能在临界区执行中途失效。</li>
 * </ul>
 */
public interface MemoryLock {

    /**
     * 该实现是否在多个 JVM 之间提供互斥。
     * <p>
     * 返回 {@code false} 表示仅在当前进程内有效（多个实例并发访问同一份记忆时不安全）；
     * 返回 {@code true} 表示跨进程有效。
     * <p>
     * 该值由实现自行声明，框架<b>不做校验</b>——声明的正确性由实现者负责。
     */
    boolean isDistributed();

    /**
     * 在锁内执行动作。资源的获取与释放由实现负责配对，调用方无需关心。
     *
     * @param resource 被保护的资源标识，通常是 {@link com.zheng.fishagent.memory.ChatMemory#id()}
     * @param timeout  获取锁的最长等待时间
     * @param action   临界区内的动作
     * @param <T>      动作的返回类型
     * @return 动作的执行结果
     * @throws com.zheng.fishagent.core.FishAgentException 获取锁失败或超时；此时动作不会被执行
     */
    <T> T execute(String resource, Duration timeout, Supplier<T> action);

    /**
     * 默认实现：按资源标识维护 {@link java.util.concurrent.locks.ReentrantLock}，
     * 仅在当前进程内互斥。
     * <p>
     * 未显式配置锁时，框架使用本方法返回的实现，因此不配锁的用法开箱可用。
     * 它不引入 Jackson 之外的运行时依赖。
     */
    static MemoryLock local() {
        return new LocalMemoryLock();
    }
}
