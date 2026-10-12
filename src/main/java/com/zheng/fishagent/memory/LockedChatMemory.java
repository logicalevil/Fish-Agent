package com.zheng.fishagent.memory;

import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.message.Message;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 记忆的加锁装饰器：把每次 {@link ChatMemory} 访问都包进 {@link MemoryLock} 的临界区。
 * <p>
 * 与 {@link MessageWindowChatMemory} 同属装饰器，两者可任意嵌套：
 * <pre>{@code
 * ChatMemory memory = LockedChatMemory.wrap(
 *         MessageWindowChatMemory.windowing(FileChatMemory.of("s1"), 20),
 *         myCrossProcessLock);
 * }</pre>
 * <p>
 * 装饰后 {@link ChatMemory} 的语义不变——仅访问路径多了一层互斥，
 * {@link #id()} 原样透传底层标识。
 */
public final class LockedChatMemory implements ChatMemory {

    /** 获取锁的默认等待时间。跨进程实现下需覆盖网络往返，取值偏保守。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final ChatMemory delegate;
    private final MemoryLock lock;
    private final Duration timeout;

    private LockedChatMemory(ChatMemory delegate, MemoryLock lock, Duration timeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.lock = Objects.requireNonNull(lock, "lock 不能为空");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /**
     * 用指定锁包裹一份记忆，使用默认超时。
     *
     * @param delegate 被包装的记忆
     * @param lock     互斥实现；跨进程场景传入自行实现的 {@link MemoryLock}
     */
    public static LockedChatMemory wrap(ChatMemory delegate, MemoryLock lock) {
        return wrap(delegate, lock, DEFAULT_TIMEOUT);
    }

    /**
     * 用指定锁包裹一份记忆。
     * <p>
     * 若 {@code delegate} 已经是 {@link LockedChatMemory}，则原样返回，避免嵌套加锁。
     */
    public static LockedChatMemory wrap(ChatMemory delegate, MemoryLock lock, Duration timeout) {
        if (delegate instanceof LockedChatMemory locked) {
            return locked;
        }
        return new LockedChatMemory(delegate, lock, timeout);
    }

    /**
     * 本装饰器使用的锁实现，供诊断与测试观察。
     */
    public MemoryLock lock() {
        return lock;
    }

    /**
     * 被包装的记忆。
     */
    public ChatMemory delegate() {
        return delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public void add(Message message) {
        lock.execute(id(), timeout, () -> {
            delegate.add(message);
            return null;
        });
    }

    @Override
    public List<Message> messages() {
        return lock.execute(id(), timeout, delegate::messages);
    }

    @Override
    public void clear() {
        lock.execute(id(), timeout, () -> {
            delegate.clear();
            return null;
        });
    }

    @Override
    public String toString() {
        return "LockedChatMemory(id=" + id()
                + ", lock=" + lock.getClass().getSimpleName()
                + ", isDistributed=" + lock.isDistributed()
                + ", delegate=" + delegate.getClass().getSimpleName() + ")";
    }
}
