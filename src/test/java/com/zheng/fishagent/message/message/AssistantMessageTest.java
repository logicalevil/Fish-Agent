package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.message.content.Content;
import com.zheng.fishagent.message.content.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link AssistantMessage} 上工具调用的实例入口与映射序列化。
 */
class AssistantMessageTest {

    private static final List<Content> TEXT = List.of(new TextContent("让我算一下"));

    private static AssistantMessage assistant(ToolCall... calls) {
        return new AssistantMessage(TEXT, List.of(calls));
    }

    @Test
    void 助手消息应原样返回工具调用列表() {
        ToolCall multiply = ToolCall.of("multiply", Map.of("a", 6, "b", 7));
        AssistantMessage message = assistant(multiply);

        List<ToolCall> calls = message.toolCallsCopyOnWrite();

        assertEquals(1, calls.size());
        assertEquals("multiply", calls.getFirst().name());
        assertEquals(Map.of("a", 6, "b", 7), calls.getFirst().arguments());
        assertEquals(multiply, calls.getFirst());
    }

    @Test
    void 工具调用列表为防御性副本() {
        AssistantMessage message = assistant(ToolCall.of("multiply", Map.of()));

        List<ToolCall> calls = message.toolCallsCopyOnWrite();
        calls.add(ToolCall.of("other", Map.of()));

        assertEquals(1, message.toolCallsCopyOnWrite().size(), "修改返回值不应影响消息内部状态");
    }

    @Test
    void 构造时传入null应视为无工具调用() {
        AssistantMessage message = new AssistantMessage(TEXT, null);

        assertTrue(message.toolCallsCopyOnWrite().isEmpty());
        assertFalse(message.hasToolCalls());
    }

    @Test
    void 有工具调用时hasToolCalls为真() {
        AssistantMessage message = assistant(ToolCall.of("multiply", Map.of()));

        assertTrue(message.hasToolCalls());
    }

    @Test
    void 无工具调用时hasToolCalls为假() {
        assertFalse(new AssistantMessage(TEXT, List.of()).hasToolCalls());
    }

    @Test
    void 工具调用参与相等性比较() {
        ToolCall call = ToolCall.of("multiply", Map.of("a", 6, "b", 7));

        assertEquals(assistant(call), assistant(call));
        assertEquals(assistant(call).hashCode(), assistant(call).hashCode());
        assertFalse(assistant(call).equals(assistant()));
        assertFalse(assistant().equals(assistant(call)));
    }

    @Test
    void 助手消息角色为ASSISTANT() {
        assertEquals(Role.ASSISTANT, assistant().role());
    }
}
