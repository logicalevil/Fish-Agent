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
 * ReAct 模式下模型被要求按下面的格式作答：
 * <pre>
 * Thought: 我需要先算一下
 * Action: multiply
 * Action Input: {"a": 6, "b": 7}
 * </pre>
 * 或者给出最终答案：
 * <pre>
 * Final Answer: 结果是 42
 * </pre>
 * 本类负责把这段文本判定为 {@link ReActStep} 的三个分支之一（工具调用 / 最终答案 / 无法识别），
 * 并从中拆出工具名、参数、思考内容。
 * <p>
 * 兼容常见的格式抖动：多余空行、Markdown 加粗（{@code **Action:** }）、中英文冒号、
 * 以及 Action Input 不是合法 JSON 的情况。
 *
 * @see ReActStep
 */
public final class ReActOutputParser {

    /*
     * 标签（Thought: / Action: / Final Answer:）在模型输出里会长得很不一样，
     * 为了不写 6 份重复的正则，这里把「标签前」和「标签后」抽成两个可复用的片段。
     *
     * LABEL_PREFIX 解释： (?im)^[\s>*-]*
     *   (?i)   忽略大小写，模型写 action / ACTION 都能认
     *   (?m)   多行模式，^ 表示「每一行的开头」而不只是全文开头
     *   ^      行首
     *   [\s>*-]* 允许行首出现空白、引用符 >、列表符 -、以及 ** 加粗产生的 *
     *
     * LABEL_SUFFIX 解释： \s*[*_`]*\s*[:：]\s*[*_`]*\s*
     *   \s*     可能有的空白
     *   [*_`]*  可能包着标签的 Markdown 装饰（**粗体**、_斜体_、`代码`）
     *   [:：]   英文冒号或中文全角冒号，模型两种都会写
     *   末尾同理，再吃掉装饰与空白
     */
    private static final String LABEL_PREFIX = "(?im)^[\\s>*-]*";
    private static final String LABEL_SUFFIX = "\\s*[*_`]*\\s*[:：]\\s*[*_`]*\\s*";

    /** 匹配 {@code Final Answer:} 标签本身（不关心后面内容）。 */
    private static final Pattern FINAL_ANSWER = Pattern.compile(
            LABEL_PREFIX + "Final\\s*Answer" + LABEL_SUFFIX);

    /**
     * 匹配 {@code Action: 工具名}，group(1) 就是工具名。
     * <p>
     * 字符集 {@code [A-Za-z0-9_.\-]} 限定了工具名允许的字符，同时天然截断，
     * 不会把工具名后面的换行、括号之类吞进来。
     */
    private static final Pattern ACTION = Pattern.compile(
            LABEL_PREFIX + "Action" + LABEL_SUFFIX + "([A-Za-z0-9_.\\-]+)");

    /**
     * 匹配 {@code Action Input:} 标签本身，参数体从标签之后开始。
     * <p>
     * 注意 {@code Action\s*Input} 中间允许空白，是为了兼容 {@code ActionInput:} 这类少写空格的抖动；
     * 由于 ACTION 的字符集不含空格，两处不会互相误匹配。
     */
    private static final Pattern ACTION_INPUT = Pattern.compile(
            LABEL_PREFIX + "Action\\s*Input" + LABEL_SUFFIX);

    /** 匹配 {@code Thought:} 并捕获其后到行尾的内容，group(1) 为思考文本。 */
    private static final Pattern THOUGHT = Pattern.compile(
            LABEL_PREFIX + "Thought" + LABEL_SUFFIX + "(.*)$");

    /**
     * 匹配任意一个段落标签，用于判断「参数在哪里结束」。
     * <p>
     * 模型偶尔会把下一步的标签直接跟在参数后面而不换行，靠它把参数截断。
     */
    private static final Pattern SECTION_BREAK = Pattern.compile(
            LABEL_PREFIX + "(Observation|Thought|Action|Final\\s*Answer)" + LABEL_SUFFIX);

    private ReActOutputParser() {
    }

