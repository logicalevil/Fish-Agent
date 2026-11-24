package com.zheng.fishagent.core.constant;

import com.zheng.fishagent.core.message.content.Content;

/**
 * {@link Content#toMap()} 映射表示中使用的键名与类型标识。
 * <p>
 * 集中在一处，序列化与反序列化共用同一份定义，避免手写字面量拼错。
 */
public final class ContentKeys {

    /** 内容块类型。 */
    public static final String TYPE = "type";
    /** 纯文本块的正文。 */
    public static final String TEXT = "text";
    /** 媒体块的来源。 */
    public static final String SOURCE = "source";
    /** Base64 来源的媒体类型。 */
    public static final String MIME_TYPE = "mimeType";
    /** Base64 来源的数据。 */
    public static final String DATA = "data";
    /** URL 来源的地址。 */
    public static final String URL = "url";

    /** 纯文本块。 */
    public static final String TYPE_TEXT = "text";
    /** 图片块。 */
    public static final String TYPE_IMAGE = "image";
    /** 音频块。 */
    public static final String TYPE_AUDIO = "audio";
    /** 视频块。 */
    public static final String TYPE_VIDEO = "video";

    private ContentKeys() {
    }
}
