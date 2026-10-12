package com.zheng.fishagent.memory;

import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockedChatMemoryTest {

    @Test
    void 标识应透传底层() {
        LockedChatMemory memory = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), MemoryLock.local());

        assertEquals("s1", memory.id());
    }

    @Test
    void 装饰后语义应与裸记忆一致() {
        ChatMemory bare = new InMemoryChatMemory("s1");
        LockedChatMemory locked = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), MemoryLock.local());

        bare.add(Message.user("你好"));
        locked.add(Message.user("你好"));

        assertEquals(bare.size(), locked.size());
        assertEquals(bare.messages().getFirst().content(), locked.messages().getFirst().content());

        bare.clear();
        locked.clear();
        assertTrue(locked.isEmpty());
    }

    @Test
    void 三种操作都应经过锁() {
        RecordingMemoryLock lock = new RecordingMemoryLock();
        LockedChatMemory memory = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), lock);

        memory.add(Message.user("a"));
        memory.messages();
        memory.clear();

        assertEquals(3, lock.calls.get(), "add / messages / clear 各应进入一次临界区");
        assertEquals(List.of("s1", "s1", "s1"), lock.resources);
    }

    @Test
    void 应复用注入的同一个锁实例() {
        RecordingMemoryLock lock = new RecordingMemoryLock();
        LockedChatMemory memory = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), lock);

        memory.add(Message.user("a"));
        memory.add(Message.user("b"));

        assertSame(lock, memory.lock());
        assertEquals(2, lock.calls.get());
    }

    @Test
    void 重复包裹应被避免() {
        LockedChatMemory first = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), MemoryLock.local());
        LockedChatMemory second = LockedChatMemory.wrap(first, MemoryLock.local());

        assertSame(first, second, "已是 LockedChatMemory 时不应再包一层");
    }

    @Test
    void 重复包裹时不应叠加加锁() {
        RecordingMemoryLock inner = new RecordingMemoryLock();
        LockedChatMemory first = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), inner);

        LockedChatMemory second = LockedChatMemory.wrap(first, new RecordingMemoryLock());
        second.add(Message.user("a"));

        assertEquals(1, inner.calls.get());
    }

    @Test
    void tostring应包含标识与锁类型() {
        LockedChatMemory memory = LockedChatMemory.wrap(new InMemoryChatMemory("s1"), MemoryLock.local());

        String text = memory.toString();

        assertTrue(text.contains("s1"));
        assertTrue(text.contains("LocalMemoryLock"));
    }

    @Test
    void 应暴露被包装的记忆与锁() {
        InMemoryChatMemory bare = new InMemoryChatMemory("s1");
        MemoryLock lock = MemoryLock.local();
        LockedChatMemory memory = LockedChatMemory.wrap(bare, lock);

        assertSame(bare, memory.delegate());
        assertSame(lock, memory.lock());
    }

    @Test
    void 包装文件记忆应仍能持久化(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of("s1", tempDir);
        LockedChatMemory memory = LockedChatMemory.wrap(file, MemoryLock.local());

        memory.add(Message.user("持久化"));

        FileChatMemory reloaded = FileChatMemory.of("s1", tempDir);
        assertEquals(1, reloaded.size());
        assertEquals("持久化", reloaded.messages().getFirst().content());
    }

    /** 记录进入临界区的次数与资源标识的测试替身。 */
    static final class RecordingMemoryLock implements MemoryLock {

        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        private final List<String> resources = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean isDistributed() {
            return false;
        }

        @Override
        public <T> T execute(String resource, Duration timeout, Supplier<T> action) {
            calls.incrementAndGet();
            resources.add(resource);
            return action.get();
        }
    }
}
