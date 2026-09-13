package com.zheng.fishagent.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.llm.ChatRequest;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.ToolSpec;
import com.zheng.fishagent.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用一个本地 HTTP 桩服务验证 Ollama 协议适配，无需真实模型。
 */
class OllamaChatModelTest {

    private final AtomicReference<byte[]> responseBody = new AtomicReference<>(new byte[0]);
    private final AtomicReference<String> capturedRequest = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private HttpServer server;
    private OllamaChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        model = OllamaChatModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .model("test-model")
                .temperature(0.3)
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(String json) {
        responseBody.set(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ToolSpec multiplySpec() {
        return new ToolSpec("multiply", "两数相乘", Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of("type", "integer")),
                "required", List.of("a")));
    }

    @Test
    void 同步调用应解析内容与工具调用并正确拼装请求() throws Exception {
        respond("""
                {
                  "model": "test-model",
                  "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                      {"function": {"name": "multiply", "arguments": {"a": 6, "b": 7}}}
                    ]
                  },
                  "done": true,
                  "done_reason": "stop",
                  "prompt_eval_count": 31,
                  "eval_count": 12
                }""");

        ChatResponse response = model.call(ChatRequest.builder()
                .message(Message.system("你是助手"))
                .message(Message.user("6 乘 7"))
                .tools(List.of(multiplySpec()))
                .build());

        ToolCall call = response.message().toolCalls().getFirst();
        assertEquals("multiply", call.name());
        assertEquals(6, ((Number) call.arguments().get("a")).intValue());
        assertEquals(31, response.usage().promptTokens());
        assertEquals(12, response.usage().completionTokens());
        assertEquals("stop", response.finishReason());

        JsonNode request = Json.mapper().readTree(capturedRequest.get());
        assertEquals("test-model", request.path("model").asText());
        assertTrue(!request.path("stream").asBoolean(true));
        assertEquals(0.3, request.path("options").path("temperature").asDouble(), 0.0001);
        assertEquals("multiply", request.path("tools").get(0).path("function").path("name").asText());
        assertEquals("integer",
                request.path("tools").get(0).path("function").path("parameters").path("properties")
                        .path("a").path("type").asText());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertEquals("你是助手", request.path("messages").get(0).path("content").asText());
    }

    @Test
    void 流式调用应逐段回调并聚合工具调用() throws Exception {
        respond("""
                {"model":"test-model","message":{"role":"assistant","content":"你好"},"done":false}
                {"model":"test-model","message":{"role":"assistant","content":"，世界"},"done":false}
                {"model":"test-model","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"multiply","arguments":"{\\"a\\":2,\\"b\\":3}"}}]},"done":false}
                {"model":"test-model","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5}
                """);

        List<String> tokens = new ArrayList<>();
        ChatResponse response = model.stream(
                ChatRequest.builder().message(Message.user("打个招呼")).build(),
                tokens::add);

        assertEquals(List.of("你好", "，世界"), tokens);
        assertEquals("你好，世界", response.message().content());

        ToolCall call = response.message().toolCalls().getFirst();
        assertEquals("multiply", call.name());
        // Ollama 部分版本会把 arguments 序列化成 JSON 字符串，需要兼容
        assertEquals(2, ((Number) call.arguments().get("a")).intValue());
        assertEquals(3, ((Number) call.arguments().get("b")).intValue());
        assertEquals(10, response.usage().promptTokens());
        assertEquals(5, response.usage().completionTokens());
        assertEquals(15, response.usage().totalTokens());

        JsonNode request = Json.mapper().readTree(capturedRequest.get());
        assertTrue(request.path("stream").asBoolean(false));
        assertTrue(request.path("tools").isMissingNode());
    }

    @Test
    void 助手工具调用与工具结果应能序列化为线格式() throws Exception {
        respond("""
                {"model":"test-model","message":{"role":"assistant","content":"ok"},"done":true}""");

        model.call(ChatRequest.builder()
                .message(Message.user("6 乘 7"))
                .message(Message.assistant("", List.of(ToolCall.of("multiply", Map.of("a", 6, "b", 7)))))
                .message(Message.tool("multiply", "42"))
                .tools(List.of(multiplySpec()))
                .build());

        JsonNode messages = Json.mapper().readTree(capturedRequest.get()).path("messages");
        JsonNode assistantCall = messages.get(1).path("tool_calls").get(0).path("function");
        assertEquals("multiply", assistantCall.path("name").asText());
        assertEquals(6, assistantCall.path("arguments").path("a").asInt());

        JsonNode toolMessage = messages.get(2);
        assertEquals("tool", toolMessage.path("role").asText());
        assertEquals("42", toolMessage.path("content").asText());
        assertEquals("multiply", toolMessage.path("tool_name").asText());
    }

    @Test
    void HTTP错误应转换为框架异常() {
        status.set(500);
        respond("{\"error\":\"model not found\"}");

        FishAgentException exception = assertThrows(FishAgentException.class,
                () -> model.call(ChatRequest.builder().message(Message.user("hi")).build()));

        assertTrue(exception.getMessage().contains("500"), exception.getMessage());
        assertTrue(exception.getMessage().contains("model not found"), exception.getMessage());
    }

    @Test
    void 服务端error字段应被识别() {
        respond("{\"error\":\"pull model first\"}");

        FishAgentException exception = assertThrows(FishAgentException.class,
                () -> model.call(ChatRequest.builder().message(Message.user("hi")).build()));

        assertTrue(exception.getMessage().contains("pull model first"), exception.getMessage());
    }

    @Test
    void 应把tool_name开关透传() throws Exception {
        respond("""
                {"model":"test-model","message":{"role":"assistant","content":"ok"},"done":true}""");

        OllamaChatModel noToolName = OllamaChatModel.builder()
                .baseUrl(model.baseUrl())
                .model("test-model")
                .sendToolName(false)
                .build();

        noToolName.call(ChatRequest.builder()
                .message(Message.tool("multiply", "42"))
                .build());

        JsonNode toolMessage = Json.mapper().readTree(capturedRequest.get()).path("messages").get(0);
        assertEquals(Role.TOOL.value(), toolMessage.path("role").asText());
        assertTrue(toolMessage.path("tool_name").isMissingNode());
    }
}
