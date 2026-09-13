package com.zheng.fishagent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具的对外描述（用于上报给模型的 JSON Schema）。
 *
 * @param name        工具名，需唯一
 * @param description 工具用途描述，直接决定模型能否正确选择工具
 * @param parameters  JSON Schema 形式的参数定义
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    public ToolSpec {
        Objects.requireNonNull(name, "工具名不能为空");
        description = description == null ? "" : description;
        parameters = parameters == null
                ? Map.of("type", "object", "properties", Map.of())
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
