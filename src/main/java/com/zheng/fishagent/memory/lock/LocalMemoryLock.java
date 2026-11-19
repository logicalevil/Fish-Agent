package com.zheng.fishagent.memory.lock;

import com.zheng.fishagent.core.FishAgentException;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * {@link MemoryLock} 的默认实现：按资源标识维护一把 {@link ReentrantLock}，
 * 仅在当前 JVM 内提供互斥。
 * <p>
 * 这是未显式配置锁时框架使用的实现。相比记忆实现各自使用 {@code synchronized}，
 * 它多了「获取锁带超时」的能力，且可被整体替换为跨进程实现。
 * <p>
 * 锁对象按资源标识缓存且<b>不回收</b>：资源标识即会话标识，其数量与并发会话数同阶，
 * 长期存活的映射有助于避免反复创建锁对象；会话量极大的场景应自行实现带淘汰策略的版本。
 */
final class LocalMemoryLock implements MemoryLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public <T> T execute(String resource, Duration timeout, Supplier<T> action) {
        if (action == null) {
            throw new FishAgentException("记忆锁动作不能为空");
        }
        String key = resource == null ? "" : resource;
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());

        boolean acquired;
        try {
            acquired = lock.tryLock(Math.max(0L, timeoutOf(timeout)), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断状态后上报，避免吞掉中断信号
            Thread.currentThread().interrupt();
            throw new FishAgentException("等待记忆锁时被中断：" + key, e);
        }

        if (!acquired) {
            throw FishAgentException.of("获取记忆锁超时（%s ms）：%s", timeoutOf(timeout), key);
        }

        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 超时为 null 或负数时按「不等待，仅尝试一次」处理。 */
    private static long timeoutOf(Duration timeout) {
        if (timeout == null) {
            return 0L;
        }
        try {
            return timeout.toMillis();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public String toString() {
        return "LocalMemoryLock(isDistributed=false, trackedResources=" + locks.size() + ")";
    }
}
