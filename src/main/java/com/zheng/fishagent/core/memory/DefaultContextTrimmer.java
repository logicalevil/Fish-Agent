package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.constant.Role;
import com.zheng.fishagent.core.message.message.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认裁剪器：滑动窗口。
 * <p>
 * 规则：
 * <ol>
 *   <li>{@link Role#SYSTEM} 消息不参与计数，始终保留在最前面；</li>
 *   <li>其余消息只取最近 {@code maxMessages} 条；</li>
 *   <li>若窗口起点落在 {@link Role#TOOL} 消息上，向前扩展到配对的助手消息
 *       （工具结果不能先于发起它的助手消息出现），因此实际条数可能略微超出窗口。</li>
 * </ol>
 * 纯函数：不修改入参，返回不可变列表。
 */
public final class DefaultContextTrimmer implements ContextTrimmer {

    /* 默认最大消息数*/
    public static final int DEFAULT_MAX_MESSAGES = 30;

    private final int maxMessages;

    public DefaultContextTrimmer() {
        this(DEFAULT_MAX_MESSAGES);
    }

    public DefaultContextTrimmer(int maxMessages) {
        // 窗口下限为 1：0 或负数没有意义
        this.maxMessages = Math.max(1, maxMessages);
    }

    /**
     * 窗口大小，即「除 SYSTEM 外」参与上下文的最近消息条数上限。
     */
    public int maxMessages() {
        return maxMessages;
    }

    @Override
    public List<Message> trim(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Message> systems = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            if (message.role() == Role.SYSTEM) {
                systems.add(message);
            } else {
                others.add(message);
            }
        }

        int start = Math.max(0, others.size() - maxMessages);
        // 起点是工具结果时，向前找发起它的助手消息，保证「助手 → 工具结果」配对完整
        while (start > 0 && others.get(start).role() == Role.TOOL) {
            start--;
        }

        List<Message> result = new ArrayList<>(systems.size() + others.size() - start);
        result.addAll(systems);
        result.addAll(others.subList(start, others.size()));
        return List.copyOf(result);
    }
}
