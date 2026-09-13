package com.zheng.fishagent.llm.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.llm.ChatRequest;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.llm.StreamListener;
import com.zheng.fishagent.llm.TokenUsage;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.ToolSpec;
import com.zheng.fishagent.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 Ollama 原生 {@code /api/chat} 的模型实现，支持工具调用与流式输出。
 *
 * <pre>{@code
 * ChatModel model = OllamaChatModel.builder()
 *         .baseUrl("http://localhost:11434")
 *         .model("qwen2.5:7b")
 *         .temperature(0.7)
 *         .build();
 * }</pre>
 */
public final class OllamaChatModel implements ChatModel {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Map<String, Object> defaultOptions;
    private final boolean sendToolName;

    private OllamaChatModel(Builder builder) {
        Objects.requireNonNull(builder.model, "model 不能为空");
        this.model = builder.model;
        this.baseUrl = normalizeBaseUrl(builder.baseUrl == null ? DEFAULT_BASE_URL : builder.baseUrl);
        this.requestTimeout = builder.requestTimeout == null ? Duration.ofMinutes(5) : builder.requestTimeout;
        this.defaultOptions = Map.copyOf(builder.defaultOptions);
        this.sendToolName = builder.sendToolName;
        this.httpClient = builder.httpClient != null ? builder.httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String modelName() {
        return model;
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------ 同步

    @Override
    public ChatResponse call(ChatRequest request) {
        String payload = Json.write(buildBody(request, false));
        HttpRequest httpRequest = newRequest("/api/chat", payload, true);
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());
            return parseMessage(response.body());
        } catch (IOException e) {
            throw connectionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FishAgentException("调用 Ollama 被中断", e);
        }
    }

    // ------------------------------------------------------------------ 流式

    @Override
    public ChatResponse stream(ChatRequest request, StreamListener listener) {
        StreamListener callback = listener == null ? StreamListener.NOOP : listener;
        try {
            ChatResponse response = doStream(request, callback);
            callback.onComplete(response);
            return response;
        } catch (RuntimeException e) {
            callback.onError(e);
            throw e;
        }
    }

