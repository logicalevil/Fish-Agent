package com.zheng.fishagent.core.util;

public class StringUtil {
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
