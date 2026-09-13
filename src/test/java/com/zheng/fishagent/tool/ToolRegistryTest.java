package com.zheng.fishagent.tool;

import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void 应能从注解方法生成工具与Schema() {
        ToolRegistry registry = new ToolRegistry().register(new SampleTools());

        assertTrue(registry.contains("add"));
        assertEquals(3, registry.size());

        Tool add = registry.find("add").orElseThrow();
        assertEquals("两数相加", add.description());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) add.parameters().get("properties");
        assertEquals("integer", ((Map<?, ?>) properties.get("a")).get("type"));
        assertEquals("a 的值", ((Map<?, ?>) properties.get("a")).get("description"));

        assertEquals(List.of("a", "b"), add.parameters().get("required"));

        Tool greet = registry.find("greet").orElseThrow();
        assertFalse(greet.parameters().containsKey("required"));
    }

    @Test
    void 执行时应完成参数类型转换() {
        ToolRegistry registry = new ToolRegistry().register(new SampleTools());

        String result = registry.execute(ToolCall.of("add", Map.of("a", "1", "b", 2)));
        assertEquals("3", result);

        String greet = registry.execute(ToolCall.of("greet", Map.of("name", "小明")));
        assertEquals("你好，小明", greet);
    }

    @Test
    void 工具异常应转为错误文本而非抛出() {
        ToolRegistry registry = new ToolRegistry().register(new SampleTools());

        String result = registry.execute(ToolCall.of("explode", Map.of()));
        assertTrue(result.startsWith("错误："), result);
    }

    @Test
    void 未知工具应返回可用工具列表() {
        ToolRegistry registry = new ToolRegistry().register(new SampleTools());

        String result = registry.execute(ToolCall.of("not_exists", Map.of()));
        assertTrue(result.contains("不存在名为 not_exists 的工具"), result);
    }

    @Test
    void 应把通用参数名纠正为声明的参数名() {
        ToolRegistry registry = new ToolRegistry().register(new SampleTools());

        ToolCall normalized = registry.normalize(ToolCall.of("greet", Map.of("input", "红红")));
        assertEquals(Map.of("name", "红红"), normalized.arguments());
        assertEquals("你好，红红", registry.execute(normalized));
    }

    @Test
    void 重复注册同名工具应报错() {
        ToolRegistry registry = new ToolRegistry()
                .register(FunctionTool.of("same", "a", args -> "1"));
        assertThrows(RuntimeException.class,
                () -> registry.register(FunctionTool.of("same", "b", args -> "2")));
    }

    @Test
    void FunctionTool应能生成参数Schema() {
        FunctionTool tool = FunctionTool.builder()
                .name("search")
                .description("搜索")
                .parameter("keyword", "string", "关键词", true)
                .parameter("limit", "integer", "数量", false)
                .function(args -> "ok:" + args.get("keyword"))
                .build();

        assertEquals("ok:鱼", tool.execute(Map.of("keyword", "鱼")));
        assertEquals(List.of("keyword"), tool.parameters().get("required"));
    }

    static class SampleTools {

        @AgentTool("两数相加")
        public int add(@ToolParam("a 的值") int a, @ToolParam("b 的值") int b) {
            return a + b;
        }

        @AgentTool("向某人问好")
        public String greet(@ToolParam(value = "对方名字", required = false) String name) {
            return "你好，" + name;
        }

        @AgentTool("总是抛异常，用于验证错误兜底")
        public String explode() {
            throw new IllegalStateException("炸了");
        }
    }
}
