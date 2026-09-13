package com.zheng.fishagent.tool;

import com.zheng.fishagent.core.FishAgentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 以 Lambda 方式声明的工具，适合轻量、动态的场景。
 * <pre>{@code
 * Tool tool = FunctionTool.builder()
 *         .name("get_weather")
 *         .description("查询指定城市的天气")
 *         .parameter("city", "string", "城市名", true)
 *         .function(args -> http.get("/weather?city=" + args.get("city")))
 *         .build();
 * }</pre>
 */
public final class FunctionTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final Function<Map<String, Object>, String> function;

    private FunctionTool(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "工具名不能为空");
        this.description = builder.description == null ? "" : builder.description;
        this.parameters = Map.copyOf(builder.schema);
        this.function = Objects.requireNonNull(builder.function, "工具执行函数不能为空");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FunctionTool of(String name, String description, Function<Map<String, Object>, String> function) {
        return builder().name(name).description(description).function(function).build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String result = function.apply(arguments == null ? Map.of() : arguments);
            return result == null ? "" : result;
        } catch (RuntimeException e) {
            throw FishAgentException.wrap("工具 " + name + " 执行失败", e);
        }
    }

    public static final class Builder {

        private String name;
        private String description;
        private Function<Map<String, Object>, String> function;
        private final Map<String, Object> schema = new LinkedHashMap<>();
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Builder() {
            schema.put("type", "object");
            schema.put("properties", properties);
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder function(Function<Map<String, Object>, String> function) {
            this.function = function;
            return this;
        }

        /**
         * 声明一个参数。
         *
         * @param name       参数名
         * @param type       JSON Schema 类型：string / integer / number / boolean / array / object
         * @param description 参数说明
         * @param isRequired 是否必填
         */
        public Builder parameter(String name, String type, String description, boolean isRequired) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", type == null ? "string" : type);
            if (description != null && !description.isBlank()) {
                property.put("description", description);
            }
            properties.put(name, property);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        public Builder parameter(String name, String type, String description) {
            return parameter(name, type, description, true);
        }

        /**
         * 直接覆盖完整的参数 Schema。
         */
        public Builder parameters(Map<String, Object> parameters) {
            this.properties.clear();
            this.required.clear();
            this.schema.clear();
            this.schema.putAll(parameters);
            Object props = parameters.get("properties");
            if (props instanceof Map<?, ?> map) {
                map.forEach((k, v) -> properties.put(String.valueOf(k), v));
            }
            Object req = parameters.get("required");
            if (req instanceof List<?> list) {
                list.forEach(item -> required.add(String.valueOf(item)));
            }
            return this;
        }

        public FunctionTool build() {
            if (!required.isEmpty()) {
                schema.put("required", List.copyOf(required));
            }
            return new FunctionTool(this);
        }
    }
}
