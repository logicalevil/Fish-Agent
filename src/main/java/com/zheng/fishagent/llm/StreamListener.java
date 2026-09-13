package com.zheng.fishagent.llm;

/**
 * 流式输出回调。函数式接口，可直接写成 {@code model.stream(request, token -> System.out.print(token))}。
 */
@FunctionalInterface
public interface StreamListener {

    StreamListener NOOP = token -> {
    };

    /**
     * 每收到一段增量文本时回调。
     */
    void onToken(String token);

    /**
     * 流结束时回调，携带聚合后的完整响应。
     */
    default void onComplete(ChatResponse response) {
    }

    /**
     * 流式过程中出错时回调。
     */
    default void onError(Throwable error) {
    }
}
