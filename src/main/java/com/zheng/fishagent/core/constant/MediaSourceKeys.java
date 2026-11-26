package com.zheng.fishagent.core.constant;

import com.zheng.fishagent.core.message.source.MediaSource;

/**
 * {@link MediaSource#toMap()} 映射表示中使用的键名与来源类型标识。
 */
public final class MediaSourceKeys {

    /** 来源类型。 */
    public static final String TYPE = "type";
    /** URL 来源的地址。 */
    public static final String URL = "url";
    /** Base64 来源的媒体类型。 */
    public static final String MIME_TYPE = "mimeType";
    /** Base64 来源的数据。 */
    public static final String DATA = "data";

    /** 外部可访问的地址来源。 */
    public static final String TYPE_URL = "url";
    /** 以内联 Base64 编码承载的来源。 */
    public static final String TYPE_BASE64 = "base64";

    private MediaSourceKeys() {
    }
}
