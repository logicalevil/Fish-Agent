package com.zheng.fishagent.message.content;

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
}
