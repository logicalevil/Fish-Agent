package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内记忆，默认实现。
 */
public class InMemoryChatMemory implements ChatMemory {

    private final String id;
    private final List<Message> store = new CopyOnWriteArrayList<>();

    public InMemoryChatMemory() {
        this("session-" + UUID.randomUUID());
    }

    public InMemoryChatMemory(String id) {
        this.id = id == null || id.isBlank() ? "session-" + UUID.randomUUID() : id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void add(Message message) {
        if (message != null) {
            store.add(message);
        }
    }

    @Override
    public List<Message> messages() {
        return new ArrayList<>(store);
    }

    @Override
    public void clear() {
        store.clear();
    }
}
