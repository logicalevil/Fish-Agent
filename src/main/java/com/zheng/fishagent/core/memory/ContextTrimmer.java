package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;
@FunctionalInterface
public interface ContextTrimmer {
    /**
     * 把记忆中的消息列表裁剪为本次进入模型上下文的列表。
     * 纯函数：不修改入参、不读外部可变状态；保持原顺序；返回不可变列表。
     * null / 空列表返回空列表。入参不含系统提示词。
     */
    List<? extends Message> trim(List<Message> messages);

    /** 恒等实现：不做裁剪，返回防御性副本。 */
    static ContextTrimmer noop() {
        return messages -> messages == null ? List.of() : List.copyOf(messages);
    }
}
