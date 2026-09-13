package com.zheng.fishagent.prompt;

import java.util.Map;

/**
 * ReAct 文本模式下，对模型单步输出的解析结果。
 */
public sealed interface ReActStep {

    /**
     * 模型给出了最终答案。
     */
    record FinalAnswer(String text, String thought) implements ReActStep {
    }

    /**
     * 模型希望调用某个工具。
     */
    record ToolRequest(String tool, Map<String, Object> arguments, String thought, String rawActionInput)
            implements ReActStep {
    }

    /**
     * 输出不符合 ReAct 格式，按最终答案兜底处理。
     */
    record Unparsed(String raw) implements ReActStep {
    }
}
