package com.zheng.fishagent.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 模型请求调用某个工具的意图。
 *
 * @param id        调用标识，模型未提供时自动生成
 * @param name      工具名
 * @param arguments 工具入参（已解析为 Map）
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall(null, name, arguments);
    }

    public Object argument(String key) {
        return arguments.get(key);
    }

    public String stringArgument(String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public String toString() {
        return name + "(" + arguments + ")";
    }
}
