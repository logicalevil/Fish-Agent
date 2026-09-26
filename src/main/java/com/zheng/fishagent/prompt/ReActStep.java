package com.zheng.fishagent.prompt;

import java.util.Map;

/**
 * ReAct 文本模式下，对模型单步输出的解析结果。
 * <p>
 * 用 sealed 接口表达「三步必居其一」，供 {@code switch} 做穷尽匹配——
 * 新增分支时编译器会立刻指出所有需要同步修改的位置。
 *
 * @see ReActOutputParser#parse(String)
 */
public sealed interface ReActStep {

    /**
     * 模型给出了最终答案。
     *
     * @param text    剥掉 {@code Final Answer:} 标签后的回答正文
     * @param thought 该步的思考内容；模型未输出时为空白串
     */
    record FinalAnswer(String text, String thought) implements ReActStep {
    }

    /**
     * 模型希望调用某个工具。
     *
     * @param tool           工具名
     * @param arguments      已解析为 Map 的入参；解析失败时为空 Map
     * @param thought        该步的思考内容
     * @param rawActionInput 原始的 Action Input 文本，便于排查解析问题
     */
    record ToolRequest(String tool, Map<String, Object> arguments, String thought, String rawActionInput)
            implements ReActStep {
    }

    /**
     * 输出不符合 ReAct 格式。框架按「没有工具调用」处理，即把原文当作最终答案兜底返回，
     * 以免丢失模型已经产出的内容。
     *
     * @param raw 未加工的原始输出
     */
    record Unparsed(String raw) implements ReActStep {
    }
}
