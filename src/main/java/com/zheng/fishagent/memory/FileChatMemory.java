package com.zheng.fishagent.memory;

import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.util.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 JSONL 文件的记忆实现：一条消息一行，便于追加写入与人工排查。
 * 默认目录为 {@code <工作目录>/.fishagent/sessions}。
 * <p>
 * 并发保护由注入的 {@link MemoryLock} 承担（不再使用方法级 {@code synchronized}）。
 * 默认使用 {@link MemoryLock#local()}，仅在当前 JVM 内互斥；若多个实例共享同一份文件，
 * 需注入自行实现的跨进程锁。
 */
public final class FileChatMemory implements ChatMemory {

    public static final Path DEFAULT_DIR = Path.of(".fishagent", "sessions");

    /** 默认的锁等待时间。 */
    private static final java.time.Duration LOCK_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final String id;
    private final Path file;
    private final MemoryLock lock;
    private final List<Message> cache = new ArrayList<>();

    private FileChatMemory(String id, Path file, MemoryLock lock) {
        this.id = id;
        this.file = file;
        this.lock = Objects.requireNonNull(lock, "lock 不能为空");
        load();
    }

    /**
     * 使用默认目录（{@link #DEFAULT_DIR}）与默认锁创建。
     */
    public static FileChatMemory of(String sessionId) {
        return of(sessionId, DEFAULT_DIR);
    }

    /**
     * 在指定目录创建，使用默认锁（{@link MemoryLock#local()}）。
     * 会话标识中的非法文件名字符会被替换为 {@code _}；构造时即读取已有文件，从而恢复历史。
     *
     * @param sessionId 会话标识；{@code null} 或空白时使用 {@code default}
     * @param directory 存储目录，不存在时在首次写入时创建
     */
    public static FileChatMemory of(String sessionId, Path directory) {
        return of(sessionId, directory, MemoryLock.local());
    }

    /**
     * 在指定目录创建，并使用指定的锁。
     * <p>
     * 当多个 JVM 实例可能访问同一份会话文件时，传入自行实现的跨进程 {@link MemoryLock}；
     * 否则并发写入可能导致缓存与文件不一致。
     *
     * @param sessionId 会话标识；{@code null} 或空白时使用 {@code default}
     * @param directory 存储目录
     * @param lock      互斥实现；{@code null} 时回退为默认本地锁
     */
    public static FileChatMemory of(String sessionId, Path directory, MemoryLock lock) {
        String safeId = sessionId == null || sessionId.isBlank() ? "default" : sessionId.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        return new FileChatMemory(safeId, directory.resolve(safeId + ".jsonl"), lock);
    }

    /**
     * 会话文件位置。
     */
    public Path file() {
        return file;
    }

    /**
     * 本实现使用的锁，供诊断与测试观察。
     */
    public MemoryLock lock() {
        return lock;
    }

    /** 构造时读取已有内容，无需加锁（此时实例尚未发布）。 */
    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                cache.add(Message.fromMap(Json.readMap(line)));
            }
        } catch (IOException e) {
            throw FishAgentException.wrap("读取会话文件失败：" + file, e);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void add(Message message) {
        if (message == null) {
            return;
        }
        lock.execute(id, LOCK_TIMEOUT, () -> {
            cache.add(message);
            try {
                Files.createDirectories(file.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write(Json.write(message.toMap()));
                    writer.newLine();
                }
            } catch (IOException e) {
                throw FishAgentException.wrap("写入会话文件失败：" + file, e);
            }
            return null;
        });
    }

    @Override
    public List<Message> messages() {
        return lock.execute(id, LOCK_TIMEOUT, () -> new ArrayList<>(cache));
    }

    /**
     * 清空文件内容与缓存。文件本身会被删除，下次 {@link #add(Message)} 时按需重建。
     */
    @Override
    public void clear() {
        lock.execute(id, LOCK_TIMEOUT, () -> {
            cache.clear();
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw FishAgentException.wrap("清空会话文件失败：" + file, e);
            }
            return null;
        });
    }

    /**
     * 便捷方法：把 Map 列表整体导入（例如从其它存储迁移）。
     */
    public void importMessages(List<Map<String, Object>> rawMessages) {
        for (Map<String, Object> raw : rawMessages) {
            add(Message.fromMap(raw));
        }
    }
}
