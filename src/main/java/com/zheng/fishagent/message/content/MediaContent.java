package com.zheng.fishagent.message.content;

public abstract sealed  class MediaContent implements Content permits ImageContent, VideoContent, AudioContent {
}
