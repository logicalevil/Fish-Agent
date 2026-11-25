package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

public final class DefaultContextTrimmer implements ContextTrimmer {

    /* 默认参数*/
    /* 默认最大消息数*/
    private static final int DEFAULT_MAX_MESSAGES = 30;
    /* 默认最大token数*/
    private static final int DEFAULT_MAX_TOKENS = 1000;
    /* 默认最后不压缩的消息数*/
    private static final int DEFAULT_LAST_MESSAGE_COUNT = 10;

    public DefaultContextTrimmer() {
    }

    @Override
    public List<? extends Message> trim(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        // 1) 分离 SYSTEM 与普通消息
        // 2) 从尾部取 maxMessages 条
        // 3) 起点若为 ToolMessage，向前扩展到配对的 AssistantMessage（可略微超出 N）
        // 4) SYSTEM + 窗口 -> List.copyOf 返回
    }
}
