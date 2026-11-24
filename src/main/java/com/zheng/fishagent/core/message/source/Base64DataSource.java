package com.zheng.fishagent.core.message.source;

import com.zheng.fishagent.core.constant.MediaSourceKeys;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 以内联 Base64 编码承载的媒体来源。
 */
public final class Base64DataSource implements MediaSource {

    /* 媒体类型，例如 image/png*/
    private final String mimeType;
    /* Base64 编码后的内容*/
    private final String data;

    public Base64DataSource(String mimeType, String data) {
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.data = Objects.requireNonNull(data, "data");
    }

    /** 媒体类型，例如 {@code image/png}。 */
    public String mimeType() {
        return this.mimeType;
    }

    /** Base64 编码后的内容。 */
    public String data() {
        return this.data;
    }

    @Override
    public String type() {
        return MediaSourceKeys.TYPE_BASE64;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(MediaSourceKeys.TYPE, type());
        map.put(MediaSourceKeys.MIME_TYPE, mimeType);
        map.put(MediaSourceKeys.DATA, data);
        return map;
    }
}
