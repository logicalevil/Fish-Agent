package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.content.Content;

import java.util.List;
import java.util.Objects;

public sealed abstract class BaseMessage implements Message
        permits UserMessage, ToolMessage, SystemMessage, AssistantMessage {

    private final Role role;
    private final List<Content> contents;

    protected BaseMessage(Role role, List<Content> contents) {
        this.role = Objects.requireNonNull(role, "role 不能为空");
        this.contents = contents == null ? List.of() : List.copyOf(contents);
    }

    @Override
    public Role role() {
        return role;
    }

    @Override
    public List<Content> contentList() {
        return contents;
    }

    /**
     * 值与类型都相同才算相等。
     * <p>
     * 用 {@code getClass()} 而非 {@code instanceof}：{@code USER} 与 {@code SYSTEM}
     * 的 role 不同尚可区分，但同 role 的两个子类若互相 {@code instanceof} 成立，
     * 会出现 {@code a.equals(b)} 为真而 {@code b.equals(a)} 为假。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BaseMessage that = (BaseMessage) o;
        return role == that.role && contents.equals(that.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, contents);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[role=" + role.value() + ", contents=" + contents + "]";
    }
}
