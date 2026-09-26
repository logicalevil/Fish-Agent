package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内记忆，默认实现。
 * <p>
 * 底层用 {@link CopyOnWriteArrayList} 存储，读取（即构造模型上下文）无需加锁，
 * 适合「读多写少」的对话场景；进程退出后数据即丢失，需持久化请用
 * {@link FileChatMemory}。
 */
public class InMemoryChatMemory implements ChatMemory {

    private final String id;
    private final List<Message> store = new CopyOnWriteArrayList<>();

    /**
     * 以随机生成的会话标识创建。
     */
    public InMemoryChatMemory() {
        this("session-" + UUID.randomUUID());
    }

    /**
     * 以指定会话标识创建；{@code null} 或空白时自动生成随机标识。
     */
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
