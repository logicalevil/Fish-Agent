package com.zheng.fishagent.memory.lock;

import com.zheng.fishagent.core.FishAgentException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMemoryLockTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void 默认工厂应返回非分布式实现() {
        MemoryLock lock = MemoryLock.local();

        assertFalse(lock.isDistributed());
    }

    @Test
    void 应返回动作的执行结果() {
        MemoryLock lock = MemoryLock.local();

        String result = lock.execute("s1", WAIT, () -> "答案");

        assertEquals("答案", result);
    }

    @Test
    void 同一资源的并发访问应互斥() throws Exception {
        MemoryLock lock = MemoryLock.local();
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Runnable task = () -> {
            try {
                start.await();
                lock.execute("same", WAIT, () -> {
                    if (inside.incrementAndGet() > 1) {
                        overlapped.set(true);
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        Thread a = new Thread(task);
        Thread b = new Thread(task);
        a.start();
        b.start();
        start.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertFalse(overlapped.get(), "同一资源的临界区不应重叠");
    }

    @Test
    void 不同资源应互不阻塞() throws Exception {
        MemoryLock lock = MemoryLock.local();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        Thread first = new Thread(() -> lock.execute("a", WAIT, () -> {
            firstInside.countDown();
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        first.start();
        assertTrue(firstInside.await(10, TimeUnit.SECONDS));

        lock.execute("b", Duration.ofMillis(500), () -> {
            secondEntered.set(true);
            return null;
        });

        releaseFirst.countDown();
        first.join(10_000);
        assertTrue(secondEntered.get(), "不同资源不应被阻塞");
    }

    @Test
    void 超时应抛出框架异常且不执行动作() throws Exception {
        MemoryLock lock = MemoryLock.local();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean actionRan = new AtomicBoolean(false);

        Thread holder = new Thread(() -> lock.execute("busy", WAIT, () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));

        FishAgentException error = assertThrows(FishAgentException.class,
                () -> lock.execute("busy", Duration.ofMillis(100), () -> {
                    actionRan.set(true);
                    return null;
                }));

        assertTrue(error.getMessage().contains("超时"));
        assertFalse(actionRan.get(), "取锁失败时动作不应被执行");

        release.countDown();
        holder.join(10_000);
    }

    @Test
    void 动作抛异常时锁应被释放() {
        MemoryLock lock = MemoryLock.local();

        assertThrows(IllegalStateException.class, () -> lock.execute("s1", WAIT, () -> {
            throw new IllegalStateException("boom");
        }));

        // 能再次取到锁即说明前一次已释放
        assertEquals("ok", lock.execute("s1", Duration.ofMillis(100), () -> "ok"));
    }

    @Test
    void 超时为零时不等待而是立即尝试() throws Exception {
        MemoryLock lock = MemoryLock.local();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> lock.execute("busy", WAIT, () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));

        assertThrows(FishAgentException.class, () -> lock.execute("busy", Duration.ZERO, () -> "never"));

        release.countDown();
        holder.join(10_000);
    }

    @Test
    void 超时为null时按不等待处理() throws Exception {
        MemoryLock lock = MemoryLock.local();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> lock.execute("busy", WAIT, () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));

        assertThrows(FishAgentException.class, () -> lock.execute("busy", null, () -> "never"));

        release.countDown();
        holder.join(10_000);
    }

    @Test
    void 动作不能为空() {
        MemoryLock lock = MemoryLock.local();

        FishAgentException error = assertThrows(FishAgentException.class,
                () -> lock.execute("s1", WAIT, null));

        assertTrue(error.getMessage().contains("动作"));
    }

    @Test
    void 资源标识为空时仍可用() {
        MemoryLock lock = MemoryLock.local();

        AtomicReference<String> seen = new AtomicReference<>();
        lock.execute(null, WAIT, () -> {
            seen.set("ran");
            return null;
        });

        assertEquals("ran", seen.get());
    }

    @Test
    void 工厂每次应返回独立实例() {
        MemoryLock a = MemoryLock.local();
        MemoryLock b = MemoryLock.local();

        assertInstanceOf(LocalMemoryLock.class, a);
        assertFalse(a == b, "每次调用应返回新实例");
    }
}
