package com.zheng.fishagent.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 内置的 {@link Tracer} 实现：用 UUID 生成 ID，不向任何后端导出。
 * <p>
 * 它的用途是「把 ID 送进日志」——配合 {@link Span#current()}，使用者在监听器里就能打印出
 * 带 traceId 的日志行，从而在多会话并发时区分归属。要真正上报到 OpenTelemetry 等系统，
 * 请自行实现 {@link Tracer} 并在此处替换。
 *
 * <h2>traceId 的粒度</h2>
 * <ul>
 *   <li>{@link #create()}：<b>每次 run 生成一个新的 traceId</b>（最简单，默认含义）；</li>
 *   <li>{@link #create(String)}：复用传入的 traceId，让<b>跨多次 run 的同一次逻辑请求</b>
 *       归入同一条链路（例如 traceId 随「信箱消息」一起流转）。</li>
 * </ul>
 */
public final class SimpleTracer implements Tracer {

    /** 非空表示复用固定的 traceId（跨 run 的同一逻辑请求）；为 null 表示每次 run 新生成。 */
    private final String fixedTraceId;

    private SimpleTracer(String fixedTraceId) {
        this.fixedTraceId = fixedTraceId;
    }

    /** 每次 run 各自一个新 traceId。 */
    public static SimpleTracer create() {
        return new SimpleTracer(null);
    }

    /**
     * 复用指定的 traceId，使多次 run 归入同一链路。
     *
     * @param traceId 已有的 traceId；为 null 或空白时退化为 {@link #create()}
     */
    public static SimpleTracer create(String traceId) {
        return new SimpleTracer(traceId == null || traceId.isBlank() ? newId() : traceId);
    }

    @Override
    public Span start(String name, Span parent) {
        // 当前没有父（栈顶为 NOOP 或空）即为根：traceId 取固定的或新生成
        boolean root = parent == null || parent.traceId().isEmpty();
        String traceId = root
                ? (fixedTraceId == null ? newId() : fixedTraceId)
                : parent.traceId();
        String parentSpanId = root ? null : parent.spanId();
        return new UuidSpan(name, traceId, newId(), parentSpanId);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 一个只读地记录 ID 与属性的 span；{@link #end()} 是留给导出实现的空位。 */
    private static final class UuidSpan implements Span {

        private final String name;
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private UuidSpan(String name, String traceId, String spanId, String parentSpanId) {
            this.name = name;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public String spanId() {
            return spanId;
        }

        @Override
        public String parentSpanId() {
            return parentSpanId;
        }

        @Override
        public Span attribute(String key, Object value) {
            if (key != null) {
                attributes.put(key, value);
            }
            return this;
        }

        @Override
        public void end() {
            // 导出点：接入 OTel / 日志时，在这里上报 name + 三级 ID + attributes
        }

        @Override
        public String toString() {
            return name + "{trace=" + traceId + ", span=" + spanId
                    + ", parent=" + parentSpanId + ", attrs=" + attributes + "}";
        }
    }
}
