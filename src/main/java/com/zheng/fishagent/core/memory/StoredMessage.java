package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

public record StoredMessage(long seq, Message message) {
}
