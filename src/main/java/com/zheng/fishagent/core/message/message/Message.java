package com.zheng.fishagent.core.message.message;

import com.zheng.fishagent.core.message.Role;
import com.zheng.fishagent.core.message.content.Content;

import java.util.List;
import java.util.Map;

public interface Message {

    /*返回消息的类型*/
    Role role();

    /*返回消息所对应的content块*/
    List<Content> contentList();

    /*返回消息的所有内容，包含额外内容*/
    String allMessage();

    /*序列化为可移植的映射表示，由子类实现*/
    Map<String, Object> toMap();
}
