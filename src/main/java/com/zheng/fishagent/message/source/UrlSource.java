package com.zheng.fishagent.message.source;

import com.zheng.fishagent.message.constant.MediaSourceKeys;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 URL 引用的媒体来源。
 */
public final class UrlSource implements MediaSource {

    private final String url;

    public UrlSource(String url) {
        this.url = Objects.requireNonNull(url, "url");
    }

    /** 媒体地址。 */
    public String url() {
        return this.url;
    }

    @Override
    public String type() {
        return MediaSourceKeys.TYPE_URL;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(MediaSourceKeys.TYPE, type());
        map.put(MediaSourceKeys.URL, url);
        return map;
    }
}
