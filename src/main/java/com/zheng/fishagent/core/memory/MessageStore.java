package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.message.message.Message;

import java.util.List;

public interface MessageStore {
    /** 追加一条消息，返回存储侧序号（单调递增，用于增量加载与排序）。 */
    Long append(AgentRunScope agentRunScope, Message message);

    /** 按序号升序读取，afterSeq 为开区间（不含）。limit <= 0 表示不限。 */
    List<StoredMessage> load(AgentRunScope agentRunScope, long afterSeq, int limit);

    /** 删除该会话全部消息。 */
    void clear(AgentRunScope agentRunScope);
}
