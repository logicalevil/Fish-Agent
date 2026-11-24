package com.zheng.fishagent.prompt;

/**
 * ReAct 文本模式下，对模型单步输出的解析结果。
 * <p>
 * 用 sealed 接口表达「三步必居其一」，供 {@code switch} 做穷尽匹配——
 * 新增分支时编译器会立刻指出所有需要同步修改的位置。
 * <p>
 * 三个实现类型声明在各自的文件中，因此必须显式列出 {@code permits}。
 *
 * @see ReActOutputParser#parse(String)
 */
public sealed interface ReActStep permits FinalAnswer, ToolRequest, Unparsed {
}
