package com.zheng.fishagent.memory;

import com.zheng.fishagent.core.FishAgentException;
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

/**
 * 基于 JSONL 文件的记忆实现：一条消息一行，便于追加写入与人工排查。
 * 默认目录为 {@code <工作目录>/.fishagent/sessions}。
 */
public final class FileChatMemory implements ChatMemory {

    public static final Path DEFAULT_DIR = Path.of(".fishagent", "sessions");

    private final String id;
    private final Path file;
    private final List<Message> cache = new ArrayList<>();

    private FileChatMemory(String id, Path file) {
        this.id = id;
        this.file = file;
        load();
    }

    public static FileChatMemory of(String sessionId) {
        return of(sessionId, DEFAULT_DIR);
    }

    public static FileChatMemory of(String sessionId, Path directory) {
        String safeId = sessionId == null || sessionId.isBlank() ? "default" : sessionId.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        return new FileChatMemory(safeId, directory.resolve(safeId + ".jsonl"));
    }

    /**
     * 会话文件位置。
     */
    public Path file() {
        return file;
    }

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
    public synchronized void add(Message message) {
        if (message == null) {
            return;
        }
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
    }

    @Override
    public synchronized List<Message> messages() {
        return new ArrayList<>(cache);
    }

    /**
     * 清空文件内容与缓存。
     */
    @Override
    public synchronized void clear() {
        cache.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw FishAgentException.wrap("清空会话文件失败：" + file, e);
        }
    }

    /**
     * 便捷方法：把 Map 列表整体导入（例如从其它存储迁移）。
     */
    public synchronized void importMessages(List<Map<String, Object>> rawMessages) {
        for (Map<String, Object> raw : rawMessages) {
            add(Message.fromMap(raw));
        }
    }
}