    /**
     * 解析一次模型输出。
     * <p>
     * 判定顺序有讲究，见各分支注释。
     *
     * @param text 模型的原始输出，可为 {@code null}
     * @return 三选一的结果；空输入与格式不符都会落到 {@link Unparsed}
     */
    public static ReActStep parse(String text) {
        if (text == null || text.isBlank()) {
            return new Unparsed("");
        }

        // Matcher 是可变的游标对象：find() 成功后，end()/start()/group() 才有效。
        Matcher finalAnswer = FINAL_ANSWER.matcher(text);
        Matcher action = ACTION.matcher(text);

        // 先各自定位一次，记录标签在全文中的起始下标，后面用来判断谁在前谁在后。
        boolean hasFinal = finalAnswer.find();
        int finalIndex = hasFinal ? finalAnswer.start() : -1;
        boolean hasAction = action.find();
        int actionIndex = hasAction ? action.start() : -1;

        /*
         * 分支一：Action 出现在 Final Answer 之后（或压根没有 Final Answer）。
         * 前后都出现时以「后面的 Action」为准，因为模型可能先说了答案又补充去调工具，
         * 此时继续执行工具更符合它的最新意图。
         */
        if (hasAction && (!hasFinal || actionIndex > finalIndex)) {
            String tool = action.group(1).trim();
            // 参数从 Action 标签之后开始找，避免误匹配到 Action 这一行本身的冒号。
            String rawInput = extractActionInput(text, action.end());
            return new ToolRequest(tool, parseArguments(rawInput), extractThought(text), rawInput);
        }

        // 分支二：只有 Final Answer，截取标签之后的全部内容作为回答正文。
        if (hasFinal) {
            String answer = text.substring(finalAnswer.end()).trim();
            return new FinalAnswer(stripDecorations(answer), extractThought(text));
        }

        /*
         * 分支三：走不到这里的情况——同时存在 Final 与 Action，且 Action 在前，
         * 但上一分支已经处理了「Action 在后」，所以这里实际是 Action 在前但被 Final 盖过的场景，
         * 仍按工具调用处理，保持与分支一一致的解析结果。
         */
        if (hasAction) {
            String tool = action.group(1).trim();
            String rawInput = extractActionInput(text, action.end());
            return new ToolRequest(tool, parseArguments(rawInput), extractThought(text), rawInput);
        }

        // 分支四：什么标签都没有。不报错，交给上层把原文当答案兜底，避免丢失模型产出。
        return new Unparsed(text.trim());
    }

    /**
     * 截取 {@code Action Input:} 之后的参数文本。
     * <p>
     * 结束位置取「下一个段落标签」或文本末尾，因此参数可以是多行 JSON。
     *
     * @param text       完整输出
     * @param searchFrom Action 标签的结束位置，从这里往后找 Action Input
     */
    private static String extractActionInput(String text, int searchFrom) {
        Matcher matcher = ACTION_INPUT.matcher(text);
        /*
         * 两步查找的用意：
         * 1. 先从 Action 附近（往前留 200 字符）往后找，正常情况下 Action Input 就紧跟其后；
         * 2. 找不到再从头找一次，兼容模型把 Action Input 写在 Action 之前等反常顺序。
         * 注意 find(int) 是「重置并从头找」，所以第二个条件是完整的全局兜底而非续找。
         */
        if (!matcher.find(Math.max(0, searchFrom - 200)) && !matcher.find(0)) {
            return "";
        }
        int start = matcher.end();
        Matcher breakMatcher = SECTION_BREAK.matcher(text);
        int end = text.length();
        // 找到第一个「起点在参数体之后」的段落标签，参数就到此为止。
        while (breakMatcher.find(start)) {
            if (breakMatcher.start() > start) {
                end = breakMatcher.start();
                break;
            }
        }
        return text.substring(start, end).trim();
    }

    /**
     * 提取 {@code Thought:} 后面的思考文本；没有该标签时返回空串。
     */
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
     * 模型给参数的写法五花八门，这里按「越结构化的越优先」依次尝试：
     * <ol>
     *   <li>文本中夹着一个 JSON 对象 → 直接采用（最常见，见 {@link #tryJsonObject}）</li>
     *   <li>整段是合法 JSON（数组 / 标量 / 对象）→ 标量统一包进 {@code input} 键</li>
     *   <li>{@code key=value} 或 {@code key: value} 逐行键值对</li>
     *   <li>都不行 → 把原文塞进 {@code input} 键兜底，至少不丢信息</li>
     * </ol>
     * 任何情况都不抛异常：参数问题由工具层报告，解析器只负责尽力而为。
     */
    public static Map<String, Object> parseArguments(String raw) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return arguments;
        }
        // 先剥掉 ```json 代码围栏，模型经常不带脑子地包一层。
        String text = stripCodeFence(raw.trim());

        // 尝试一：文本中间藏着一个 JSON 对象（允许前后有说明文字）。
        Map<String, Object> json = tryJsonObject(text);
        if (json != null) {
            return json;
        }

