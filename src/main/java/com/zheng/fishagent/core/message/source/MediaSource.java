package com.zheng.fishagent.core.message.source;

import com.zheng.fishagent.core.constant.MediaSourceKeys;

import java.util.Map;

/**
 * 媒体内容的来源描述。
 * <p>
 * 每个实现自行负责序列化为 {@code {type, ...}}，持有来源的对象因此不需要
 * 用 {@code instanceof} 判断具体来源类型。
 */
public sealed interface MediaSource permits Base64DataSource, UrlSource {

    /** 来源类型，取值见 {@link MediaSourceKeys} 中的 {@code TYPE_*} 常量。 */
    String type();

    /** 序列化为映射表示，由各来源自行实现。 */
    Map<String, Object> toMap();
}
