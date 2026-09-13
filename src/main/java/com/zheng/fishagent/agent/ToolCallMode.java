package com.zheng.fishagent.agent;

/**
 * Agent 的工具调用策略。
 */
public enum ToolCallMode {

    /**
     * 原生 Tool Calling：依赖模型/推理框架提供的结构化工具调用能力（推荐）。
     * 提示词只描述行为准则，工具 Schema 通过请求的 tools 字段下发。
     */
    NATIVE_TOOLS,

    /**
     * 文本 ReAct：通过提示词约束模型输出 Thought / Action / Action Input / Final Answer，
     * 由框架解析文本得到工具调用。适用于不支持原生工具调用的模型。
     */
    REACT_TEXT
}
