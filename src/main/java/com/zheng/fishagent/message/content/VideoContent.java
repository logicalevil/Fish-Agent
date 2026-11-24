package com.zheng.fishagent.message.content;

import com.zheng.fishagent.message.source.MediaSource;

/**
 * 视频内容块。
 */
public final class VideoContent extends MediaContent {

    public VideoContent(MediaSource mediaSource) {
        super(mediaSource);
    }

    @Override
    public String type() {
        return ContentKeys.TYPE_VIDEO;
    }
}
