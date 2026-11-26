package com.zheng.fishagent.core.message.message;

import com.zheng.fishagent.core.constant.Role;
import com.zheng.fishagent.core.constant.MessageKeys;
import com.zheng.fishagent.core.message.content.AudioContent;
import com.zheng.fishagent.core.message.content.Content;
import com.zheng.fishagent.core.constant.ContentKeys;
import com.zheng.fishagent.core.message.content.ImageContent;
import com.zheng.fishagent.core.message.content.TextContent;
import com.zheng.fishagent.core.message.content.VideoContent;
import com.zheng.fishagent.core.message.source.Base64DataSource;
import com.zheng.fishagent.core.message.source.MediaSource;
import com.zheng.fishagent.core.constant.MediaSourceKeys;
import com.zheng.fishagent.core.message.source.UrlSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed abstract class BaseMessage implements Message
        permits UserMessage, ToolMessage, SystemMessage, AssistantMessage {

    /*消息类型*/
    private final Role role;
    /*消息元数据*/
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
        return new ArrayList<>(contents);
    }

    /**
     * 公共字段的序列化骨架：写入 {@code role} 与 {@code content}，
     * 再把自己特有的字段交给子类追加。
     * <p>
     * 子类通常这样实现：
     * <pre>{@code
     * @Override
     * public Map<String, Object> toMap() {
     *     Map<String, Object> map = super.toMap();
     *     map.put(MessageKeys.TOOL_NAME, toolName);
     *     return map;
     * }
     * }</pre>
     */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(MessageKeys.ROLE, role.value());
        map.put(MessageKeys.CONTENT, contentValue());
        return map;
    }


    /**
     * 从可移植映射还原消息，是 {@link #toMap()} 的反向操作。
     * <p>
     * 这里只负责按 {@code role} 分派到对应子类，各子类自己还原特有字段；
     * 角色缺失或无法识别时退化为 {@link Role#USER}（与 {@link Role#fromValue} 的宽容策略一致）。
     */
    public static Message fromMap(Map<String, Object> map) {
        Map<String, Object> source = map == null ? Map.of() : map;
        Role role = Role.fromValue(stringValue(source.get(MessageKeys.ROLE)));
        return switch (role) {
            case SYSTEM -> SystemMessage.fromMap(source);
            case ASSISTANT -> AssistantMessage.fromMap(source);
            case TOOL -> ToolMessage.fromMap(source);
            case USER -> UserMessage.fromMap(source);
        };
    }

    /**
     * 读取映射中的 {@code content} 并还原内容块。
     * <p>
     * 兼容两种形态：字符串（单块文本，历史数据的写法）与数组（逐个按 {@code type} 分派）。
     */
    protected static List<Content> contentsOf(Map<String, Object> map) {
        if (!(map.get(MessageKeys.CONTENT) instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Content> contents = new ArrayList<>(list.size());
        for (Object item : list) {
            Content content = contentOf(mapValue(item));
            if (content != null) {
                contents.add(content);
            }
        }
        return contents;
    }

    /**
     * 按 {@code type} 还原单个内容块；无法识别的类型返回 {@code null} 由调用方跳过。
     */
    private static Content contentOf(Map<String, Object> entry) {
        String type = stringValue(entry.get(ContentKeys.TYPE));
        if (type == null) {
            return null;
        }
        return switch (type) {
            case ContentKeys.TYPE_TEXT -> new TextContent(stringValue(entry.get(ContentKeys.TEXT)));
            case ContentKeys.TYPE_IMAGE -> new ImageContent(sourceOf(mapValue(entry.get(ContentKeys.SOURCE))));
            case ContentKeys.TYPE_AUDIO -> new AudioContent(sourceOf(mapValue(entry.get(ContentKeys.SOURCE))));
            case ContentKeys.TYPE_VIDEO -> new VideoContent(sourceOf(mapValue(entry.get(ContentKeys.SOURCE))));
            default -> null;
        };
    }

    /**
     * 按 {@code type} 还原媒体来源；无法识别或缺失时按 URL 来源处理。
     */
    private static MediaSource sourceOf(Map<String, Object> entry) {
        String type = stringValue(entry.get(MediaSourceKeys.TYPE));
        if (MediaSourceKeys.TYPE_BASE64.equals(type)) {
            return new Base64DataSource(
                    stringValue(entry.get(MediaSourceKeys.MIME_TYPE)),
                    stringValue(entry.get(MediaSourceKeys.DATA)));
        }
        return new UrlSource(stringValue(entry.get(MediaSourceKeys.URL)));
    }

    /**
     * 正文的序列化取值：统一写成内容块数组，
     * 以保留块的边界、先后顺序、类型与媒体本体。
     */
    private List<Map<String, Object>> contentValue() {
        List<Map<String, Object>> blocks = new ArrayList<>(contents.size());
        for (Content content : contents) {
            blocks.add(content.toMap());
        }
        return blocks;
    }

    /**
     * 把映射中的值转为字符串；{@code null} 原样返回。
     */
    protected static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把映射中的值转为字符串映射；非映射或 {@code null} 返回空映射。
     */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 正文的纯文本表示：各内容块的 {@link Content#text()} 顺序拼接。
     * <p>
     * 媒体块（图片 / 视频 / 音频）的 {@code text()} 为空串，相当于被忽略；
     * 需要媒体本体时请使用 {@link #contentList()}。
     */
    protected String text() {
        StringBuilder sb = new StringBuilder();
        for (Content content : contents) {
            String value = content == null ? null : content.text();
            if (value != null) {
                sb.append(value);
            }
        }
        return sb.toString();
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
        return "";
    }
}
