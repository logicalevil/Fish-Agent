package com.zheng.fishagent.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对话消息。不可变对象，通过静态工厂或 {@link Builder} 创建。
 */
public final class Message {

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolName;

    private Message(Builder builder) {
        this.role = Objects.requireNonNull(builder.role, "role 不能为空");
        this.content = builder.content == null ? "" : builder.content;
        this.toolCalls = builder.toolCalls == null ? List.of() : List.copyOf(builder.toolCalls);
        this.toolName = builder.toolName;
    }

    /**
     * 系统提示词消息。框架不会把系统提示词写入记忆，此工厂主要用于自定义场景。
     */
    public static Message system(String content) {
        return builder(Role.SYSTEM).content(content).build();
    }

    /**
     * 用户消息。
     */
    public static Message user(String content) {
        return builder(Role.USER).content(content).build();
    }

    /**
     * 不含工具调用的助手消息（通常即最终答案）。
     */
    public static Message assistant(String content) {
        return builder(Role.ASSISTANT).content(content).build();
    }

    /**
     * 携带工具调用的助手消息。{@code content} 可为空串（部分模型只返回工具调用）。
     */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return builder(Role.ASSISTANT).content(content).toolCalls(toolCalls).build();
    }

    /**
     * 工具结果消息（原生模式使用）。
     *
     * @param toolName 产生该结果的工具名
     * @param content  工具的返回值文本
     */
    public static Message tool(String toolName, String content) {
        return builder(Role.TOOL).toolName(toolName).content(content).build();
    }

    /**
     * 以指定角色创建构建器。
     */
    public static Builder builder(Role role) {
        return new Builder(role);
    }

    /**
     * 消息角色。
     */
    public Role role() {
        return role;
    }

    /**
     * 正文；为 {@code null} 时规范化为空串。
     */
    public String content() {
        return content;
    }

    /**
     * 本消息请求的工具调用；非助手消息恒为空列表。
     */
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    /**
     * 工具结果消息对应的工具名；仅 {@code TOOL} 角色非 {@code null}。
     */
    public String toolName() {
        return toolName;
    }

    /**
     * 是否携带至少一个工具调用。
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 正文空白且无工具调用。
     */
    public boolean isBlank() {
        return content.isBlank() && toolCalls.isEmpty();
    }

    /**
     * 序列化为可持久化 / 可传输的 Map 结构。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role.value());
        map.put("content", content);
        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>(toolCalls.size());
            for (ToolCall call : toolCalls) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", call.id());
                item.put("name", call.name());
                item.put("arguments", call.arguments());
                calls.add(item);
            }
            map.put("toolCalls", calls);
        }
        if (toolName != null) {
            map.put("toolName", toolName);
        }
        return map;
    }

    /**
     * 从 {@link #toMap()} 的输出还原消息。
     */
    @SuppressWarnings("unchecked")
    public static Message fromMap(Map<String, Object> map) {
        Role role = Role.fromValue(String.valueOf(map.get("role")));
        Builder builder = builder(role);
        Object content = map.get("content");
        builder.content(content == null ? "" : String.valueOf(content));
        Object toolName = map.get("toolName");
        if (toolName != null) {
            builder.toolName(String.valueOf(toolName));
        }
        Object calls = map.get("toolCalls");
        if (calls instanceof List<?> list) {
            List<ToolCall> parsed = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> callMap = (Map<String, Object>) raw;
                    Object arguments = callMap.get("arguments");
                    parsed.add(new ToolCall(
                            callMap.get("id") == null ? null : String.valueOf(callMap.get("id")),
                            String.valueOf(callMap.get("name")),
                            arguments instanceof Map<?, ?> argMap
                                    ? new LinkedHashMap<>((Map<String, Object>) argMap)
                                    : Map.of()));
                }
            }
            builder.toolCalls(parsed);
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message other)) {
            return false;
        }
        return role == other.role
                && content.equals(other.content)
                && toolCalls.equals(other.toolCalls)
                && Objects.equals(toolName, other.toolName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, toolCalls, toolName);
    }

    @Override
    public String toString() {
        return "Message[role=" + role.value()
                + ", content=" + (content.length() > 200 ? content.substring(0, 200) + "..." : content)
                + (toolCalls.isEmpty() ? "" : ", toolCalls=" + toolCalls)
                + (toolName == null ? "" : ", toolName=" + toolName)
                + "]";
    }

    public static final class Builder {

        private final Role role;
        private String content;
        private List<ToolCall> toolCalls;
        private String toolName;

        private Builder(Role role) {
            this.role = role;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder toolCall(ToolCall toolCall) {
            if (this.toolCalls == null) {
                this.toolCalls = new ArrayList<>();
            }
            this.toolCalls.add(toolCall);
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }

    /**
     * 返回一个不可变副本列表，便于框架内部传递上下文。
     */
    public static List<Message> immutableCopy(List<Message> messages) {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
