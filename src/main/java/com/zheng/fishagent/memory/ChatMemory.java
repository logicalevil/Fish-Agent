package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.Message;

import java.util.List;

/**
 * 对话记忆抽象。Agent 每轮结束都会把消息写入记忆，
 * 下一次推理时再按需取回，从而实现多轮对话。
 * <p>
 * 框架自带四种实现，可按需组合：
 * <ul>
 *   <li>{@link InMemoryChatMemory} —— 默认，进程内存储；</li>
 *   <li>{@link MessageWindowChatMemory} —— 窗口裁剪，只保留最近若干条参与上下文；</li>
 *   <li>{@link SummarizingChatMemory} —— 摘要压缩，把早期消息压成一条摘要，避免信息硬丢失；</li>
 *   <li>{@link FileChatMemory} —— JSONL 文件持久化。</li>
 * </ul>
 * 并发保护统一经由 {@link com.zheng.fishagent.memory.lock.MemoryLock}。
 * <p>
 * <b>实现提示</b>：{@link #messages()} 的返回值就是「参与模型上下文的消息列表」，
 * 实现方可以在此做裁剪或压缩，而把完整历史保留在内部（参见 {@code fullHistory()}）。
 * 裁剪与压缩 SHALL NOT 删除底层原文。
 */
public interface ChatMemory {

    /**
     * 会话标识，可理解为多轮对话的 session id。
     */
    String id();

    /**
     * 追加一条消息。{@code null} 通常被忽略（具体实现可自行决定）。
     */
    void add(Message message);

    /**
     * 批量追加消息，按列表顺序逐条写入。{@code null} 或空列表视为无操作。
     */
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
     * <p>
     * 注意：窗口实现返回的是<b>裁剪后</b>的视图，完整历史需用实现类的专用方法读取
     * （如 {@link MessageWindowChatMemory#fullHistory()}）。
     */
    List<Message> messages();

    /**
     * 清空全部消息，但保留本记忆实例（会话标识不变）。
     */
    void clear();

    default int size() {
        return messages().size();
    }

    default boolean isEmpty() {
        return messages().isEmpty();
    }
}
