package com.zheng.fishagent;

import com.zheng.fishagent.agent.Agent;
import com.zheng.fishagent.agent.AgentResult;
import com.zheng.fishagent.agent.FakeChatModel;
import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.llm.TokenUsage;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证对外入口门面 {@link FishAgent} 的组装行为。
 */
class FishAgentTest {

    static class MathTools {
        @AgentTool("计算两个整数之和")
        public int add(@ToolParam("第一个加数") int a, @ToolParam("第二个加数") int b) {
            return a + b;
        }
    }

    @Test
    void 门面应组装模型工具与记忆并完成一轮工具调用() {
        FakeChatModel model = new FakeChatModel()
                .then(new ChatResponse(
                        Message.assistant("", List.of(ToolCall.of("add", Map.of("a", 1, "b", 2)))),
                        new TokenUsage(10, 5),
                        "stop"))
                .thenText("答案是 3");

        Agent agent = FishAgent.builder()
                .model(model)
                .toolBean(new MathTools())
                .session("facade-test")
                .streaming(false)
                .build();

        AgentResult result = agent.run("1+2 等于几");

        assertTrue(result.success());
        assertEquals("答案是 3", result.text());
        assertEquals(2, result.iterations());
        assertEquals("facade-test", agent.memory().id());
    }

    @Test
    void 未指定模型时应快速失败() {
        FishAgentException error = assertThrows(FishAgentException.class, () -> FishAgent.builder().build());
        assertTrue(error.getMessage().contains("未指定模型"));
    }
}
