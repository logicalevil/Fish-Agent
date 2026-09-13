package com.zheng.fishagent.agent;

import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.ToolCall;

/**
 * Agent 执行过程的事件回调。所有方法均为默认空实现，按需覆写。
 * <p>
 * 典型用途：命令行实时打印、SSE 推送、埋点统计。
 */
public interface AgentListener {

    AgentListener NOOP = new AgentListener() {
    };

    /**
     * 一次 run 开始时触发。
     */
    default void onStart(String userInput) {
    }

    /**
     * 模型（或框架）产生的思考文本。
     */
    default void onThought(String thought) {
    }

    /**
     * 流式增量文本。仅在开启流式且模型支持时触发。
     */
    default void onToken(String token) {
    }

    /**
     * 一条完整的 assistant 消息生成完毕。
     */
    default void onMessage(Message message) {
    }

    /**
     * 即将执行一次工具调用。
     */
    default void onToolCall(ToolCall call) {
    }

    /**
     * 工具执行完成。
     */
    default void onToolResult(ToolCall call, String result) {
    }

    /**
     * 一次 run 正常结束。
     */
    default void onFinish(AgentResult result) {
    }

    /**
     * 执行过程中出现异常。
     */
    default void onError(Throwable error) {
    }
}
