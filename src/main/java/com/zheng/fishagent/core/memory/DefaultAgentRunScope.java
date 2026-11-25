package com.zheng.fishagent.core.memory;

import com.zheng.fishagent.core.constant.Role;

public record DefaultAgentRunScope(String sessionId, Role role, String runId, String userId) implements AgentRunScope{

    @Override
    public String toKey() {
        return String.format("%s_%s_%s_%s", userId,sessionId, runId,role);
    }
}
