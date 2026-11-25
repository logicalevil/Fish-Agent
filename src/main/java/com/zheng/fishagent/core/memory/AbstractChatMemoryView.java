package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

/**
 * 视图模板基类：把「裁剪」这一步收敛在 {@link #context()} 里。
 * <p>
 * 子类只需提供未经裁剪的原始上下文（{@link #rawContext()}）与完整历史（{@link #history()}），
 * 裁剪策略通过构造器注入的 {@link ContextTrimmer} 决定。
 * <p>
 * {@link #clear()} 不在此实现：清空需要触及子类的存储（消息列表、MessageStore 等），
 * 由具体子类负责。
 */
public abstract class AbstractChatMemoryView implements ChatMemoryView {

    /** 当前裁剪器；构造时保证非 null */
    private final ContextTrimmer trimmer;

    protected AbstractChatMemoryView() {
        this(null);
    }

    protected AbstractChatMemoryView(ContextTrimmer trimmer) {
        this.trimmer = trimmer != null ? trimmer : defaultTrimmer();
    }

    /**
     * 子类可覆盖此方法，提供自己的默认裁剪器。
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
     * 子类一般无需覆盖；若确需完全自定义（例如摘要 + 尾部），可 override。
     */
    @Override
    public List<? extends Message> context() {
        return trimmer.trim(rawContext());
    }

    /** 子类实现：返回「未经裁剪的上下文」，通常是内存中的消息列表 */
    protected abstract List<Message> rawContext();
}
