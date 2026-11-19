package com.zheng.fishagent.tool;

import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.annotation.AgentTool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：负责收集工具、上报 Schema、并把模型的调用路由到具体实现。
 */
public final class ToolRegistry {

    private static final java.util.Set<String> GENERIC_PARAM_NAMES = java.util.Set.of(
            "input", "action_input", "argument", "arguments", "arg", "query", "value", "params", "request");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 注册一个工具。
     * <p>
     * {@code null} 被静默忽略；工具名重复时抛 {@link FishAgentException}（快速失败，
     * 避免模型在重名工具间产生歧义）。
     */
    public ToolRegistry register(Tool tool) {
        if (tool == null) {
            return this;
        }
        Tool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw FishAgentException.of("工具名重复：%s", tool.name());
        }
        return this;
    }

    /**
     * 批量注册工具，按数组顺序依次调用 {@link #register(Tool)}。
     */
    public ToolRegistry register(Tool... tools) {
        for (Tool tool : tools) {
            register(tool);
        }
        return this;
    }

    /**
     * 扫描对象中被 {@link AgentTool} 标注的方法并注册为工具。
     */
    public ToolRegistry register(Object bean) {
        if (bean == null) {
            return this;
        }
        if (bean instanceof Tool tool) {
            return register(tool);
        }
        List<Method> methods = new ArrayList<>();
        Class<?> type = bean.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (method.isAnnotationPresent(AgentTool.class)) {
                    methods.add(method);
                }
            }
            type = type.getSuperclass();
        }
        if (methods.isEmpty()) {
            throw FishAgentException.of("类 %s 中未找到标注 @AgentTool 的方法", bean.getClass().getName());
        }
        for (Method method : methods) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            String description = annotation.description().isBlank() ? annotation.value() : annotation.description();
            register(MethodTool.from(bean, method, annotation.name(), description));
        }
        return this;
    }

    /**
     * 按名查找工具。
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 是否存在指定名称的工具。
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 返回所有工具，顺序与注册顺序一致。
     */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * 返回所有工具的对外描述，供模型选择工具时使用。
     */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /**
     * 是否尚未注册任何工具。为空时 Agent 退化为纯对话。
     */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 已注册的工具数量。
     */
    public int size() {
        return tools.size();
    }

    /**
     * 容错处理：模型有时会用 input / query 之类的通用名传参，
     * 若目标工具只声明了一个参数，这里自动把参数名纠正过来。
     */
    public ToolCall normalize(ToolCall call) {
        Map<String, Object> arguments = call.arguments();
        if (arguments.size() != 1) {
            return call;
        }
        Optional<Tool> tool = find(call.name());
        if (tool.isEmpty()) {
            return call;
        }
        Object properties = tool.get().parameters().get("properties");
        if (!(properties instanceof Map<?, ?> propertyMap) || propertyMap.size() != 1) {
            return call;
        }
        String declaredName = String.valueOf(propertyMap.keySet().iterator().next());
        String providedName = String.valueOf(arguments.keySet().iterator().next());
        if (declaredName.equals(providedName) || !GENERIC_PARAM_NAMES.contains(providedName.toLowerCase())) {
            return call;
        }
        Map<String, Object> remapped = new LinkedHashMap<>();
        remapped.put(declaredName, arguments.get(providedName));
        return new ToolCall(call.id(), call.name(), remapped);
    }

    /**
     * 执行一次工具调用。工具内部异常不会向上抛出，而是转成错误文本回填给模型，
     * 让模型有机会自我纠正。
     */
    public String execute(ToolCall call) {
        Optional<Tool> tool = find(call.name());
        if (tool.isEmpty()) {
            return "错误：不存在名为 " + call.name() + " 的工具，可用工具为 " + tools.keySet();
        }
        try {
            String result = tool.get().execute(call.arguments());
            return result == null ? "" : result;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
