package com.zheng.fishagent.memory;

import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.message.Message;
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

    @Test
    void 锁在窗口外层时应同时生效(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of("c1", tempDir);
        ChatMemory memory = LockedChatMemory.wrap(
                MessageWindowChatMemory.windowing(file, 3), MemoryLock.local());

        for (int i = 1; i <= 5; i++) {
            memory.add(Message.user("q" + i));
        }

        List<Message> window = memory.messages();
        assertEquals(3, window.size(), "窗口裁剪应生效");
        assertEquals("q3", window.getFirst().content());
        assertEquals(5, file.size(), "锁不应影响底层全量存储");
    }

    @Test
    void 窗口在锁外层时应同时生效(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of("c2", tempDir);
        ChatMemory memory = MessageWindowChatMemory.windowing(
                LockedChatMemory.wrap(file, MemoryLock.local()), 3);

        for (int i = 1; i <= 5; i++) {
            memory.add(Message.user("q" + i));
        }

        List<Message> window = memory.messages();
        assertEquals(3, window.size(), "窗口裁剪应生效");
        assertEquals("q3", window.getFirst().content());
        assertEquals(5, file.size());
    }

    @Test
    void 两种嵌套顺序结果应一致(@TempDir Path tempDir) {
        FileChatMemory fileA = FileChatMemory.of("a", tempDir);
        ChatMemory outerLock = LockedChatMemory.wrap(
                MessageWindowChatMemory.windowing(fileA, 3), new CountingMemoryLock());

        FileChatMemory fileB = FileChatMemory.of("b", tempDir);
        ChatMemory outerWindow = MessageWindowChatMemory.windowing(
                LockedChatMemory.wrap(fileB, new CountingMemoryLock()), 3);

        for (int i = 1; i <= 5; i++) {
            outerLock.add(Message.user("q" + i));
            outerWindow.add(Message.user("q" + i));
        }

        assertEquals(outerLock.messages().size(), outerWindow.messages().size());
        assertEquals(
                outerLock.messages().stream().map(Message::content).toList(),
                outerWindow.messages().stream().map(Message::content).toList());
    }

    @Test
    void 未配置锁时并发写入应被串行化(@TempDir Path tempDir) throws Exception {
        // 默认构造回退 MemoryLock.local()
        FileChatMemory memory = FileChatMemory.of("concurrent", tempDir);
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
                            memory.add(Message.user("t" + id + "-" + i));
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
        public <T> T execute(String resource, Duration timeout, Supplier<T> action) {
            calls.incrementAndGet();
            return action.get();
        }
    }
}
