package com.zheng.fishagent.llm;

import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次模型调用请求。不可变对象，使用 {@link #builder()} 构建。
 */
public final class ChatRequest {

    private final List<Message> messages;
    private final List<ToolSpec> tools;
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final List<String> stop;
    private final Map<String, Object> extraOptions;
    private final boolean stream;

    private ChatRequest(Builder builder) {
        this.messages = List.copyOf(builder.messages);
        this.tools = List.copyOf(builder.tools);
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.stop = List.copyOf(builder.stop);
        this.extraOptions = Map.copyOf(builder.extraOptions);
        this.stream = builder.stream;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 上下文消息，顺序为「系统提示词 → 历史消息」。
     */
    public List<Message> messages() {
        return messages;
    }

    /**
     * 下发给模型的工具描述。原生模式非空，文本模式恒为空（工具写在提示词里）。
     */
    public List<ToolSpec> tools() {
        return tools;
    }

    /**
     * 采样温度；为 {@code null} 表示不下发，由模型侧默认值决定。
     */
    public Double temperature() {
        return temperature;
    }

    /**
     * 核采样参数；为 {@code null} 表示不下发。
     */
    public Double topP() {
        return topP;
    }

    /**
     * 输出 token 上限；为 {@code null} 表示不下发。
     */
    public Integer maxTokens() {
        return maxTokens;
    }

    /**
     * 停止词序列。
     */
    public List<String> stop() {
        return stop;
    }

    /**
     * 透传给厂商的额外参数（Ollama 下合并进 {@code options} 字段）。
     */
    public Map<String, Object> extraOptions() {
        return extraOptions;
    }

    /**
     * 本次调用是否走流式。
     */
    public boolean isStream() {
        return stream;
    }

    /**
     * 基于当前请求创建一个预填充的 Builder，便于派生微调后的请求。
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.messages.addAll(messages);
        builder.tools.addAll(tools);
        builder.temperature = temperature;
        builder.topP = topP;
        builder.maxTokens = maxTokens;
        builder.stop.addAll(stop);
        builder.extraOptions.putAll(extraOptions);
        builder.stream = stream;
        return builder;
    }

    public static final class Builder {

        private final List<Message> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private final List<String> stop = new ArrayList<>();
        private final Map<String, Object> extraOptions = new LinkedHashMap<>();
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private boolean stream;

        private Builder() {
        }

        /**
         * 替换整个消息列表（会清空已有消息）。
         */
        public Builder messages(List<Message> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        /**
         * 追加一条消息。
         */
        public Builder message(Message message) {
            this.messages.add(message);
            return this;
        }

        /**
         * 替换整个工具列表（会清空已有工具）。
         */
        public Builder tools(List<ToolSpec> tools) {
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 替换停止词列表（会清空已有值）。
         */
        public Builder stop(List<String> stop) {
            this.stop.clear();
            this.stop.addAll(stop);
            return this;
        }

        /**
         * 追加一个厂商私有参数。
         */
        public Builder option(String key, Object value) {
            this.extraOptions.put(key, value);
            return this;
        }

        /**
         * 透传厂商私有参数（会合并进 Ollama 的 options 字段）。
         */
        public Builder options(Map<String, Object> options) {
            if (options != null) {
                this.extraOptions.putAll(options);
            }
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}
