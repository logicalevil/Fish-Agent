package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.constant.LockConstant;
import com.zheng.fishagent.core.lock.MemoryLock;
import com.zheng.fishagent.core.message.message.Message;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 默认记忆视图：把「落库」与「内存上下文」两层合在一起。
 * <p>
 * <ul>
 *   <li>{@link MessageStore} 是真相源，承载完整历史（{@link #history()}）；</li>
 *   <li>{@code contextMessage} 是内存中的上下文缓冲，{@link #context()} 在此之上做裁剪；</li>
 *   <li>读写统一经由 {@link MemoryLock} 保护，保证两层不会撕裂。</li>
 * </ul>
 * 写入顺序是「先落库，成功后再更新内存」：落库失败时内存不会被污染，
 * 下一轮上下文与持久化历史保持一致。
 */
public class DelegateMemoryView extends AbstractChatMemoryView {

    private final MemoryLock memoryLock;
    private final List<Message> contextMessage;
    private final AgentRunScope agentRunScope;
    private final MessageStore messageStore;

    public DelegateMemoryView(List<Message> contextMessage,
                              AgentRunScope agentRunScope,
                              MessageStore messageStore,
                              MemoryLock memoryLock) {
        this(contextMessage, agentRunScope, messageStore, memoryLock, null);
    }

    public DelegateMemoryView(List<Message> contextMessage,
                              AgentRunScope agentRunScope,
                              MessageStore messageStore,
                              MemoryLock memoryLock,
                              ContextTrimmer trimmer) {
        super(trimmer);
        this.contextMessage = Objects.requireNonNull(contextMessage, "contextMessage 不能为空");
        this.agentRunScope = Objects.requireNonNull(agentRunScope, "agentRunScope 不能为空");
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore 不能为空");
        this.memoryLock = memoryLock == null ? MemoryLock.local() : memoryLock;
    }

    /**
     * 便捷构造：使用 {@link InMemoryMessageStore} 与默认裁剪器。
     *
     * @param sessionId 会话标识，多轮对话以它隔离
     */
    public static DelegateMemoryView inMemory(String sessionId) {
        return inMemory(sessionId, null);
    }

    /**
     * 便捷构造：使用 {@link InMemoryMessageStore} 与指定裁剪器。
     *
     * @param sessionId 会话标识
     * @param trimmer   裁剪策略；{@code null} 时使用 {@link DefaultContextTrimmer}
     */
    public static DelegateMemoryView inMemory(String sessionId, ContextTrimmer trimmer) {
        return new DelegateMemoryView(
                new ArrayList<>(),
                new DefaultAgentRunScope(sessionId, null, null, null),
                new InMemoryMessageStore(),
                MemoryLock.local(),
                trimmer);
    }

    @Override
    public void add(Message message) {
        if (message == null) {
            return;
        }
        inLock(() -> {
            // 先同步落库，成功后再更新内存
            messageStore.append(agentRunScope, message);
            contextMessage.add(message);
            return null;
        });
    }

    @Override
    public void clear() {
        inLock(() -> {
            messageStore.clear(agentRunScope);
            contextMessage.clear();
            return null;
        });
    }

    @Override
    public List<Message> history() {
        return inLock(() -> {
            List<StoredMessage> stored = messageStore.load(agentRunScope, 0, 0);
            List<Message> messages = new ArrayList<>(stored.size());
            for (StoredMessage item : stored) {
                messages.add(item.message());
            }
            return List.copyOf(messages);
        });
    }

    @Override
    protected List<Message> rawContext() {
        return inLock(() -> List.copyOf(contextMessage));
    }

    /**
     * 锁超时取 {@link LockConstant#LOCK_TIMEOUT} 秒。
     */
    private <T> T inLock(java.util.function.Supplier<T> action) {
        return memoryLock.execute(agentRunScope, Duration.of(LockConstant.LOCK_TIMEOUT, ChronoUnit.SECONDS), action);
    }
}
