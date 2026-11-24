package com.zheng.fishagent.prompt;

/**
 * 输出不符合 ReAct 格式。框架按「没有工具调用」处理，即把原文当作最终答案兜底返回，
 * 以免丢失模型已经产出的内容。
 *
 * @param raw 未加工的原始输出
 */
public record Unparsed(String raw) implements ReActStep {
}
