package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.constant.LockConstant;
import com.zheng.fishagent.core.lock.MemoryLock;
import com.zheng.fishagent.core.message.message.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class DelegateMemoryView extends AbstractChatMemoryView{



    private final MemoryLock memoryLock ;
    private final List<Message> contextMessage;
    private final AgentRunScope agentRunScope;
    private final MessageStore messageStore;

    public DelegateMemoryView(List<Message> contextMessage, AgentRunScope agentRunScope, MessageStore messageStore, MemoryLock memoryLock ) {
        this.contextMessage = contextMessage;
        this.agentRunScope = agentRunScope;
        this.messageStore = messageStore;
        this.memoryLock = memoryLock;
    }

    @Override
    public void add(Message message) {
        if (message == null) {
            return;
        }
        memoryLock.execute(agentRunScope, Duration.ofSeconds(LockConstant.LOCK_TIMEOUT), () -> {
            // 先同步落库，成功后再更新内存
            messageStore.append(agentRunScope, message);
            contextMessage.add(message);

            return null;
        });
    }

    @Override
    public List<? extends Message> messages() {
        return null;
    }

    @Override
    public List<? extends Message> rawContext() {
        return List.copyOf(this.contextMessage);
    }


    @Override
    public List<? extends Message> history() {
        List<StoredMessage> load = messageStore.load(agentRunScope, 0, 0);
        List<Message> messages = new ArrayList<>(load.size());
        for (StoredMessage storedMessage : load) {
            messages.add(storedMessage.message());
        }
        return messages;
    }
}
