package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.content.Content;

import java.util.List;

public  interface Message {
    Role role();

    List<Content> contentList();

    String content();
}
