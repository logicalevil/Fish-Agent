package com.zheng.fishagent.core;

/**
 * Fish-Agent 统一异常类型。
 */
public class FishAgentException extends RuntimeException {

    public FishAgentException(String message) {
        super(message);
    }

    public FishAgentException(String message, Throwable cause) {
        super(message, cause);
    }

    public static FishAgentException of(String format, Object... args) {
        return new FishAgentException(String.format(format, args));
    }

    public static FishAgentException wrap(String message, Throwable cause) {
        return new FishAgentException(message + ": " + cause.getMessage(), cause);
    }
}
