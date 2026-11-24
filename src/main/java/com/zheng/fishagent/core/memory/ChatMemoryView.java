package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

public interface ChatMemoryView {
    void add(Message user);

    List<? extends Message> messages();
}
