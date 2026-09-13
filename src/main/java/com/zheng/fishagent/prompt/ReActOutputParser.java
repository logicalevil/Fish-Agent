package com.zheng.fishagent.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zheng.fishagent.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 ReAct 格式的文本输出。
 * <p>
 * 兼容常见的格式抖动：多余空行、Markdown 加粗（{@code **Action:** }）、中英文冒号、
 * 以及 Action Input 不是合法 JSON 的情况。
 */
public final class ReActOutputParser {

    /** 允许 Markdown 装饰：`**Action:**` / `Action：` 等写法都要能识别。 */
    private static final String LABEL_PREFIX = "(?im)^[\\s>*-]*";
    private static final String LABEL_SUFFIX = "\\s*[*_`]*\\s*[:：]\\s*[*_`]*\\s*";

    private static final Pattern FINAL_ANSWER = Pattern.compile(
            LABEL_PREFIX + "Final\\s*Answer" + LABEL_SUFFIX);

    private static final Pattern ACTION = Pattern.compile(
            LABEL_PREFIX + "Action" + LABEL_SUFFIX + "([A-Za-z0-9_.\\-]+)");

    private static final Pattern ACTION_INPUT = Pattern.compile(
            LABEL_PREFIX + "Action\\s*Input" + LABEL_SUFFIX);

    private static final Pattern THOUGHT = Pattern.compile(
            LABEL_PREFIX + "Thought" + LABEL_SUFFIX + "(.*)$");

    private static final Pattern SECTION_BREAK = Pattern.compile(
            LABEL_PREFIX + "(Observation|Thought|Action|Final\\s*Answer)" + LABEL_SUFFIX);

    private ReActOutputParser() {
    }

    public static ReActStep parse(String text) {
        if (text == null || text.isBlank()) {
            return new ReActStep.Unparsed("");
        }

        Matcher finalAnswer = FINAL_ANSWER.matcher(text);
        Matcher action = ACTION.matcher(text);

        boolean hasFinal = finalAnswer.find();
        int finalIndex = hasFinal ? finalAnswer.start() : -1;
        boolean hasAction = action.find();
        int actionIndex = hasAction ? action.start() : -1;

        // Action 出现在 Final Answer 之后，说明模型又开始了新一轮工具调用
        if (hasAction && (!hasFinal || actionIndex > finalIndex)) {
            String tool = action.group(1).trim();
            String rawInput = extractActionInput(text, action.end());
            return new ReActStep.ToolRequest(tool, parseArguments(rawInput), extractThought(text), rawInput);
        }

        if (hasFinal) {
            String answer = text.substring(finalAnswer.end()).trim();
            return new ReActStep.FinalAnswer(stripDecorations(answer), extractThought(text));
        }

        if (hasAction) {
            String tool = action.group(1).trim();
            String rawInput = extractActionInput(text, action.end());
            return new ReActStep.ToolRequest(tool, parseArguments(rawInput), extractThought(text), rawInput);
        }

        return new ReActStep.Unparsed(text.trim());
    }

    private static String extractActionInput(String text, int searchFrom) {
        Matcher matcher = ACTION_INPUT.matcher(text);
        if (!matcher.find(Math.max(0, searchFrom - 200)) && !matcher.find(0)) {
            return "";
        }
        int start = matcher.end();
        Matcher breakMatcher = SECTION_BREAK.matcher(text);
        int end = text.length();
        while (breakMatcher.find(start)) {
            if (breakMatcher.start() > start) {
                end = breakMatcher.start();
                break;
            }
        }
        return text.substring(start, end).trim();
    }

    private static String extractThought(String text) {
        Matcher matcher = THOUGHT.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * 把 Action Input 文本解析为参数 Map。
     * <p>
     * 依次尝试：JSON 对象 → JSON 数组 → JSON 标量 → 形如 {@code key=value} 的键值对 → 单参数兜底。
     */
    public static Map<String, Object> parseArguments(String raw) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return arguments;
        }
        String text = stripCodeFence(raw.trim());

        Map<String, Object> json = tryJsonObject(text);
        if (json != null) {
            return json;
        }

        try {
            Object value = Json.mapper().readValue(text, new TypeReference<Object>() {
            });
            if (value instanceof Map<?, ?> map) {
                map.forEach((k, v) -> arguments.put(String.valueOf(k), v));
                return arguments;
            }
            if (value != null && !text.startsWith("{")) {
                arguments.put("input", value);
                return arguments;
            }
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }

        Map<String, Object> pairs = tryKeyValuePairs(text);
        if (!pairs.isEmpty()) {
            return pairs;
        }

        arguments.put("input", text);
        return arguments;
    }

    private static Map<String, Object> tryJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int end = findBalancedEnd(text, start);
        if (end < 0) {
            return null;
        }
        try {
            Map<String, Object> parsed = Json.mapper().readValue(
                    text.substring(start, end + 1), new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 找到与起始花括号配对的结束位置，忽略字符串内部的花括号。
     */
    private static int findBalancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Map<String, Object> tryKeyValuePairs(String text) {
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                separator = trimmed.indexOf('：');
            }
            if (separator <= 0) {
                separator = trimmed.indexOf(':');
            }
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim().replace("\"", "");
            String value = trimmed.substring(separator + 1).trim().replace("\"", "");
            if (!key.isBlank()) {
                pairs.put(key, value);
            }
        }
        return pairs;
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String body = text.substring(firstLineEnd + 1);
        int fenceEnd = body.lastIndexOf("```");
        return fenceEnd < 0 ? body.trim() : body.substring(0, fenceEnd).trim();
    }

    private static String stripDecorations(String text) {
        return stripCodeFence(text).trim();
    }
}
