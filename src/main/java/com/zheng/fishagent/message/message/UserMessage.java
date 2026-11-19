package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.content.Content;

import java.util.List;

public final class UserMessage extends BaseMessage {

    UserMessage(List<Content> contents) {
        super(Role.USER, contents);
    }

    /**
     * 无额外字段，equals/hashCode 全部由 {@link BaseMessage} 提供；
     * 这里不重写，避免与父类的 {@code getClass()} 校验重复。
     */
    @Override
    public String toString() {
        return "UserMessage[contents=" + content() + "]";
    }

    @Override
    public String content() {
        return "";
    }
}
