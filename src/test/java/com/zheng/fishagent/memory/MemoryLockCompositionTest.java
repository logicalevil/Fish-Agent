package com.zheng.fishagent.memory;

import com.zheng.fishagent.core.memory.AgentRunScope;
import com.zheng.fishagent.core.memory.ChatMemory;
import com.zheng.fishagent.core.memory.FileChatMemory;
import com.zheng.fishagent.core.memory.LockedChatMemory;
import com.zheng.fishagent.core.memory.MessageWindowChatMemory;
import com.zheng.fishagent.core.lock.MemoryLock;
import com.zheng.fishagent.core.message.message.Message;
import com.zheng.fishagent.testkit.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLockCompositionTest {

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
    void 锁在窗口外层时应同时生效(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of(tempDir, scope("c1"));
        ChatMemory memory = LockedChatMemory.wrap(
                MessageWindowChatMemory.windowing(file, 3), MemoryLock.local(), scope("c1"));

        for (int i = 1; i <= 5; i++) {
            memory.add(Messages.user("q" + i));
        }

        List<Message> window = memory.messages();
        assertEquals(3, window.size(), "窗口裁剪应生效");
        assertEquals("q3", textOf(window.getFirst()));
        assertEquals(5, file.size(), "锁不应影响底层全量存储");
    }

    @Test
    void 窗口在锁外层时应同时生效(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of(tempDir, scope("c2"));
        ChatMemory memory = MessageWindowChatMemory.windowing(
                LockedChatMemory.wrap(file, MemoryLock.local(), scope("c2")), 3);

        for (int i = 1; i <= 5; i++) {
            memory.add(Messages.user("q" + i));
        }

        List<Message> window = memory.messages();
        assertEquals(3, window.size(), "窗口裁剪应生效");
        assertEquals("q3", textOf(window.getFirst()));
        assertEquals(5, file.size());
    }

    @Test
    void 两种嵌套顺序结果应一致(@TempDir Path tempDir) {
        FileChatMemory fileA = FileChatMemory.of(tempDir, scope("a"));
        ChatMemory outerLock = LockedChatMemory.wrap(
                MessageWindowChatMemory.windowing(fileA, 3), new CountingMemoryLock(), scope("a"));

        FileChatMemory fileB = FileChatMemory.of(tempDir, scope("b"));
        ChatMemory outerWindow = MessageWindowChatMemory.windowing(
                LockedChatMemory.wrap(fileB, new CountingMemoryLock(), scope("b")), 3);

        for (int i = 1; i <= 5; i++) {
            outerLock.add(Messages.user("q" + i));
            outerWindow.add(Messages.user("q" + i));
        }

        assertEquals(outerLock.messages().size(), outerWindow.messages().size());
        assertEquals(
                outerLock.messages().stream().map(ChatMemoryCompositionTestSupport::textOf).toList(),
                outerWindow.messages().stream().map(ChatMemoryCompositionTestSupport::textOf).toList());
    }

    @Test
    void 未配置锁时并发写入应被串行化(@TempDir Path tempDir) throws Exception {
        // 默认构造回退 MemoryLock.local()
        FileChatMemory memory = FileChatMemory.of(tempDir, scope("concurrent"));
        int threads = 8;
        int perThread = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            memory.add(Messages.user("t" + id + "-" + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, memory.size(), "不应丢失写入");
    }

    /** 计数用的最简锁实现。 */
    private static final class CountingMemoryLock implements MemoryLock {

        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public boolean isDistributed() {
            return false;
        }

        @Override
        public <T> T execute(AgentRunScope agentRunScope, Duration timeout, Supplier<T> action) {
            calls.incrementAndGet();
            return action.get();
        }
    }

    /** 供方法引用使用的文本提取工具。 */
    private static final class ChatMemoryCompositionTestSupport {
        private static String textOf(Message message) {
            return MemoryLockCompositionTest.textOf(message);
        }
    }
}
