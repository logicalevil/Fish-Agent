package com.zheng.fishagent.prompt;

import com.zheng.fishagent.tool.ToolSpec;
import com.zheng.fishagent.util.Json;

import java.util.List;
import java.util.StringJoiner;

/**
 * ReAct 提示词模板。
 */
public final class ReActPrompt {

    public static final String DEFAULT_INSTRUCTIONS = """
            你是一个严谨的智能助手。
            你可以使用工具来获取你无法直接知道的事实，不要凭记忆编造工具才能提供的信息。
            当信息足够时，直接给出简洁、明确的最终回答，不要暴露内部推理细节之外的内容。""";

    private ReActPrompt() {
    }

    /**
     * 原生 Tool Calling 模式：模型通过结构化字段发起调用，提示词只需说明行为准则。
     */
    public static String nativeTools(String instructions, List<ToolSpec> tools) {
        String base = instructions == null || instructions.isBlank() ? DEFAULT_INSTRUCTIONS : instructions.trim();
        StringBuilder prompt = new StringBuilder(base);
        prompt.append("\n\n");
        if (tools != null && !tools.isEmpty()) {
            prompt.append("如需调用工具，请直接使用工具调用能力，一次可以调用一个或多个；")
                    .append("拿到工具结果后再判断是否需要继续调用。");
        } else {
            prompt.append("当前没有任何可用工具，请直接根据已有知识回答；")
                    .append("如果不确定，请明确说明不确定的部分。");
        }
        return prompt.toString();
    }

    /**
     * 文本 ReAct 模式：用于不支持原生工具调用的模型，通过提示词约定 Thought/Action 格式。
     */
    public static String textMode(String instructions, List<ToolSpec> tools) {
        String base = instructions == null || instructions.isBlank() ? DEFAULT_INSTRUCTIONS : instructions.trim();
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(base);
        joiner.add("");
        joiner.add("你可以使用以下工具：");
        joiner.add(describeTools(tools));
        joiner.add("");
        joiner.add("请严格按照下面的格式回答，每次只输出一个步骤，不要编造 Observation：");
        joiner.add("");
        joiner.add("Thought: 你的思考过程");
        joiner.add("Action: 要使用的工具名，必须是上面列表中的一个");
        joiner.add("Action Input: 调用该工具的参数，必须是合法的 JSON 对象");
        joiner.add("");
        joiner.add("（系统会返回 Observation: 工具执行结果，你可以继续下一轮 Thought/Action）");
        joiner.add("");
        joiner.add("当你已经可以回答用户问题时，使用以下格式结束：");
        joiner.add("");
        joiner.add("Thought: 我已经知道最终答案");
        joiner.add("Final Answer: 对用户问题的最终回答");
        joiner.add("");
        joiner.add("如果你不需要任何工具，直接输出 Final Answer 即可。");
        return joiner.toString();
    }

    /**
     * 把工具列表渲染成“名称 + 描述 + 参数”的文本块。
     */
    public static String describeTools(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) {
            return "（无）";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolSpec spec : tools) {
            builder.append("- ").append(spec.name());
            if (!spec.description().isBlank()) {
                builder.append("：").append(spec.description().replaceAll("\\s+", " ").trim());
            }
            builder.append("\n");
            builder.append("  参数：").append(Json.write(spec.parameters())).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    /**
     * ReAct 文本模式下把用户问题包装成可续写的前缀。
     */
    public static String questionBlock(String userInput) {
        return "Question: " + userInput + "\n";
    }

    /**
     * ReAct 文本模式下把工具结果回填给模型。
     */
    public static String observationBlock(String toolName, String result) {
        return "Observation: " + result + "\n";
    }
}
