package com.zheng.fishagent.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ReActOutputParserTest {

    @Test
    void 应解析最终答案() {
        String text = """
                Thought: 我已经知道最终答案
                Final Answer: 北京现在是 15:00。""";

        ReActStep step = ReActOutputParser.parse(text);

        ReActStep.FinalAnswer answer = assertInstanceOf(ReActStep.FinalAnswer.class, step);
        assertEquals("北京现在是 15:00。", answer.text());
        assertEquals("我已经知道最终答案", answer.thought());
    }

    @Test
    void 应解析工具调用() {
        String text = """
                Thought: 需要查询北京当前时间
                Action: currentTime
                Action Input: {"city": "北京"}""";

        ReActStep step = ReActOutputParser.parse(text);

        ReActStep.ToolRequest request = assertInstanceOf(ReActStep.ToolRequest.class, step);
        assertEquals("currentTime", request.tool());
        assertEquals(Map.of("city", "北京"), request.arguments());
        assertEquals("需要查询北京当前时间", request.thought());
    }

    @Test
    void 应兼容Markdown加粗与中文冒号() {
        String text = """
                **Thought：** 先算一下
                **Action:** calculate
                **Action Input：** 1+1""";

        ReActStep step = ReActOutputParser.parse(text);

        ReActStep.ToolRequest request = assertInstanceOf(ReActStep.ToolRequest.class, step);
        assertEquals("calculate", request.tool());
        assertEquals(Map.of("input", "1+1"), request.arguments());
    }

    @Test
    void Action出现在最终答案之后应按工具调用处理() {
        String text = """
                Final Answer: 稍等
                Thought: 还需要一次调用
                Action: currentTime
                Action Input: {}""";

        ReActStep step = ReActOutputParser.parse(text);

        assertInstanceOf(ReActStep.ToolRequest.class, step);
    }

    @Test
    void 非JSON的ActionInput应尽力解析() {
        Map<String, Object> pairs = ReActOutputParser.parseArguments("city=北京\nunit=celsius");
        assertEquals(Map.of("city", "北京", "unit", "celsius"), pairs);

        Map<String, Object> scalar = ReActOutputParser.parseArguments("1+1");
        assertEquals(Map.of("input", "1+1"), scalar);

        Map<String, Object> fenced = ReActOutputParser.parseArguments("```json\n{\"a\": 1}\n```");
        assertEquals(Map.of("a", 1), fenced);
    }

    @Test
    void 不符合格式的输出应原样返回() {
        ReActStep step = ReActOutputParser.parse("北京今天多云。");

        ReActStep.Unparsed unparsed = assertInstanceOf(ReActStep.Unparsed.class, step);
        assertEquals("北京今天多云。", unparsed.raw());
    }
}
