package com.zheng.fishagent.message.content;

import com.zheng.fishagent.message.source.MediaSource;

public final class ImageContent extends MediaContent {
    private final MediaSource mediaSource;

    public ImageContent(MediaSource mediaSource) {
        this.mediaSource = mediaSource;
    }
    public MediaSource getMediaSource() {
        return mediaSource;
    }

    @Override
    public String text() {
        return "";
    }
}