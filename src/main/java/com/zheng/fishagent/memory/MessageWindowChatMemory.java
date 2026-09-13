package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 滑动窗口记忆装饰器：底层保留全部历史，但返回上下文时只取最近 N 条，
 * 并始终保留 SYSTEM 消息，避免长对话撑爆上下文窗口。
 */
public final class MessageWindowChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final int maxMessages;

    private MessageWindowChatMemory(ChatMemory delegate, int maxMessages) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.maxMessages = Math.max(1, maxMessages);
    }

    public static MessageWindowChatMemory of(int maxMessages) {
        return new MessageWindowChatMemory(new InMemoryChatMemory(), maxMessages);
    }

    public static MessageWindowChatMemory of(String id, int maxMessages) {
        return new MessageWindowChatMemory(new InMemoryChatMemory(id), maxMessages);
    }

    /**
     * 在已有记忆之上套一层窗口裁剪。
     */
    public static MessageWindowChatMemory windowing(ChatMemory delegate, int maxMessages) {
        return new MessageWindowChatMemory(delegate, maxMessages);
    }

    public int maxMessages() {
        return maxMessages;
    }

    /**
     * 未被裁剪的完整历史。
     */
    public List<Message> fullHistory() {
        return delegate.messages();
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public void add(Message message) {
        delegate.add(message);
    }

    @Override
    public List<Message> messages() {
        List<Message> all = delegate.messages();
        List<Message> systems = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message message : all) {
            if (message.role() == Role.SYSTEM) {
                systems.add(message);
            } else {
                others.add(message);
            }
        }

        List<Message> window = new ArrayList<>(systems);
        if (others.size() <= maxMessages) {
            window.addAll(others);
            return window;
        }
        window.addAll(others.subList(others.size() - maxMessages, others.size()));
        return window;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
