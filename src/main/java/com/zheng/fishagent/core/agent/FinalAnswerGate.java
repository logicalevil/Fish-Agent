package com.zheng.fishagent.core.agent;

import com.zheng.fishagent.core.llm.StreamListener;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本 ReAct 模式下的流式门控：模型会先输出 Thought/Action 才能得到答案，
 * 这些中间步骤不应该直接呈现给用户。这里只在检测到 "Final Answer:" 后开始向下游推送。
 * <p>
 * 实现要点：由于 token 是任意切分的，标记可能跨越多个 token，
 * 因此必须<b>先缓冲、再扫描</b>。用 {@code emittedUpTo} 游标记录已下发的边界，
 * 保证每段文本只推送一次。
 * <p>
 * 一个实例对应一次模型调用，不可跨轮复用。
 */
final class FinalAnswerGate implements StreamListener {

    /** 匹配 "Final Answer:" 标签（容忍前导空格/引用符/列表符，以及加粗与中英文冒号）。 */
    private static final Pattern FINAL_LABEL = Pattern.compile(
            "(?im)^[\\s>*-]*Final\\s*Answer\\s*[*]*\\s*[:：]\\s*");

    /** 匹配 "Action:" 标签，用于判断本轮是工具调用而非最终答案。 */
    private static final Pattern ACTION_LABEL = Pattern.compile(
            "(?im)^[\\s>*-]*Action\\s*[*]*\\s*[:：]");

    private final AgentListener callback;
    /** 累积的所有 token，扫描与切片都基于它。 */
    private final StringBuilder buffer = new StringBuilder();
    /** 已推送到下游的位置；-1 表示尚未发现 Final Answer 标记。 */
    private int emittedUpTo = -1;

    FinalAnswerGate(AgentListener callback) {
        this.callback = callback;
    }

    @Override
    public void onToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        buffer.append(token);
        if (emittedUpTo < 0) {
            // 门尚未打开：在缓冲区中寻找 Final Answer 标记
            Matcher matcher = FINAL_LABEL.matcher(buffer);
            if (matcher.find()) {
                // 标记之后的正文立即下发，并打开门（此后不再走扫描逻辑）
                emittedUpTo = matcher.end();
                emitTail();
                return;
            }
            if (ACTION_LABEL.matcher(buffer).find()) {
                // 本步是工具调用，丢包并重置缓冲，继续等待真正的 Final Answer。
                // 重置是必要的：否则下一轮的 Thought 会和上一轮的残留拼在一起，
                // 可能误判出根本不存在的 Final Answer 标记。
                buffer.setLength(0);
            }
            return;
        }
        // 门已打开：缓冲区新增部分直接下发
        emitTail();
    }

    /** 推送 {@code [emittedUpTo, buffer.length())} 区间，并推进游标。 */
    private void emitTail() {
        if (emittedUpTo < 0 || emittedUpTo > buffer.length()) {
            return;
        }
        String tail = buffer.substring(emittedUpTo);
        if (!tail.isEmpty()) {
            emittedUpTo = buffer.length();
            callback.onToken(tail);
        }
    }
}
