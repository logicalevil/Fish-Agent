package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内消息存储，{@link MessageStore} 的默认实现。
 * <p>
 * 按 {@link AgentRunScope#toKey()} 隔离会话；每个会话维护单调递增的序号。
 * 序号取自一条全局计数器，因此不同会话之间的序号不保证连续，
 * 但同一会话内严格递增，足以支撑增量加载（{@code afterSeq}）。
 * <p>
 * 并发：内部结构本身是线程安全的（{@link ConcurrentHashMap} + {@link AtomicLong}），
 * 但「取号 + 追加」作为一个整体仍需外层 {@link com.zheng.fishagent.core.lock.MemoryLock}
 * 保护，否则读取方可能看到空洞。锁由 {@link DelegateMemoryView} 统一持有。
 */
public final class InMemoryMessageStore implements MessageStore {

    /** 会话键 → 该会话的消息列表（按 seq 升序） */
    private final Map<String, List<StoredMessage>> sessions = new ConcurrentHashMap<>();

    /** 全局序号发生器 */
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Long append(AgentRunScope agentRunScope, Message message) {
        if (message == null) {
            return null;
        }
        long seq = sequence.incrementAndGet();
        sessions.computeIfAbsent(agentRunScope.toKey(), key -> new ArrayList<>())
                .add(new StoredMessage(seq, message));
        return seq;
    }

    @Override
    public List<StoredMessage> load(AgentRunScope agentRunScope, long afterSeq, int limit) {
        List<StoredMessage> stored = sessions.get(agentRunScope.toKey());
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<StoredMessage> result = new ArrayList<>();
        for (StoredMessage item : stored) {
            // 列表按 seq 升序，一旦落后于游标就可以继续；这里用判断而非 break 是为了容忍乱序写入
            if (item.seq() <= afterSeq) {
                continue;
            }
            result.add(item);
            if (limit > 0 && result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    @Override
    public void clear(AgentRunScope agentRunScope) {
        sessions.remove(agentRunScope.toKey());
    }
}
