package com.zheng.fishagent.message.content;

import com.zheng.fishagent.message.source.MediaSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 媒体内容块的公共部分：持有一个 {@link MediaSource} 并负责其序列化。
 */
public abstract sealed class MediaContent implements Content
        permits ImageContent, VideoContent, AudioContent {

    private final MediaSource mediaSource;

    protected MediaContent(MediaSource mediaSource) {
        this.mediaSource = Objects.requireNonNull(mediaSource, "mediaSource");
    }

    public MediaSource getMediaSource() {
        return this.mediaSource;
    }

    @Override
    public String text() {
        return "";
    }

    /*
    * 将媒体内容块序列化为 Map
    * 由其子类实现toMap方法
    * */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(ContentKeys.TYPE, type());
        map.put(ContentKeys.SOURCE, mediaSource.toMap());
        return map;
    }
}
