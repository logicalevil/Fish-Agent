package com.zheng.fishagent.core.message.content;

import com.zheng.fishagent.core.constant.ContentKeys;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纯文本内容块。
 */
public final class TextContent implements Content {

    private String text;

    public TextContent(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    @Override
    public String text() {
        return getText();
    }

    @Override
    public String type() {
        return ContentKeys.TYPE_TEXT;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(ContentKeys.TYPE, type());
        map.put(ContentKeys.TEXT, text);
        return map;
    }
}
