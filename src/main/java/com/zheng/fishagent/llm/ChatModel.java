package com.zheng.fishagent.llm;

/**
 * 大模型对话能力抽象。接入新的模型厂商时实现该接口即可。
 */
public interface ChatModel {

    /**
     * 同步（阻塞）调用模型。
     */
    ChatResponse call(ChatRequest request);

    /**
     * 流式调用模型。增量文本通过 {@code listener.onToken} 推送，
     * 方法返回时携带聚合完成的完整响应（含工具调用）。
     */
    ChatResponse stream(ChatRequest request, StreamListener listener);

    /**
     * 模型标识，便于日志与元信息展示。
     */
    default String modelName() {
        return getClass().getSimpleName();
    }

    /**
     * 连通性检查。
     */
    default boolean healthy() {
        try {
            call(ChatRequest.builder().message(com.zheng.fishagent.message.Message.user("ping")).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
