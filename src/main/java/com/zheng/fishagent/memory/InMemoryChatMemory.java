package com.zheng.fishagent.memory;

import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.message.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内记忆，默认实现。
 * <p>
 * 底层用 {@link CopyOnWriteArrayList} 存储，读取（即构造模型上下文）无需加锁，
 * 适合「读多写少」的对话场景；进程退出后数据即丢失，需持久化请用
 * {@link FileChatMemory}。
 * <p>
 * 并发保护经由注入的 {@link MemoryLock} 表达，默认使用 {@link MemoryLock#local()}。
 * 需要说明的是：底层 COW 结构本身已保证单条读写的安全，此处接入锁的意义在于
 * <b>语义统一与可替换性</b>——使用者可以将它换成跨进程实现；在默认本地锁下，
 * 实际的互斥效果来自 COW 而非锁。
 */
public class InMemoryChatMemory implements ChatMemory {

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

    private final String id;
    private final List<Message> store = new CopyOnWriteArrayList<>();
    private final MemoryLock lock;

    /**
     * 以随机生成的会话标识与默认锁创建。
     */
    public InMemoryChatMemory() {
        this("session-" + UUID.randomUUID());
    }

    /**
     * 以指定会话标识创建；{@code null} 或空白时自动生成随机标识。使用默认锁。
     */
    public InMemoryChatMemory(String id) {
        this(id, MemoryLock.local());
    }

    /**
     * 以指定会话标识与指定锁创建。{@code lock} 为 {@code null} 时回退为默认本地锁。
     */
    public InMemoryChatMemory(String id, MemoryLock lock) {
        this.id = id == null || id.isBlank() ? "session-" + UUID.randomUUID() : id;
        this.lock = lock == null ? MemoryLock.local() : lock;
    }

    @Override
    public String id() {
        return id;
    }

    /**
     * 本实现使用的锁，供诊断与测试观察。
     */
    public MemoryLock lock() {
        return lock;
    }

    @Override
    public void add(Message message) {
        if (message == null) {
            return;
        }
        lock.execute(id, LOCK_TIMEOUT, () -> {
            store.add(message);
            return null;
        });
    }

    @Override
    public List<Message> messages() {
        return lock.execute(id, LOCK_TIMEOUT, () -> new ArrayList<>(store));
    }

    @Override
    public void clear() {
        lock.execute(id, LOCK_TIMEOUT, () -> {
            store.clear();
            return null;
        });
    }
}
