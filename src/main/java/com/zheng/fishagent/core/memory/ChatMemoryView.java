package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

public interface ChatMemoryView {
    void add(Message user);

    /** 本次要发给模型的上下文，可经过裁剪或摘要。 */
    List<? extends Message> context();

    /** 完整历史原文，不受窗口/摘要影响。 */
    List<? extends Message> history();

}
