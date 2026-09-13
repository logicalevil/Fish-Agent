package com.zheng.fishagent.agent;

import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.tool.Tool;

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
     * 会话记忆。
     */
    ChatMemory memory();

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
        memory().clear();
    }
}
