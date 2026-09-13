package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.Message;

import java.util.List;

/**
 * 对话记忆抽象。Agent 每轮结束都会把消息写入记忆，
 * 下一次推理时再按需取回，从而实现多轮对话。
 */
public interface ChatMemory {

    /**
     * 会话标识，可理解为多轮对话的 session id。
     */
    String id();

    void add(Message message);

    default void addAll(List<Message> messages) {
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            add(message);
        }
    }

    /**
     * 返回用于构造模型上下文的全部消息（按时间顺序）。
     */
    List<Message> messages();

    void clear();

    default int size() {
        return messages().size();
    }

    default boolean isEmpty() {
        return messages().isEmpty();
    }
}
