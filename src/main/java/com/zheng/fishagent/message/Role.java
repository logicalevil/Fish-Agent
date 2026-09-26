package com.zheng.fishagent.message;

/**
 * 对话消息角色。
 */
public enum Role {

    /** 系统提示词。框架按请求临时前置，不写入记忆。 */
    SYSTEM("system"),
    /** 用户输入。文本模式下工具结果（Observation）也以该角色回填。 */
    USER("user"),
    /** 模型输出，可能携带工具调用。 */
    ASSISTANT("assistant"),
    /** 工具执行结果，仅原生模式使用。 */
    TOOL("tool");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    /**
     * 线上协议中的角色字符串（小写）。
     */
    public String value() {
        return value;
    }

    /**
     * 按协议值或枚举名解析角色，大小写不敏感。
     * <p>
     * 无法识别或为 {@code null} 时回退到 {@link #USER}——这是刻意的宽容策略，
     * 避免个别模型返回非常规角色时直接中断整个 run。
     */
    public static Role fromValue(String value) {
        if (value == null) {
            return USER;
        }
        for (Role role : values()) {
            if (role.value.equalsIgnoreCase(value) || role.name().equalsIgnoreCase(value)) {
                return role;
            }
        }
        return USER;
    }
}
