package com.zheng.fishagent.agent;

import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.memory.InMemoryChatMemory;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.ToolRegistry;
import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentTest {

    @Test
    void 原生模式应执行工具并把结果回填后给出答案() {
        FakeChatModel model = new FakeChatModel()
                .then(new ChatResponse(
                        Message.assistant("", List.of(ToolCall.of("multiply", Map.of("a", 6, "b", 7)))),
                        com.zheng.fishagent.llm.TokenUsage.EMPTY,
                        "stop"))
                .thenText("结果是 42。");

        ReActAgent agent = ReActAgent.builder()
                .model(model)
                .tools(new ToolRegistry().register(new MathTools()))
                .memory(new InMemoryChatMemory("native"))
                .streaming(false)
                .build();

        AgentResult result = agent.run("6 乘 7 等于几？");

        assertEquals("结果是 42。", result.text());
        assertEquals(2, result.iterations());
        assertTrue(result.success());
        assertEquals(AgentResult.STOP, result.stopReason());

        // 首次请求应带上工具 Schema
        assertEquals(1, model.requests().getFirst().tools().size());
        assertEquals("multiply", model.requests().getFirst().tools().getFirst().name());

        // 第二次请求应包含工具结果
        List<Message> secondRound = model.requests().get(1).messages();
        Message toolMessage = secondRound.getLast();
        assertEquals(Role.TOOL, toolMessage.role());
        assertEquals("multiply", toolMessage.toolName());
        assertEquals("42", toolMessage.content());

        // 记忆里应有 user / assistant(toolCall) / tool / assistant 四条
        assertEquals(4, agent.memory().messages().size());
    }

    @Test
    void 文本模式应解析Action并回填Observation() {
        FakeChatModel model = new FakeChatModel()
                .thenText("""
                        Thought: 需要计算
                        Action: multiply
                        Action Input: {"a": 3, "b": 5}""")
                .thenText("""
                        Thought: 我已经知道最终答案
                        Final Answer: 3 乘 5 等于 15。""");

        ReActAgent agent = ReActAgent.builder()
                .model(model)
                .tools(new ToolRegistry().register(new MathTools()))
                .memory(new InMemoryChatMemory("react"))
                .mode(ToolCallMode.REACT_TEXT)
                .streaming(false)
                .build();

        AgentResult result = agent.run("3 乘 5 等于几？");

        assertEquals("3 乘 5 等于 15。", result.text());
        assertTrue(result.success());

        // 文本模式不下发 tools 字段
        assertTrue(model.lastRequest().tools().isEmpty());

        Message observation = model.requests().get(1).messages().getLast();
        assertEquals(Role.USER, observation.role());
        assertTrue(observation.content().startsWith("Observation: 15"), observation.content());
    }

    @Test
    void 文本模式流式应只推送最终答案() {
        FakeChatModel model = new FakeChatModel(3)
                .thenText("""
                        Thought: 先算一下
                        Action: multiply
                        Action Input: {"a": 2, "b": 8}""")
                .thenText("""
                        Thought: 我已经知道最终答案
                        Final Answer: 答案是 16。""");

        ReActAgent agent = ReActAgent.builder()
                .model(model)
                .tools(new ToolRegistry().register(new MathTools()))
                .mode(ToolCallMode.REACT_TEXT)
                .streaming(true)
                .build();

        List<String> tokens = new ArrayList<>();
        AgentResult result = agent.run("2 乘 8 等于几？", new AgentListener() {
            @Override
            public void onToken(String token) {
                tokens.add(token);
            }
        });

        assertEquals("答案是 16。", String.join("", tokens));
        assertEquals("答案是 16。", result.text());
    }

    @Test
    void 达到最大轮数应返回未完成结果() {
        FakeChatModel model = new FakeChatModel();
        for (int i = 0; i < 5; i++) {
            model.then(new ChatResponse(
                    Message.assistant("再确认一次", List.of(ToolCall.of("multiply", Map.of("a", i, "b", 2)))),
                    com.zheng.fishagent.llm.TokenUsage.EMPTY,
                    "stop"));
        }

        ReActAgent agent = ReActAgent.builder()
                .model(model)
                .tools(new ToolRegistry().register(new MathTools()))
                .maxIterations(3)
                .streaming(false)
                .build();

        AgentResult result = agent.run("帮我算点东西");

        assertEquals(AgentResult.MAX_ITERATIONS, result.stopReason());
        assertFalse(result.success());
        assertEquals(3, result.iterations());
    }

    @Test
    void 重复调用同一工具应被判定为死循环() {
        FakeChatModel model = new FakeChatModel();
        for (int i = 0; i < 5; i++) {
            model.then(new ChatResponse(
                    Message.assistant("", List.of(ToolCall.of("multiply", Map.of("a", 1, "b", 1)))),
                    com.zheng.fishagent.llm.TokenUsage.EMPTY,
                    "stop"));
        }

        ReActAgent agent = ReActAgent.builder()
                .model(model)
                .tools(new ToolRegistry().register(new MathTools()))
                .maxIterations(5)
                .maxRepeatedToolCalls(2)
                .streaming(false)
                .build();

        AgentResult result = agent.run("算 1*1");

        assertEquals(AgentResult.LOOP_DETECTED, result.stopReason());
        assertFalse(result.success());
        assertEquals(3, result.iterations());
    }

    @Test
    void 累计Token用量应跨轮次相加() {
        FakeChatModel model = new FakeChatModel()
                .then(new ChatResponse(
                        Message.assistant("", List.of(ToolCall.of("multiply", Map.of("a", 1, "b", 2)))),
                        new com.zheng.fishagent.llm.TokenUsage(100, 10),
                        "stop"))
                .thenText("2");

        ReActAgent agent = ReActAgent.builder()
                .model(model)
                .tools(new ToolRegistry().register(new MathTools()))
                .streaming(false)
                .build();

        AgentResult result = agent.run("1*2");

        // 第一轮 100+10，第二轮由 FakeChatModel.thenText 计为 1+1
        assertEquals(112, result.usage().totalTokens());
    }

    static class MathTools {

        @AgentTool("计算两个整数相乘")
        public int multiply(@ToolParam("被乘数") int a, @ToolParam("乘数") int b) {
            return a * b;
        }
    }
}