        /*
         * 尝试二：整段就是合法 JSON。
         * 能走到这里说明没有可用的 JSON 对象，所以命中时可能是数组或数字/字符串这类标量。
         */
        try {
            Object value = Json.mapper().readValue(text, new TypeReference<Object>() {
            });
            if (value instanceof Map<?, ?> map) {
                // 理论上被尝试一覆盖，保留是为了兼容极端键类型。
                map.forEach((k, v) -> arguments.put(String.valueOf(k), v));
                return arguments;
            }
            if (value != null && !text.startsWith("{")) {
                /*
                 * 标量或数组：工具的参数是「名字 → 值」的映射，这里没有名字可用，
                 * 于是用约定的 input 键承载，单参数工具正好能直接取到。
                 * 排除以 { 开头的情况，是因为那说明是个坏掉的 JSON 对象，应该继续往下兜底而不是当成标量。
                 */
                arguments.put("input", value);
                return arguments;
            }
        } catch (Exception ignored) {
            // 不是合法 JSON，继续尝试其它格式
        }

        // 尝试三：形如 a=6 / b: 7 的逐行键值对。
        Map<String, Object> pairs = tryKeyValuePairs(text);
        if (!pairs.isEmpty()) {
            return pairs;
        }

        // 尝试四：都不匹配。原文放进 input，交给工具自己判断是否可用。
        arguments.put("input", text);
        return arguments;
    }

    /**
     * 从文本中找出第一个完整的 JSON 对象并解析。
     * <p>
     * 之所以不直接 {@code readValue(整段)}，是因为模型经常写成：
     * {@code Action Input: 好的，参数是 {"a": 6}}，前后会带说明文字。
     *
     * @return 解析成功的参数 Map；找不到或解析失败返回 {@code null}
     */
    private static Map<String, Object> tryJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        // 按括号配对找结尾，而不是找最后一个 }，避免把后面的无关文本带进来。
        int end = findBalancedEnd(text, start);
        if (end < 0) {
            // 括号不配对，说明 JSON 被截断了，放弃这种尝试。
            return null;
        }
        try {
            Map<String, Object> parsed = Json.mapper().readValue(
                    text.substring(start, end + 1), new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            // 复制一份，避免返回 Jackson 内部持有的 Map 实现，保证调用方拿到的是可变普通 Map。
            return new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 找到与起始花括号配对的结束位置，忽略字符串内部的花括号。
     * <p>
     * 例如 {@code {"msg": "a}b{c"}} 中的花括号出现在引号里，不应参与配对，
     * 因此需要一个极简的「字符串内 / 字符串外」状态机。
     *
     * @param text  待扫描文本
     * @param start 起始花括号的下标
     * @return 配对结束的 {@code }} 下标；括号不配对时返回 {@code -1}
     */
    private static int findBalancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            // 状态一：在字符串内部，只有未转义的 " 才能结束字符串，其它字符一律跳过。
            if (inString) {
                if (escaped) {
                    // 上一个字符是反斜杠，当前字符被转义，如 \" 不结束字符串。
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            // 状态二：在字符串外部，只统计括号。
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

    /**
     * 尝试把文本按「每行一个键值对」解析。
     * <p>
     * 支持三种分隔符：{@code =}、全角冒号 {@code ：}、半角冒号 {@code :}。
     * 冒号放在最后尝试，因为工具名本身可能带冒号，优先用更明确的分隔符切分。
     *
     * @return 解析出的键值对；一条都没有时返回空 Map（由调用方据此判断是否命中）
     */
    private static Map<String, Object> tryKeyValuePairs(String text) {
        Map<String, Object> pairs = new LinkedHashMap<>();
        // \\R 匹配任意换行符（\n、\r\n、\r），不依赖具体平台。
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            // separator <= 0 同时排除了「找不到」(-1) 和「冒号在行首」(0，说明没有键名) 两种情况。
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
            // 去掉模型习惯加的双引号，让 a="6" 与 a=6 得到同样的键值。
            String key = trimmed.substring(0, separator).trim().replace("\"", "");
            String value = trimmed.substring(separator + 1).trim().replace("\"", "");
            if (!key.isBlank()) {
                pairs.put(key, value);
            }
        }
        return pairs;
    }

    /**
     * 剥掉 Markdown 代码围栏：把 ```json ... ``` 还原成其中的正文。
     * <p>
     * 只处理「整段被围栏包裹」的情况：首行是围栏（可能带语言标识），末尾是收尾围栏。
     * 不是这种形态时原样返回。
     */
    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        // 跳过开围栏那一行（```json 之类），从下一行开始才是正文。
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String body = text.substring(firstLineEnd + 1);
        // 用 lastIndexOf 而非 indexOf：正文里可能还有别的围栏，收尾的一定是最后一个。
        int fenceEnd = body.lastIndexOf("```");
        return fenceEnd < 0 ? body.trim() : body.substring(0, fenceEnd).trim();
    }

    /**
     * 清理最终答案的装饰：剥掉代码围栏与首尾空白。
     */
    private static String stripDecorations(String text) {
        return stripCodeFence(text).trim();
    }
}
