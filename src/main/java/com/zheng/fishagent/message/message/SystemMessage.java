package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.content.Content;

import java.util.List;

public final class SystemMessage extends BaseMessage {

    public SystemMessage(List<Content> contents) {
        super(Role.SYSTEM, contents);
    }

    /**
     * 无额外字段，equals/hashCode 全部由 {@link BaseMessage} 提供。
     */
    @Override
    public String toString() {
        return "SystemMessage[contents=" + content() + "]";
    }

    @Override
    public String content() {
        return "";
    }
}
