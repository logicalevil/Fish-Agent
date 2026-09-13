package com.zheng.fishagent.memory;

import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryTest {

    @Test
    void 窗口记忆应保留系统消息与最近N条(@TempDir Path tempDir) {
        MessageWindowChatMemory memory = MessageWindowChatMemory.of("window", 3);
        memory.add(Message.system("你是助手"));
        for (int i = 1; i <= 6; i++) {
            memory.add(Message.user("问题" + i));
        }

        List<Message> window = memory.messages();

        assertEquals(4, window.size());
        assertEquals("你是助手", window.getFirst().content());
        assertEquals("问题4", window.get(1).content());
        assertEquals("问题6", window.getLast().content());
        assertEquals(7, memory.fullHistory().size());
    }

    @Test
    void 文件记忆应能持久化并重新加载(@TempDir Path tempDir) {
        FileChatMemory memory = FileChatMemory.of("tester", tempDir);
        memory.add(Message.user("你好"));
        memory.add(Message.assistant("", List.of(ToolCall.of("calculate", Map.of("expression", "1+1")))));
        memory.add(Message.tool("calculate", "2"));

        assertTrue(Files.exists(memory.file()));

        FileChatMemory reloaded = FileChatMemory.of("tester", tempDir);
        List<Message> messages = reloaded.messages();

        assertEquals(3, messages.size());
        assertEquals("你好", messages.getFirst().content());
        assertEquals("calculate", messages.get(1).toolCalls().getFirst().name());
        assertEquals(Map.of("expression", "1+1"), messages.get(1).toolCalls().getFirst().arguments());
        assertEquals("calculate", messages.get(2).toolName());
    }

    @Test
    void 清空后应删除会话文件(@TempDir Path tempDir) {
        FileChatMemory memory = FileChatMemory.of("to-clear", tempDir);
        memory.add(Message.user("hi"));
        memory.clear();

        assertTrue(memory.isEmpty());
        assertTrue(Files.notExists(memory.file()));
    }

    @Test
    void 内存记忆应支持多轮追加() {
        ChatMemory memory = new InMemoryChatMemory("memory");
        memory.addAll(List.of(Message.user("a"), Message.assistant("b")));

        assertEquals(2, memory.size());
        memory.clear();
        assertTrue(memory.isEmpty());
    }
}
