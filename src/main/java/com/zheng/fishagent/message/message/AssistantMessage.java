package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.message.constant.MessageKeys;
import com.zheng.fishagent.message.content.Content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AssistantMessage extends BaseMessage {

    /*工具消息特有的数据结构，标明某一次工具的调用*/
    private final List<ToolCall> toolCalls;      // ← 子类特有

    public AssistantMessage(List<Content> content, List<ToolCall> toolCalls) {
        super(Role.ASSISTANT, content);
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
    /*返回工具调用标识的结果*/
    public List<ToolCall> toolCallsCopyOnWrite() {
        return new ArrayList<>(this.toolCalls);
    }
    /*返回是否有工具调用*/
    public boolean hasToolCalls(){
        return !toolCalls.isEmpty();
    }


    /**
     * 暴露基类的纯文本提取，供调用方读取正文。
     * 媒体块返回空串，相当于被忽略。
     */
    public String text() {
        return super.text();
    }
    /**
     * 在公共字段之上追加 {@code toolCalls}；无工具调用时不写该键。
     * 每个调用序列化为 {@code {id, name, arguments}}。
     */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        if (toolCalls.isEmpty()) {
            return map;
        }
        List<Map<String, Object>> calls = new ArrayList<>(toolCalls.size());
        for (ToolCall call : toolCalls) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", call.id());
            entry.put("name", call.name());
            entry.put("arguments", call.arguments());
            calls.add(entry);
        }
        map.put(MessageKeys.TOOL_CALLS, calls);
        return map;
    }

    /**
     * 从映射还原助手消息，读取 {@code toolCalls} 中每个调用的
     * {@code id} / {@code name} / {@code arguments}；缺少 {@code name} 的条目被跳过。
     */
    public static AssistantMessage fromMap(Map<String, Object> map) {
        return new AssistantMessage(contentsOf(map), toolCallsOf(map.get(MessageKeys.TOOL_CALLS)));
    }

    private static List<ToolCall> toolCallsOf(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> entry = mapValue(item);
            String name = stringValue(entry.get("name"));
            if (name == null) {
                continue;
            }
            calls.add(new ToolCall(stringValue(entry.get("id")), name, mapValue(entry.get("arguments"))));
        }
        return calls;
    }


    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        AssistantMessage that = (AssistantMessage) o;
        return toolCalls.equals(that.toolCalls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), toolCalls);
    }

    @Override
    public String toString() {
        return "AssistantMessage[contents=" + this.contentList() + ", toolCalls=" + toolCalls + "]";
    }

    @Override
    public String allMessage() {
        return "";
    }
}
