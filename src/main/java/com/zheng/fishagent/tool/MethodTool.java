package com.zheng.fishagent.tool;

import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.tool.annotation.ToolParam;
import com.zheng.fishagent.util.Json;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 由 {@link com.zheng.fishagent.tool.annotation.AgentTool} 注解方法生成的工具。
 */
public final class MethodTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final Object target;
    private final Method method;

    private MethodTool(String name, String description, Map<String, Object> parameters, Object target, Method method) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.target = target;
        this.method = method;
    }

    public static MethodTool from(Object target, Method method, String name, String description) {
        Objects.requireNonNull(method, "方法不能为空");
        method.setAccessible(true);
        Map<String, Object> schema = ToolSchemaGenerator.forMethod(method);
        String toolName = (name == null || name.isBlank()) ? method.getName() : name;
        return new MethodTool(toolName, description, schema, target, method);
    }

    public Method method() {
        return method;
    }

    public Object target() {
        return target;
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
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            ToolParam annotation = parameters[i].getAnnotation(ToolParam.class);
            String paramName = ToolSchemaGenerator.resolveName(parameters[i], annotation, i);
            Object raw = args.get(paramName);
            values[i] = ToolSchemaGenerator.convert(raw, parameters[i].getType());
        }
        try {
            return stringify(method.invoke(java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null : target, values));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw FishAgentException.wrap("工具 " + name + " 执行异常", cause == null ? e : cause);
        } catch (IllegalAccessException e) {
            throw FishAgentException.wrap("工具 " + name + " 无法访问", e);
        }
    }

    private static String stringify(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof String text) {
            return text;
        }
        if (result instanceof Number || result instanceof Boolean || result instanceof Character) {
            return String.valueOf(result);
        }
        if (result instanceof Map<?, ?> map) {
            return Json.write(new LinkedHashMap<>(map));
        }
        if (result.getClass().isRecord() || !result.getClass().getName().startsWith("java.")) {
            return Json.write(result);
        }
        return String.valueOf(result);
    }
}
