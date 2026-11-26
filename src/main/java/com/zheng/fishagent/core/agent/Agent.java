package com.zheng.fishagent.core.agent;

import com.zheng.fishagent.core.listener.AgentListener;
import com.zheng.fishagent.core.memory.ChatMemoryView;
import com.zheng.fishagent.core.tool.Tool;

import java.util.List;

/**
 * Agent 抽象：输入一句话，内部完成推理、工具调用与回答生成。
 */
public interface Agent {

    /**
     * 执行一轮对话（自动携带历史记忆）。
     */
    AgentResult run(String userInput);

    /**
     * 执行一轮对话并接收过程事件。
     */
    AgentResult run(String userInput, AgentListener listener);

    /**
     * 会话记忆视图，是读取与清空会话状态的唯一入口。
     * <p>
     * {@link #reset()} 走 {@link ChatMemoryView#clear()}，因此本方法是接口的必要部分：
     * 调用方据此可以查看完整历史（{@code history()}）或当前上下文（{@code context()}）。
     */
    ChatMemoryView memoryView();

    /**
     * 当前使用的系统提示词。
     */
    String systemPrompt();

    /**
     * 当前注册的工具。
     */
    List<Tool> tools();

    /**
     * 清空会话记忆。
     */
    default void reset() {
        memoryView().clear();
    }
}
