package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

/**
 * 会话记忆视图：Agent 读写记忆的<b>唯一入口</b>。
 * <p>
 * 与 {@link ChatMemory} 的区别在于职责拆分：
 * <ul>
 *   <li>{@link #context()} 返回「本次要发给模型的上下文」，可经过窗口裁剪或摘要压缩；</li>
 *   <li>{@link #history()} 返回「完整历史原文」，不受裁剪影响。</li>
 * </ul>
 * 这样裁剪策略可以随实现自由更换，而写入与读取的调用点保持稳定。
 */
public interface ChatMemoryView {

    /**
     * 追加一条消息。{@code null} 由实现决定如何处理（通常忽略）。
     */
    void add(Message message);

    /** 本次要发给模型的上下文，可经过裁剪或摘要。 */
    List<? extends Message> context();

    /** 完整历史原文，不受窗口/摘要影响。 */
    List<? extends Message> history();

    /**
     * 清空该会话的全部消息（上下文与历史一并清空，实例本身保留）。
     */
    void clear();

    /**
     * 会话消息条数，以完整历史为准。
     * <p>
     * 刻意基于 {@link #history()} 而非 {@link #context()}：后者可能被裁剪，
     * 不能代表会话的真实状态。
     */
    default int size() {
        return history().size();
    }

    /**
     * 会话是否为空，以完整历史为准。
     */
    default boolean isEmpty() {
        return history().isEmpty();
    }
}
