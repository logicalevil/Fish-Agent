package com.zheng.fishagent.core;

/**
 * Fish-Agent 统一异常类型。
 * <p>
 * 框架内所有「需要让调用方感知」的失败都用它表达，不抛裸 {@link RuntimeException}。
 * 未受检（继承 {@link RuntimeException}）是刻意选择：框架面向的调用场景多为
 * 交互式应用，强制捕获会污染调用链。
 * <p>
 * 注意区分三类失败（见 {@code openspec/specs/error-semantics}）：
 * <ul>
 *   <li><b>工具执行失败</b> → 不抛异常，转为以「错误：」开头的文本回填给模型；</li>
 *   <li><b>模型 / IO 失败</b> → 抛本异常，调用方可重试；</li>
 *   <li><b>预算耗尽 / 死循环</b> → 不抛异常，通过 {@code AgentResult.stopReason()} 标记。</li>
 * </ul>
 */
public class FishAgentException extends RuntimeException {

    public FishAgentException(String message) {
        super(message);
    }

    public FishAgentException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 以 {@link String#format} 风格构造异常。
     */
    public static FishAgentException of(String format, Object... args) {
        return new FishAgentException(String.format(format, args));
    }

    /**
     * 包装底层异常，并把 cause 的消息拼进描述，便于日志直接看出根因。
     */
    public static FishAgentException wrap(String message, Throwable cause) {
        return new FishAgentException(message + ": " + cause.getMessage(), cause);
    }
}
