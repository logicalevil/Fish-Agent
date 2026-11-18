package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.message.Message;

public interface ChatMemoryView {
    void add(Message user);
}
