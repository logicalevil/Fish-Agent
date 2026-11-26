package com.zheng.fishagent.core.message.content;

import com.zheng.fishagent.core.constant.ContentKeys;
import com.zheng.fishagent.core.message.source.MediaSource;

/**
 * 音频内容块。
 */
public final class AudioContent extends MediaContent {

    public AudioContent(MediaSource mediaSource) {
        super(mediaSource);
    }

    @Override
    public String type() {
        return ContentKeys.TYPE_AUDIO;
    }
}
