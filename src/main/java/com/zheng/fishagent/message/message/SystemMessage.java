package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.content.Content;

import java.util.List;
import java.util.Map;

public final class SystemMessage extends BaseMessage {

    public SystemMessage(List<Content> contents) {
        super(Role.SYSTEM, contents);
    }

    /**
     * 无额外字段，equals/hashCode 全部由 {@link BaseMessage} 提供。
     */
    @Override
    public String toString() {
        return "";
    }

    @Override
    public String allMessage() {
        return "";
    }

    /**
     * 无子类特有字段，映射就是公共的 {@code role} 与 {@code content}。
     */
    @Override
    public Map<String, Object> toMap() {
        return super.toMap();
    }

    /**
     * 从映射还原系统消息；无特有字段需要读取。
     */
    public static SystemMessage fromMap(Map<String, Object> map) {
        return new SystemMessage(contentsOf(map));
    }
}
