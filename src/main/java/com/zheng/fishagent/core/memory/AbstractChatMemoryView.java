package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

public abstract class AbstractChatMemoryView implements ChatMemoryView {

    /** 当前裁剪器；null 时回退为默认实现 */
    private final ContextTrimmer trimmer;

    protected AbstractChatMemoryView() {
        this(null);
    }

    protected AbstractChatMemoryView(ContextTrimmer trimmer) {
        this.trimmer = trimmer != null ? trimmer : defaultTrimmer();
    }

    /**
     * 子类可覆盖此方法，提供自己的默认裁剪器。
     * 框架默认不裁剪，保持行为兼容。
     */
    protected ContextTrimmer defaultTrimmer() {
        return new DefaultContextTrimmer();
    }

    /** 读取当前裁剪器 */
    public ContextTrimmer trimmer() {
        return trimmer;
    }
    /**
     * 模板方法：对子类提供的原始上下文做裁剪。
     * 子类一般无需覆盖；若确需完全自定义（例如摘要），可 override。
     */
    @Override
    public List<? extends Message> context() {
        return trimmer.trim(rawContext());
    }

    /** 子类实现：返回「未经裁剪的上下文」，通常是内存中的消息列表 */
    protected abstract List<? extends Message> rawContext();
}