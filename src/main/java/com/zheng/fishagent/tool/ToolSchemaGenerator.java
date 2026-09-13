package com.zheng.fishagent.tool;

import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于反射把 Java 方法转换成模型可读的 JSON Schema，并负责入参类型转换。
 */
public final class ToolSchemaGenerator {

    private ToolSchemaGenerator() {
    }

    /**
     * 生成方法参数的 Object Schema。
     */
    public static Map<String, Object> forMethod(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name = resolveName(parameter, annotation, i);
            Map<String, Object> schema = forType(parameter.getParameterizedType());
            if (annotation != null) {
                String description = resolveDescription(annotation);
                if (!description.isBlank()) {
                    schema.put("description", description);
                }
                if (annotation.required()) {
                    required.add(name);
                }
            } else {
                required.add(name);
            }
            properties.put(name, schema);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        if (!required.isEmpty()) {
            root.put("required", required);
        }
        return root;
    }

    /**
     * 生成单个类型对应的 Schema。
     */
    public static Map<String, Object> forType(Type type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Class<?> raw = rawClass(type);

        if (raw == null || raw == Object.class) {
            schema.put("type", "string");
            return schema;
        }
        if (raw == String.class || CharSequence.class.isAssignableFrom(raw) || raw == Character.class || raw == char.class) {
            schema.put("type", "string");
            return schema;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
            return schema;
        }
        if (raw == byte.class || raw == short.class || raw == int.class || raw == long.class
                || raw == Byte.class || raw == Short.class || raw == Integer.class || raw == Long.class
                || raw == BigInteger.class) {
            schema.put("type", "integer");
            return schema;
        }
        if (raw == float.class || raw == double.class || raw == Float.class || raw == Double.class
                || raw == BigDecimal.class) {
            schema.put("type", "number");
            return schema;
        }
        if (raw.isEnum()) {
            schema.put("type", "string");
            List<String> values = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            schema.put("enum", values);
            return schema;
        }
        if (raw.isArray()) {
            schema.put("type", "array");
            schema.put("items", forType(raw.getComponentType()));
            return schema;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            schema.put("type", "array");
            Type elementType = elementTypeOf(type);
            schema.put("items", elementType == null ? Map.of("type", "string") : forType(elementType));
            return schema;
        }
        if (Map.class.isAssignableFrom(raw)) {
            schema.put("type", "object");
            return schema;
        }
        if (raw.isRecord()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                properties.put(component.getName(), forType(component.getGenericType()));
                required.add(component.getName());
            }
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    /**
     * 把模型返回的原始值转换成目标类型。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convert(Object value, Class<?> targetType) {
        if (value == null) {
            return defaultValue(targetType);
        }
        if (targetType.isInstance(value) && !(value instanceof String)) {
            return value;
        }
        if (targetType == String.class || CharSequence.class.isAssignableFrom(targetType)) {
            return String.valueOf(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean b) {
                return b;
            }
            return Boolean.parseBoolean(String.valueOf(value).trim());
        }
        if (targetType == int.class || targetType == Integer.class) {
            return toNumber(value).intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return toNumber(value).longValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return toNumber(value).shortValue();
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return toNumber(value).byteValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return toNumber(value).doubleValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return toNumber(value).floatValue();
        }
        if (targetType == BigDecimal.class) {
            return new BigDecimal(String.valueOf(value).trim());
        }
        if (targetType == BigInteger.class) {
            return new BigInteger(String.valueOf(value).trim());
        }
        if (targetType == Character.class || targetType == char.class) {
            String text = String.valueOf(value);
            return text.isEmpty() ? '\0' : text.charAt(0);
        }
        if (targetType.isEnum()) {
            String text = String.valueOf(value).trim();
            for (Object constant : targetType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                    return constant;
                }
            }
            throw FishAgentException.of("参数值 %s 不是枚举 %s 的合法取值", text, targetType.getSimpleName());
        }
        if (targetType.isArray() || Collection.class.isAssignableFrom(targetType)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return com.zheng.fishagent.util.Json.mapper().convertValue(map, targetType);
        }
        return value;
    }

    /**
     * 读取方法参数名：优先注解指定名，其次编译期保留的形参名。
     */
    public static String resolveName(Parameter parameter, ToolParam annotation, int index) {
        if (annotation != null && !annotation.name().isBlank()) {
            return annotation.name();
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        return "arg" + index;
    }

    private static String resolveDescription(ToolParam annotation) {
        if (!annotation.description().isBlank()) {
            return annotation.description();
        }
        return annotation.value();
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return rawClass(parameterized.getRawType());
        }
        return null;
    }

    private static Type elementTypeOf(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1) {
                return arguments[0];
            }
        }
        return null;
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw FishAgentException.of("参数值 %s 无法转换为数字", text);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return (byte) 0;
    }
}
