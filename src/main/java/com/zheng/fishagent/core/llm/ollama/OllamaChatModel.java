package com.zheng.fishagent.core.llm.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.core.llm.ChatModel;
import com.zheng.fishagent.core.llm.ChatRequest;
import com.zheng.fishagent.core.llm.ChatResponse;
import com.zheng.fishagent.core.llm.StreamListener;
import com.zheng.fishagent.core.llm.TokenUsage;
import com.zheng.fishagent.core.message.content.Content;
import com.zheng.fishagent.core.message.content.TextContent;
import com.zheng.fishagent.core.message.message.AssistantMessage;
import com.zheng.fishagent.core.message.message.Message;
import com.zheng.fishagent.core.message.Role;
import com.zheng.fishagent.core.message.ToolCall;
import com.zheng.fishagent.core.message.message.ToolMessage;
import com.zheng.fishagent.core.tool.ToolSpec;
import com.zheng.fishagent.core.util.Json;

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

    /**
     * 实际使用的服务地址（已去除末尾斜杠）。
     */
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

    /**
     * 流式调用入口：把真正的协议解析交给 {@link #doStream}，自身只负责监听器回调的编排。
     *
     * <p>职责边界：
     * <ul>
     *   <li>listener 为 {@code null} 时替换为 {@link StreamListener#NOOP}，后续无需判空；</li>
     *   <li>正常返回前触发 {@code onComplete}，异常时触发 {@code onError} 并原样重抛；</li>
     *   <li>catch 的是 {@link RuntimeException}，因为 {@code doStream} 已把受检异常统一转为
     *       {@link FishAgentException}。</li>
     * </ul>
     *
     * @param request  请求参数，内部会序列化为 {@code stream=true} 的 Ollama 请求体
     * @param listener 逐 token 回调的监听器，可为 {@code null}
     * @return 聚合后的完整响应（正文、工具调用、token 用量、结束原因）
     */
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

    /**
     * 流式协议解析器：读取 Ollama 返回的 NDJSON 流，逐行拆解并聚合出完整响应。
     *
     * <p>每行独立解析，四类信息分别处理：
     * <ol>
     *   <li>正文片段：累积到 {@code content}，同时通过 {@code onToken} 实时推给调用方；</li>
     *   <li>工具调用：出现在任意行，累加到 {@code toolCalls}（分批返回也能兼容）；</li>
     *   <li>结束行（{@code done=true}）：读取 {@code done_reason} 与 token 统计；</li>
     *   <li>内嵌错误（HTTP 200 但带 {@code error} 字段）：直接抛 {@link FishAgentException}。</li>
     * </ol>
     *
     * <p>与同步路径 {@code call} 的差异：请求不带总超时（流可能持续很久），
     * 响应体按行消费，结束时统一组装为 {@link ChatResponse}。
     *
     * @param request  请求参数
     * @param callback 已做空值兜底的监听器
     * @return 聚合后的完整响应
     * @throws FishAgentException 连接失败、被中断、或 Ollama 返回业务错误
     */
    private ChatResponse doStream(ChatRequest request, StreamListener callback) {
        // stream=true：让 Ollama 以 NDJSON 逐行返回，而非一次性返回完整 JSON
        String payload = Json.write(buildBody(request, true));
        // withTimeout=false：流式响应可能持续很久，不设请求总超时
        HttpRequest httpRequest = newRequest("/api/chat", payload, false);

        // 流式响应是碎片化的，以下变量用于跨行累积最终结果
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        long promptTokens = 0;
        long completionTokens = 0;
        String finishReason = "stop";

        try {
            HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                // HTTP 错误时响应体是错误说明而非 NDJSON，先读全再交给 ensureSuccess 抛出
                String errorBody;
                try (Stream<String> errorLines = response.body()) {
                    errorBody = errorLines.collect(Collectors.joining("\n"));
                }
                ensureSuccess(response.statusCode(), errorBody);
            }
            // try-with-resources 保证流关闭，避免连接泄漏
            try (Stream<String> lines = response.body()) {
                // 用 Iterator 而非 forEach：循环体内需要抛异常，lambda 中处理不便
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next().trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    JsonNode chunk = Json.mapper().readTree(line);
                    if (chunk.hasNonNull("error")) {
                        // Ollama 可能以 HTTP 200 返回 {"error": "..."} 表示业务失败
                        throw FishAgentException.of("Ollama 返回错误：%s", chunk.get("error").asText());
                    }
                    JsonNode messageNode = chunk.get("message");
                    if (messageNode != null && !messageNode.isNull()) {
                        JsonNode contentNode = messageNode.get("content");
                        if (contentNode != null && !contentNode.isNull()) {
                            // 每行的 content 是增量 token（非累积全文），需要自行拼接
                            String token = contentNode.asText();
                            if (!token.isEmpty()) {
                                content.append(token);
                                callback.onToken(token);
                            }
                        }
                        JsonNode callsNode = messageNode.get("tool_calls");
                        if (callsNode != null && callsNode.isArray()) {
                            // 工具调用可能分批出现在多行，用 addAll 累加
                            toolCalls.addAll(parseToolCalls(callsNode));
                        }
                    }
                    if (chunk.path("done").asBoolean(false)) {
                        // done=true 是最后一行，只有它携带结束原因和 token 统计
                        String reason = chunk.path("done_reason").asText("");
                        if (!reason.isBlank()) {
                            finishReason = reason;
                        }
                        // 以当前值作默认：字段缺失时保留已有值而非覆盖成 0
                        promptTokens = chunk.path("prompt_eval_count").asLong(promptTokens);
                        completionTokens = chunk.path("eval_count").asLong(completionTokens);
                    }
                }
            }
        } catch (IOException e) {
            throw connectionError(e);
        } catch (InterruptedException e) {
            // 阻塞方法抛异常前会清除中断标记，这里恢复后上报，避免中断信号丢失
            Thread.currentThread().interrupt();
            throw new FishAgentException("流式调用 Ollama 被中断", e);
        }

        return new ChatResponse(
                new AssistantMessage(List.of(new TextContent(content.toString())), toolCalls),
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
        String content = message.contentList().stream()
                .map(Content::text)
                .collect(Collectors.joining(""));
        wire.put("content", content);
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            List<ToolCall> toolCalls = assistant.toolCallsCopyOnWrite();
            List<Map<String, Object>> calls = new ArrayList<>(toolCalls.size());
            for (ToolCall call : toolCalls) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", call.arguments());
                calls.add(Map.of("function", function));
            }
            wire.put("tool_calls", calls);
        }
        if (message.role() == Role.TOOL && sendToolName && ToolMessage.toolName(message) != null) {
            wire.put("tool_name", ToolMessage.toolName(message));
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
            return new ChatResponse(new AssistantMessage(List.of(new TextContent(content)), toolCalls), usage, finishReason);
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

        /**
         * Ollama 服务地址，默认 {@link #DEFAULT_BASE_URL}。末尾多余的 {@code /} 会被自动去除。
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 必填项：模型名，例如 {@code qwen2.5:7b}。
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 自定义 HTTP 客户端。缺省时框架自建一个（连接超时 10 秒、强制 HTTP/1.1）。
         * <p>
         * 需要统一代理、连接池或超时策略时在此注入。
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * 同步调用的超时时间，默认 5 分钟。流式调用不受此限制。
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * 是否在 {@code tool} 角色消息中附带 {@code tool_name} 字段，默认开启。
         * <p>
         * 仅在与要求该字段的特定 Ollama 版本配合时才需要调整。
         */
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

        /**
         * 批量设置默认采样参数，语义同 {@link #option(String, Object)}。
         */
        public Builder options(Map<String, Object> options) {
            if (options != null) {
                this.defaultOptions.putAll(options);
            }
            return this;
        }

        /**
         * 设置默认采样温度，等价于 {@code option("temperature", temperature)}。
         */
        public Builder temperature(double temperature) {
            this.defaultOptions.put("temperature", temperature);
            return this;
        }

        /**
         * 组装模型实例。
         *
         * @throws NullPointerException 未调用 {@link #model(String)} 时抛出
         */
        public OllamaChatModel build() {
            return new OllamaChatModel(this);
        }
    }
}
