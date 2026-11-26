package com.zheng.fishagent.memory;

import com.zheng.fishagent.core.memory.AgentRunScope;
import com.zheng.fishagent.core.memory.ChatMemory;
import com.zheng.fishagent.core.memory.FileChatMemory;
import com.zheng.fishagent.core.memory.InMemoryChatMemory;
import com.zheng.fishagent.core.memory.LockedChatMemory;
import com.zheng.fishagent.core.lock.MemoryLock;
import com.zheng.fishagent.core.message.message.Message;
import com.zheng.fishagent.testkit.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockedChatMemoryTest {

    private static AgentRunScope scope(String sessionId) {
        return () -> sessionId;
    }

    /** 消息正文的纯文本，便于断言。 */
    private static String textOf(Message message) {
        StringBuilder sb = new StringBuilder();
        message.contentList().forEach(content -> sb.append(content.text()));
        return sb.toString();
    }

    @Test
    void 装饰后语义应与裸记忆一致() {
        ChatMemory bare = new InMemoryChatMemory("s1", scope("s1"));
        LockedChatMemory locked = LockedChatMemory.wrap(
                new InMemoryChatMemory("s1", scope("s1")), MemoryLock.local(), scope("s1"));

        bare.add(Messages.user("你好"));
        locked.add(Messages.user("你好"));

        assertEquals(bare.size(), locked.size());
        assertEquals(textOf(bare.messages().getFirst()), textOf(locked.messages().getFirst()));

        bare.clear();
        locked.clear();
        assertTrue(locked.isEmpty());
    }

    @Test
    void 三种操作都应经过锁() {
        RecordingMemoryLock lock = new RecordingMemoryLock();
        LockedChatMemory memory = LockedChatMemory.wrap(
                new InMemoryChatMemory("s1", scope("s1")), lock, scope("s1"));

        memory.add(Messages.user("a"));
        memory.messages();
        memory.clear();

        assertEquals(3, lock.calls.get(), "add / messages / clear 各应进入一次临界区");
        assertEquals(List.of("s1", "s1", "s1"), lock.resources);
    }

    @Test
    void 应复用注入的同一个锁实例() {
        RecordingMemoryLock lock = new RecordingMemoryLock();
        LockedChatMemory memory = LockedChatMemory.wrap(
                new InMemoryChatMemory("s1", scope("s1")), lock, scope("s1"));

        memory.add(Messages.user("a"));
        memory.add(Messages.user("b"));

        assertSame(lock, memory.lock());
        assertEquals(2, lock.calls.get());
    }

    @Test
    void 重复包裹应被避免() {
        LockedChatMemory first = LockedChatMemory.wrap(
                new InMemoryChatMemory("s1", scope("s1")), MemoryLock.local(), scope("s1"));
        LockedChatMemory second = LockedChatMemory.wrap(first, MemoryLock.local(), scope("s1"));

        assertSame(first, second, "已是 LockedChatMemory 时不应再包一层");
    }

    @Test
    void 重复包裹时不应叠加加锁() {
        RecordingMemoryLock inner = new RecordingMemoryLock();
        LockedChatMemory first = LockedChatMemory.wrap(
                new InMemoryChatMemory("s1", scope("s1")), inner, scope("s1"));

        LockedChatMemory second = LockedChatMemory.wrap(first, new RecordingMemoryLock(), scope("s1"));
        second.add(Messages.user("a"));

        assertEquals(1, inner.calls.get());
    }

    @Test
    void 应暴露被包装的记忆与锁() {
        InMemoryChatMemory bare = new InMemoryChatMemory("s1", scope("s1"));
        MemoryLock lock = MemoryLock.local();
        LockedChatMemory memory = LockedChatMemory.wrap(bare, lock, scope("s1"));

        assertSame(bare, memory.delegate());
        assertSame(lock, memory.lock());
    }

    @Test
    void 包装文件记忆应仍能持久化(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of(tempDir, scope("s1"));
        LockedChatMemory memory = LockedChatMemory.wrap(file, MemoryLock.local(), scope("s1"));

        memory.add(Messages.user("持久化"));

        FileChatMemory reloaded = FileChatMemory.of(tempDir, scope("s1"));
        assertEquals(1, reloaded.size());
        assertEquals("持久化", textOf(reloaded.messages().getFirst()));
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
        public <T> T execute(AgentRunScope agentRunScope, Duration timeout, Supplier<T> action) {
            calls.incrementAndGet();
            resources.add(agentRunScope.toKey());
            return action.get();
        }
    }
}
