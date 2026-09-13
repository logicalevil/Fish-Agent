package com.zheng.fishagent.llm;

/**
 * Token 用量统计。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 */
public record TokenUsage(long promptTokens, long completionTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0);

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(promptTokens + other.promptTokens(), completionTokens + other.completionTokens());
    }
}
