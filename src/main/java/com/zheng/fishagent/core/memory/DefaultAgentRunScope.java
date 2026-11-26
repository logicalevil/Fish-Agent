package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.constant.Role;

/**
 * 默认作用域。
 * <p>
 * {@link #toKey()} <b>只使用稳定字段</b>（用户 + 会话）：它是记忆存储的分区键，
 * 若把 {@code runId} 也算进去，每次 run 都会落在一个新的分区里，
 * 上一轮写入的消息就读不到了，{@link ChatMemoryView#clear()} 也只能清掉当前 run。
 * <p>
 * {@code role} 若为空则按 {@link Role#USER} 处理，避免 {@code null} 直接拼进键里。
 */
public record DefaultAgentRunScope(String sessionId, Role role, String runId, String userId)
        implements AgentRunScope {

    /** 会话标识缺失时使用的占位，保证不同「无会话」调用共享同一分区。 */
    private static final String DEFAULT_SESSION = "default";

    public DefaultAgentRunScope {
        sessionId = (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;
        role = role == null ? Role.USER : role;
        runId = runId == null ? "" : runId;
        userId = userId == null ? "" : userId;
    }

    @Override
    public String toKey() {
        return userId + "_" + sessionId;
    }
}
