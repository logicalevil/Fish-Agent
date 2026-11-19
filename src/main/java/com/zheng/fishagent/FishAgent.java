package com.zheng.fishagent;

import com.zheng.fishagent.agent.Agent;
import com.zheng.fishagent.agent.Span;
import com.zheng.fishagent.agent.ToolCallMode;
import com.zheng.fishagent.agent.Tracer;
import com.zheng.fishagent.agent.ReActAgent;
import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.llm.ollama.OllamaChatModel;
import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.memory.MessageWindowChatMemory;
import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.tool.Tool;
import com.zheng.fishagent.tool.ToolRegistry;

import java.util.Objects;

/**
 * Fish-Agent 的统一入口门面。
 * <p>
 * 这是接入本框架的推荐起点：把「模型 + 工具 + 记忆」用链式调用组装成一个
 * {@link Agent}，屏蔽掉各组件 Builder 的细节。需要更细粒度控制时，仍可直接使用
 * {@link ReActAgent#builder()}、{@link OllamaChatModel#builder()} 等原生 Builder。
 *
 * <pre>{@code
 * Agent agent = FishAgent.builder()
 *         .ollama("qwen2.5:7b")          // 或 .model(myCustomChatModel)
 *         .toolBean(new DemoTools())     // 注解式工具
 *         .session("user-42")            // 带滑动窗口的会话记忆
 *         .streaming(true)
 *         .build();
 *
 * AgentResult result = agent.run("北京现在几点？");
 * System.out.println(result.text());
 * }</pre>
 */
public final class FishAgent {

    private FishAgent() {
    }

    /**
     * 创建一个 Agent 构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快捷创建一个空工具注册表，便于先注册工具再交给 {@link Builder#tools(ToolRegistry)}。
     */
    public static ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    /**
     * 快捷创建本地 Ollama 模型实例。
     */
    public static ChatModel ollamaModel(String modelName) {
        return OllamaChatModel.builder().model(modelName).build();
    }

    /**
     * 快捷创建本地 Ollama 模型实例。
     */
    public static ChatModel ollamaModel(String baseUrl, String modelName) {
        return OllamaChatModel.builder().baseUrl(baseUrl).model(modelName).build();
    }

    /**
     * Agent 门面构建器。默认使用原生 Tool Calling 模式与流式输出。
     */
    public static final class Builder {

        private final ToolRegistry registry = new ToolRegistry();
        private ChatModel model;
        private String modelName;
        private String baseUrl = OllamaChatModel.DEFAULT_BASE_URL;
        private ChatMemory memory;
        private String instructions;
        private String systemPrompt;
        private ToolCallMode mode = ToolCallMode.NATIVE_TOOLS;
        private boolean streaming = true;
        private int maxIterations = 8;
        private int maxRepeatedToolCalls = 3;
        private Double temperature;
        private Tracer tracer = Tracer.NOOP;
        private MemoryLock memoryLock = MemoryLock.local();

        private Builder() {
        }

        /**
         * 使用本地 Ollama 模型（地址默认 {@code http://localhost:11434}）。
         */
        public Builder ollama(String modelName) {
            this.modelName = Objects.requireNonNull(modelName, "模型名不能为空");
            return this;
        }

        /**
         * 使用指定地址的 Ollama 模型。
         */
        public Builder ollama(String baseUrl, String modelName) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl 不能为空");
            this.modelName = Objects.requireNonNull(modelName, "模型名不能为空");
            return this;
        }

        /**
         * 使用自定义模型实现，优先级高于 {@link #ollama(String)}。
         */
        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        /**
         * 采样温度。对内置 Ollama 模型会同时作用于模型默认参数。
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 注册注解式工具：扫描对象中被 {@code @AgentTool} 标注的方法。
         */
        public Builder toolBean(Object bean) {
            registry.register(bean);
            return this;
        }

        /**
         * 注册一个工具实现。
         */
        public Builder tool(Tool tool) {
            registry.register(tool);
            return this;
        }

        /**
         * 批量注册工具实现。
         */
        public Builder tools(Tool... tools) {
            registry.register(tools);
            return this;
        }

