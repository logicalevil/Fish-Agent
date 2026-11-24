package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.message.Message;

import java.util.Collection;
import java.util.List;

public interface ChatMemoryView {
    void add(Message user);

    List<? extends Message> messages();
}
