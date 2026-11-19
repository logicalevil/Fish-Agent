package com.zheng.fishagent.message.content;

public sealed interface Content permits TextContent, MediaContent {
    String text();
}
