package com.zheng.fishagent.memory;

import com.zheng.fishagent.core.memory.AgentRunScope;
import com.zheng.fishagent.core.memory.ChatMemory;
import com.zheng.fishagent.core.memory.FileChatMemory;
import com.zheng.fishagent.core.memory.InMemoryChatMemory;
import com.zheng.fishagent.core.memory.MessageWindowChatMemory;
import com.zheng.fishagent.core.message.ToolCall;
import com.zheng.fishagent.core.message.message.AssistantMessage;
import com.zheng.fishagent.core.message.message.Message;
import com.zheng.fishagent.core.message.message.ToolMessage;
import com.zheng.fishagent.testkit.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryTest {

    /** 测试用的最小作用域：以会话标识作为资源键，对应一个会话文件。 */
    private static AgentRunScope scope(String sessionId) {
        return () -> sessionId;
    }

    /** 消息正文的纯文本，便于断言。 */
    private static String textOf(Message message) {
        StringBuilder sb = new StringBuilder();
        message.contentList().forEach(content -> sb.append(content.text()));
        return sb.toString();
    }

    @Test
    void 窗口记忆应保留系统消息与最近N条() {
        MessageWindowChatMemory memory = MessageWindowChatMemory.of("window", 3);
        memory.add(Messages.system("你是助手"));
        for (int i = 1; i <= 6; i++) {
            memory.add(Messages.user("问题" + i));
        }

        List<Message> window = memory.messages();

        assertEquals(4, window.size());
        assertEquals("你是助手", textOf(window.getFirst()));
        assertEquals("问题4", textOf(window.get(1)));
        assertEquals("问题6", textOf(window.getLast()));
        assertEquals(7, memory.fullHistory().size());
    }

    @Test
    void 文件记忆应能持久化并重新加载(@TempDir Path tempDir) {
        FileChatMemory memory = FileChatMemory.of(tempDir, scope("tester"));
        memory.add(Messages.user("你好"));
        memory.add(Messages.assistant("", List.of(ToolCall.of("calculate", Map.of("expression", "1+1")))));
        memory.add(Messages.tool("call-1", "calculate", "2"));

        assertTrue(Files.exists(memory.file()));

        FileChatMemory reloaded = FileChatMemory.of(tempDir, scope("tester"));
        List<Message> messages = reloaded.messages();

        assertEquals(3, messages.size());
        assertEquals("你好", textOf(messages.getFirst()));
        AssistantMessage assistantMessage = (AssistantMessage) messages.get(1);
        assertEquals("calculate", assistantMessage.toolCallsCopyOnWrite().getFirst().name());
        assertEquals(Map.of("expression", "1+1"), assistantMessage.toolCallsCopyOnWrite().getFirst().arguments());
        assertEquals("calculate", ((ToolMessage) messages.get(2)).toolName());
    }

    @Test
    void 清空后应删除会话文件(@TempDir Path tempDir) {
        FileChatMemory memory = FileChatMemory.of(tempDir, scope("to-clear"));
        memory.add(Messages.user("hi"));
        memory.clear();

        assertTrue(memory.isEmpty());
        assertTrue(Files.notExists(memory.file()));
    }

    @Test
    void 内存记忆应支持多轮追加() {
        ChatMemory memory = new InMemoryChatMemory("memory", scope("memory"));
        memory.addAll(List.of(Messages.user("a"), Messages.assistant("b")));

        assertEquals(2, memory.size());
        memory.clear();
        assertTrue(memory.isEmpty());
    }
}
