package com.zheng.fishagent.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zheng.fishagent.core.FishAgentException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 框架内部统一的 JSON 工具。
 * <p>
 * 共享同一个 {@link ObjectMapper} 实例：它线程安全，且已按框架需要调整过配置
 * （反序列化时忽略未知字段、序列化时忽略空 Bean，以容忍模型返回的多余字段）。
 * <p>
 * 所有方法在失败时统一抛 {@link FishAgentException}，不向外暴露 Jackson 的受检异常。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    /**
     * 共享的 {@link ObjectMapper}，供需要直接操作 JSON 树的场景使用（如解析流式分片）。
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 序列化为紧凑 JSON 字符串。
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw FishAgentException.wrap("JSON 序列化失败", e);
        }
    }

    /**
     * 序列化为带缩进的 JSON 字符串，便于日志与人工排查。
     */
    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw FishAgentException.wrap("JSON 序列化失败", e);
        }
    }

    /**
     * 解析 JSON 对象为 {@link LinkedHashMap}，保留字段顺序。
     * <p>
     * {@code null} 或空白输入返回空 Map（而非抛异常），以简化调用方的空值处理。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw FishAgentException.wrap("JSON 解析失败", e);
        }
    }

    /**
     * 解析 JSON 为指定类型。
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw FishAgentException.wrap("JSON 解析失败", e);
        }
    }
}
