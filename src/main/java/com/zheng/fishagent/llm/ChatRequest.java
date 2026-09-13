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

    public List<Message> messages() {
        return messages;
    }

    public List<ToolSpec> tools() {
        return tools;
    }

    public Double temperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public List<String> stop() {
        return stop;
    }

    public Map<String, Object> extraOptions() {
        return extraOptions;
    }

    public boolean isStream() {
        return stream;
    }

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

        public Builder messages(List<Message> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder message(Message message) {
            this.messages.add(message);
            return this;
        }

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

        public Builder stop(List<String> stop) {
            this.stop.clear();
            this.stop.addAll(stop);
            return this;
        }

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
