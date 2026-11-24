package com.zheng.fishagent.prompt;

import java.util.Map;

/**
 * 模型希望调用某个工具。
 *
 * @param tool           工具名
 * @param arguments      已解析为 Map 的入参；解析失败时为空 Map
 * @param thought        该步的思考内容
 * @param rawActionInput 原始的 Action Input 文本，便于排查解析问题
 */
public record ToolRequest(String tool, Map<String, Object> arguments, String thought, String rawActionInput)
        implements ReActStep {
}
