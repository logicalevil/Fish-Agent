package com.zheng.fishagent.llm;

/**
 * Token 用量统计。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 */
public record TokenUsage(long promptTokens, long completionTokens) {

    /** 零用量，用于缺失时的兜底。 */
    public static final TokenUsage EMPTY = new TokenUsage(0, 0);

    /**
     * 总 token 数（输入 + 输出）。
     */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * 与另一次用量的累加。{@code other} 为 {@code null} 时返回自身。
     * <p>
     * 本记录不可变，每次累加返回新实例，因此可安全跨轮次累加。
     */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(promptTokens + other.promptTokens(), completionTokens + other.completionTokens());
    }
}