    private ChatResponse doStream(ChatRequest request, StreamListener callback) {
        String payload = Json.write(buildBody(request, true));
        HttpRequest httpRequest = newRequest("/api/chat", payload, false);

        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        long promptTokens = 0;
        long completionTokens = 0;
        String finishReason = "stop";

        try {
            HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                String errorBody;
                try (Stream<String> errorLines = response.body()) {
                    errorBody = errorLines.collect(Collectors.joining("\n"));
                }
                ensureSuccess(response.statusCode(), errorBody);
            }
            try (Stream<String> lines = response.body()) {
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next().trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    JsonNode chunk = Json.mapper().readTree(line);
                    if (chunk.hasNonNull("error")) {
                        throw FishAgentException.of("Ollama 返回错误：%s", chunk.get("error").asText());
                    }
                    JsonNode messageNode = chunk.get("message");
                    if (messageNode != null && !messageNode.isNull()) {
                        JsonNode contentNode = messageNode.get("content");
                        if (contentNode != null && !contentNode.isNull()) {
                            String token = contentNode.asText();
                            if (!token.isEmpty()) {
                                content.append(token);
                                callback.onToken(token);
                            }
                        }
                        JsonNode callsNode = messageNode.get("tool_calls");
                        if (callsNode != null && callsNode.isArray()) {
                            toolCalls.addAll(parseToolCalls(callsNode));
                        }
                    }
                    if (chunk.path("done").asBoolean(false)) {
                        String reason = chunk.path("done_reason").asText("");
                        if (!reason.isBlank()) {
                            finishReason = reason;
                        }
                        promptTokens = chunk.path("prompt_eval_count").asLong(promptTokens);
                        completionTokens = chunk.path("eval_count").asLong(completionTokens);
                    }
                }
            }
        } catch (IOException e) {
            throw connectionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FishAgentException("流式调用 Ollama 被中断", e);
        }

        return new ChatResponse(
                Message.assistant(content.toString(), toolCalls),
                new TokenUsage(promptTokens, completionTokens),
                finishReason);
    }

    // ------------------------------------------------------------------ 元信息

    /**
     * 列出本地已安装的模型名。
     */
    public List<String> listModels() {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());
            JsonNode models = Json.mapper().readTree(response.body()).path("models");
            List<String> names = new ArrayList<>();
            for (JsonNode node : models) {
                names.add(node.path("name").asText(node.path("model").asText("")));
            }
            return names;
        } catch (IOException e) {
            throw connectionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FishAgentException("查询 Ollama 模型列表被中断", e);
        }
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> buildBody(ChatRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages().stream().map(this::toWireMessage).toList());
        body.put("stream", stream);

        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toWireTool).toList());
        }

        Map<String, Object> options = new LinkedHashMap<>(defaultOptions);
        if (request.temperature() != null) {
            options.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            options.put("top_p", request.topP());
        }
        if (request.maxTokens() != null) {
            options.put("num_predict", request.maxTokens());
        }
        if (!request.stop().isEmpty()) {
            options.put("stop", request.stop());
        }
        options.putAll(request.extraOptions());
        options.values().removeIf(Objects::isNull);
        if (!options.isEmpty()) {
            body.put("options", options);
        }
        return body;
    }

    private Map<String, Object> toWireMessage(Message message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role().value());
        wire.put("content", message.content() == null ? "" : message.content());

        if (message.role() == Role.ASSISTANT && message.hasToolCalls()) {
            List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
            for (ToolCall call : message.toolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", call.arguments());
                calls.add(Map.of("function", function));
            }
            wire.put("tool_calls", calls);
        }
        if (message.role() == Role.TOOL && sendToolName && message.toolName() != null) {
            wire.put("tool_name", message.toolName());
        }
        return wire;
    }

    private Map<String, Object> toWireTool(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        function.put("description", spec.description());
        function.put("parameters", spec.parameters());
        return Map.of("type", "function", "function", function);
    }

    private ChatResponse parseMessage(String body) {
        try {
            JsonNode root = Json.mapper().readTree(body);
            if (root.hasNonNull("error")) {
                throw FishAgentException.of("Ollama 返回错误：%s", root.get("error").asText());
            }
            JsonNode messageNode = root.path("message");
            String content = messageNode.path("content").asText("");
            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode callsNode = messageNode.get("tool_calls");
            if (callsNode != null && callsNode.isArray()) {
                toolCalls.addAll(parseToolCalls(callsNode));
            }
            String finishReason = root.path("done_reason").asText("stop");
            TokenUsage usage = new TokenUsage(
                    root.path("prompt_eval_count").asLong(0),
                    root.path("eval_count").asLong(0));
            return new ChatResponse(Message.assistant(content, toolCalls), usage, finishReason);
        } catch (IOException e) {
            throw new FishAgentException("解析 Ollama 响应失败：" + body, e);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode callsNode) {
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode callNode : callsNode) {
            JsonNode functionNode = callNode.path("function");
            String name = functionNode.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String id = callNode.path("id").asText(null);
            calls.add(new ToolCall(id, name, toArguments(functionNode.get("arguments"))));
        }
        return calls;
    }

    /**
     * Ollama 不同版本可能返回对象或字符串形式的 arguments，这里统一成 Map。
     */
    private Map<String, Object> toArguments(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isNull()) {
            return Map.of();
        }
        if (argumentsNode.isTextual()) {
            String text = argumentsNode.asText().trim();
            if (text.isEmpty()) {
                return Map.of();
            }
            try {
                return Json.mapper().readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (IOException e) {
                return new LinkedHashMap<>(Map.of("input", text));
            }
        }
        if (argumentsNode.isObject()) {
            return Json.mapper().convertValue(argumentsNode, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        return new LinkedHashMap<>(Map.of("input", argumentsNode.asText()));
    }

    private HttpRequest newRequest(String path, String payload, boolean withTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (withTimeout) {
            builder.timeout(requestTimeout);
        }
        return builder.build();
    }

    private void ensureSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String detail = body == null || body.isBlank() ? "" : "，响应：" + body;
        throw FishAgentException.of("Ollama 请求失败，HTTP %d%s", statusCode, detail);
    }

    private FishAgentException connectionError(IOException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (message.contains("Connection refused") || message.contains("Failed to connect")) {
            return new FishAgentException(
                    "无法连接 Ollama（" + baseUrl + "）。请确认服务已启动：ollama serve", e);
        }
        return new FishAgentException("调用 Ollama 失败：" + message, e);
    }

    private static String normalizeBaseUrl(String url) {
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // ------------------------------------------------------------------ Builder

    public static final class Builder {

        private String baseUrl = DEFAULT_BASE_URL;
        private String model;
        private HttpClient httpClient;
        private Duration requestTimeout;
        private boolean sendToolName = true;
        private final Map<String, Object> defaultOptions = new LinkedHashMap<>();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder sendToolName(boolean sendToolName) {
            this.sendToolName = sendToolName;
            return this;
        }

        /**
         * 设置默认采样参数（如 temperature / top_p / num_ctx / seed）。
         * 请求级别的参数会覆盖此处的同名配置。
         */
        public Builder option(String key, Object value) {
            this.defaultOptions.put(key, value);
            return this;
        }

        public Builder options(Map<String, Object> options) {
            if (options != null) {
                this.defaultOptions.putAll(options);
            }
            return this;
        }

        public Builder temperature(double temperature) {
            this.defaultOptions.put("temperature", temperature);
            return this;
        }

        public OllamaChatModel build() {
            return new OllamaChatModel(this);
        }
    }
}
