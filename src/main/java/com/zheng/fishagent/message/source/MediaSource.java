package com.zheng.fishagent.message.source;

public sealed interface MediaSource permits  Base64DataSource, UrlSource {
}
