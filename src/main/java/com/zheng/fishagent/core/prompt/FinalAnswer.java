package com.zheng.fishagent.core.prompt;

/**
 * 模型给出了最终答案。
 *
 * @param text    剥掉 {@code Final Answer:} 标签后的回答正文
 * @param thought 该步的思考内容；模型未输出时为空白串
 */
public record FinalAnswer(String text, String thought) implements ReActStep {
}
