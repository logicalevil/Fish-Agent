package com.zheng.fishagent.message.content;

import com.zheng.fishagent.message.source.MediaSource;

/**
 * 图片内容块。
 */
public final class ImageContent extends MediaContent {

    public ImageContent(MediaSource mediaSource) {
        super(mediaSource);
    }

    @Override
    public String type() {
        return ContentKeys.TYPE_IMAGE;
    }
}