        /**
         * 合并一个已存在的工具注册表。
         */
        public Builder tools(ToolRegistry registry) {
            if (registry != null) {
                registry.all().forEach(this.registry::register);
            }
            return this;
        }

        /**
         * 使用自定义记忆实现。
         */
        public Builder memory(ChatMemory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * 使用带滑动窗口的内存记忆，默认保留最近 20 条消息。
         *
         * @param sessionId 会话标识，多轮对话以它隔离
         */
        public Builder session(String sessionId) {
            return session(sessionId, 20);
        }

        /**
         * 使用带滑动窗口的内存记忆。
         *
         * @param sessionId   会话标识
         * @param windowSize  参与上下文的最近消息条数（SYSTEM 消息始终保留）
         */
        public Builder session(String sessionId, int windowSize) {
            this.memory = MessageWindowChatMemory.of(sessionId, windowSize);
            return this;
        }

        /**
         * 追加行为准则，会与内置的 ReAct 格式说明拼接。
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * 完全接管系统提示词，优先级高于 {@link #instructions(String)}。
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 工具调用策略，默认 {@link ToolCallMode#NATIVE_TOOLS}。
         */
        public Builder mode(ToolCallMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode 不能为空");
            return this;
        }

        /**
         * 是否流式输出，默认开启。
         */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /**
         * 单次 run 的最大推理轮数，防止无限循环。
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 同一个工具调用允许重复的最大次数，超过则判定为死循环。
         */
        public Builder maxRepeatedToolCalls(int maxRepeatedToolCalls) {
            this.maxRepeatedToolCalls = maxRepeatedToolCalls;
            return this;
        }

        /**
         * 链路追踪扩展点。缺省为 {@link Tracer#NOOP}（不追踪、零开销）。
         * <p>
         * 设置后，run、模型调用与工具执行会自动生成 span，监听器可用
         * {@link Span#current()} 读取当前 traceId。
         * 内置的 {@code SimpleTracer}（{@code com.zheng.fishagent.agent} 包）
         * 提供基于 UUID 的轻量实现，可直接使用。
         */
        public Builder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        /**
         * 记忆的互斥实现。缺省为 {@link MemoryLock#local()}，仅在当前 JVM 内提供互斥。
         * <p>
         * 当同一份记忆会被<b>多个实例</b>并发访问时（例如多副本部署 + 共享会话），
         * 需注入自行实现的跨进程 {@link MemoryLock}。框架不提供跨进程实现，也不依赖
         * 任何分布式组件——由使用者按自己的基础设施选择。
         * <p>
         * 传入 {@code null} 时回退为默认本地实现。
         */
        public Builder memoryLock(MemoryLock memoryLock) {
            this.memoryLock = memoryLock;
            return this;
        }

        /**
         * 组装并返回 Agent。
         *
         * @throws FishAgentException 未指定任何模型时抛出
         */
        public Agent build() {
            ReActAgent.Builder builder = ReActAgent.builder()
                    .model(resolveModel())
                    .tools(registry)
                    .mode(mode)
                    .streaming(streaming)
                    .maxIterations(maxIterations)
                    .maxRepeatedToolCalls(maxRepeatedToolCalls)
                    .tracer(tracer)
                    .memoryLock(memoryLock);
            if (memory != null) {
                builder.memory(memory);
            }
            if (instructions != null) {
                builder.instructions(instructions);
            }
            if (systemPrompt != null) {
                builder.systemPrompt(systemPrompt);
            }
            if (temperature != null) {
                builder.temperature(temperature);
            }
            return builder.build();
        }

        private ChatModel resolveModel() {
            if (model != null) {
                return model;
            }
            if (modelName == null || modelName.isBlank()) {
                throw FishAgentException.of("未指定模型：请调用 ollama(model) 或 model(ChatModel)");
            }
            OllamaChatModel.Builder builder = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .model(modelName);
            if (temperature != null) {
                builder.temperature(temperature);
            }
            return builder.build();
        }
    }
}
