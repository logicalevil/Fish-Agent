package com.zheng.fishagent.core.message.content;

import com.zheng.fishagent.core.constant.ContentKeys;

import java.util.Map;

/**
 * 消息正文中的一个内容块。
 * <p>
 * 每个块自行负责序列化为映射（{@code {type, ...}}），消息层因此不需要用
 * {@code instanceof} 判断具体块类型。
 */
public sealed interface Content permits TextContent, MediaContent {

    /** 块的纯文本表示；媒体块返回空串。 */
    String text();

    /** 块的类型标识，取值见 {@link ContentKeys} 中的 {@code TYPE_*} 常量。 */
    String type();

    /** 序列化为映射表示，由各块自行实现。 */
    Map<String, Object> toMap();
}
