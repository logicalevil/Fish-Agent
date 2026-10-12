package com.zheng.fishagent.agent;

import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.llm.ChatRequest;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.llm.StreamListener;
import com.zheng.fishagent.llm.TokenUsage;
import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.memory.InMemoryChatMemory;
import com.zheng.fishagent.memory.LockedChatMemory;
import com.zheng.fishagent.memory.lock.MemoryLock;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.prompt.ReActOutputParser;
import com.zheng.fishagent.prompt.ReActPrompt;
import com.zheng.fishagent.prompt.ReActStep;
import com.zheng.fishagent.tool.Tool;
import com.zheng.fishagent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认 Agent 实现：基于 ReAct（Reason + Act）循环。
 * <p>
 * 每一轮向模型发起一次请求；若模型请求工具调用，则执行工具并把结果回填，
 * 继续下一轮；若模型直接给出回答，则结束。支持原生 Tool Calling 与文本 ReAct 两种模式，
 * 以及在流式场景下只把最终答案推送给用户（过滤掉中间的思考与工具步骤）。
 *
 * <h2>核心机制</h2>
 * <b>终止条件只有一个</b>：某一轮模型没有请求任何工具，该轮内容即最终答案。
 * 围绕它有三个出口，分别对应三种结束方式：
 * <ul>
 *   <li><b>正常结束</b>：模型不再调用工具，{@code stopReason} 取模型的 finishReason，{@code success = true}；</li>
 *   <li><b>轮数耗尽</b>：达到 {@link #maxIterations}，用 {@link #fallbackText()} 兜底，{@code success = false}；</li>
 *   <li><b>检测到死循环</b>：同一工具调用重复超限，{@code success = false}。</li>
 * </ul>
 * 后两种情况都不抛异常，而是通过 {@link AgentResult#success()} 与 {@link AgentResult#stopReason()}
 * 把「未能正常完成」的事实交给调用方判断，由调用方决定是重试还是展示半成品。
 *
 * <h2>两种模式（{@link ToolCallMode}）的差异</h2>
 * 差异被收敛在少数几个方法里（{@link #resolveToolCalls}、{@link #observation}、{@link #invoke}），
 * 循环主体本身两种模式共用：
 * <pre>
 *                        NATIVE_TOOLS                    REACT_TEXT
 * 工具下发给模型         请求的 tools 字段（结构化）       系统提示词中的文本描述
 * 识别工具调用           读取 message.toolCalls()         正则解析 Action / Action Input
 * 工具结果回填           tool 角色消息                    user 角色包裹 Observation 文本
 * 流式输出               全部 token 直接转发               经 FinalAnswerGate 门控
 * </pre>
 *
 * <h2>并发与状态</h2>
 * 本类是<b>有状态</b>的：会话记忆（{@link ChatMemory}）绑定在实例上，而 {@link #run} 会读写它。
 * 因此<b>同一个实例不应被多个线程并发调用</b>。需要并发时，请为每个会话创建独立的 Agent 实例，
 * 或由调用方自行加锁。框架内部不创建任何线程，也不做同步。
 * <p>
 * 状态还包括<b>工具集快照</b>：系统提示词在首次使用后即冻结（见 {@link #cachedSystemPrompt}），
 * 因此所有工具须在首次 {@link #run} 之前注册完毕。
 */
public final class ReActAgent implements Agent {

    // ------------------------------------------------------------------ 不可变配置（构造后不再改变）

    /** 模型调用入口。支持任意 {@link ChatModel} 实现，是替换厂商的唯一接入点。 */
    private final ChatModel model;

    /** 工具注册表。为空时退化为纯对话（不会向模型下发任何工具）。 */
    private final ToolRegistry tools;

    /**
     * 会话记忆。此实例被本 Agent 独占，构成「Agent 有状态」的根源：
     * 每轮的用户输入、助手输出、工具结果都会写入这里，下一轮再取回拼进上下文。
     */
    private final ChatMemory memory;

    /** 用户追加的行为准则，会与内置的 ReAct 格式说明拼接。 */
    private final String instructions;

    /** 完全接管系统提示词；非空时优先级高于 {@link #instructions}。 */
    private final String systemPromptOverride;

    /** 单次 run 的最大模型调用轮数，防止无限循环。 */
    private final int maxIterations;

    /** 同一「工具名 + 参数」组合允许重复执行的次数上限，超过即判定死循环。 */
    private final int maxRepeatedToolCalls;

    /** 采样温度；为 null 时不下发，由模型侧默认值决定。 */
    private final Double temperature;

    /** 是否走流式调用。 */
    private final boolean streaming;

    /** 工具调用策略，决定上面提到的整套模式差异。 */
    private final ToolCallMode mode;

    /**
     * 链路追踪扩展点。默认 {@link Tracer#NOOP}，即不追踪、零开销。
     * <p>
     * 它只在固定的结构边界（{@code agent.run} / {@code model.call} / {@code tool.execute}）
     * 上被调用；生成的 ID 通过 {@link Span#current()} 暴露给 {@link AgentListener}，
     * 因此监听器无需任何传参即可读到本次 run 的 traceId。
     */
    private final Tracer tracer;

    /**
     * 系统提示词缓存。
     * <p>
     * 提示词依赖「已注册的工具列表」，而工具可能在构建之后才注册，因此<b>延迟到首次使用时计算</b>，
     * 算一次后缓存。声明为 {@code volatile} 是为了让多个线程读到的是同一份已发布对象，
     * 避免撕裂；计算过程本身可能重复执行，但结果幂等，可接受。
     * <p>
     * <b>快照语义（重要）</b>：缓存一经写入<b>不再失效</b>，即系统提示词定格为「首次使用时的工具集」。
     * 因此<b>所有工具必须在首次 {@link #run} 之前注册完毕</b>。此后新增的工具不会进入提示词：
     * <ul>
     *   <li>{@link ToolCallMode#REACT_TEXT}：提示词承载完整工具清单，新工具对模型<b>完全不可见</b>；</li>
     *   <li>{@link ToolCallMode#NATIVE_TOOLS}：若首次计算时工具集为空，提示词会一直停留在
     *       「没有可用工具」分支，模型可能忽略请求体 {@code tools} 字段中已下发的工具。</li>
     * </ul>
     * 若确需运行期变更工具集，应改为「版本化缓存」（按工具集的变更版本重算提示词），而非复用本缓存。
     */
    private volatile String cachedSystemPrompt;

    private ReActAgent(Builder builder) {
        // 模型是硬性依赖：没有模型就无法工作，直接在此处快速失败
        this.model = Objects.requireNonNull(builder.model, "model 不能为空");
        // 以下三项均为可选，缺省时回退到安全默认值，避免调用方做空判断
        this.tools = builder.tools == null ? new ToolRegistry() : builder.tools;
        // 记忆统一经锁装饰器访问：未配置锁时回退 MemoryLock.local()（等价于原先的 synchronized），
        // 需要跨实例互斥时由调用方注入自定义实现。已是 LockedChatMemory 时不重复包裹
        MemoryLock lock = builder.memoryLock == null ? MemoryLock.local() : builder.memoryLock;
        this.memory = LockedChatMemory.wrap(
                builder.memory == null ? new InMemoryChatMemory() : builder.memory, lock);
        this.instructions = builder.instructions;
        this.systemPromptOverride = builder.systemPromptOverride;
        // 轮数下限为 1：至少允许一次模型调用，否则 run 会直接空转退出
        this.maxIterations = Math.max(1, builder.maxIterations);
        this.maxRepeatedToolCalls = Math.max(1, builder.maxRepeatedToolCalls);
        this.temperature = builder.temperature;
        this.streaming = builder.streaming;
        this.mode = builder.mode;
        this.tracer = builder.tracer == null ? Tracer.NOOP : builder.tracer;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ToolCallMode mode() {
        return mode;
    }

    public boolean streaming() {
        return streaming;
    }

    public int maxIterations() {
        return maxIterations;
    }

    @Override
    public ChatMemory memory() {
        return memory;
    }

    @Override
    public List<Tool> tools() {
        return tools.all();
    }

    /**
     * 返回本次会话使用的系统提示词。
     * <p>
     * 组装规则：{@link #systemPromptOverride} 完全接管时直接使用；
     * 否则由「行为准则（{@code instructions}）+ 模式相关的格式说明 + 工具描述」拼装。
     * 结果做一次性缓存（见 {@link #cachedSystemPrompt}），因为每次请求都要用它，重复拼装没有意义。
     */
    @Override
    public String systemPrompt() {
        String cached = cachedSystemPrompt;
        if (cached != null) {
            return cached;
        }
        String prompt = systemPromptOverride != null
                ? systemPromptOverride
                : (mode == ToolCallMode.NATIVE_TOOLS
                        // 原生模式：工具 Schema 走请求字段，提示词只需说明行为准则
                        ? ReActPrompt.nativeTools(instructions, tools.specs())
                        // 文本模式：必须把工具清单和输出格式约定写进提示词
                        : ReActPrompt.textMode(instructions, tools.specs()));
        cachedSystemPrompt = prompt;
        return prompt;
    }

    @Override
    public AgentResult run(String userInput) {
        return run(userInput, AgentListener.NOOP);
    }

    /**
     * 执行一轮完整对话（内部可能包含多次模型调用），并返回本次运行的汇总结果。
     * <p>
     * 副作用：会向 {@link #memory} 写入用户输入、助手消息与工具结果。因此本方法<b>不是线程安全</b>的。
     * <p>
     * 本方法在 {@code agent.run} span 内执行；若配置了 {@link Tracer}，监听器回调中可通过
     * {@link Span#current()} 读到本次运行的追踪标识。
     *
     * @param userInput 用户输入；为 null 或空白时不会写入记忆（此时相当于让模型接着现有上下文继续）
     * @param listener  过程事件回调；允许为 null，内部会替换为空实现
     * @return 本次运行的最终回答、轮数、token 用量与结束原因
     * @throws RuntimeException 模型或工具抛出异常时原样向上抛出，并先回调 {@link AgentListener#onError}
     */
    @Override
    public AgentResult run(String userInput, AgentListener listener) {
        // 空对象模式：调用方不必做 null 判断，循环里可以无脑回调
        AgentListener callback = listener == null ? AgentListener.NOOP : listener;
        // 开启本次 run 的顶层 span（未启用追踪时为零开销的 NOOP）；
        // finally 保证异常路径也会关闭，避免当前线程的环境上下文泄漏
        Span runSpan = Spans.open(tracer, "agent.run");
        try {
            return doRun(userInput, callback);
        } finally {
            Spans.close(runSpan);
        }
    }

    /**
     * {@link #run(String, AgentListener)} 的实际执行体，被包在 {@code agent.run} span 之内。
     * 拆成独立方法只是为了让「span 的开合」与「run 的逻辑」在源码里一眼可见。
     */
    private AgentResult doRun(String userInput, AgentListener callback) {
        callback.onStart(userInput);
        // 先落用户输入再进循环，保证构建请求时上下文是完整的
        if (userInput != null && !userInput.isBlank()) {
            memory.add(Message.user(userInput));
        }

        // ---- 循环状态：全部为局部变量，天然隔离多次 run 之间的相互影响 ----
        // 用量累加器（不可变，每次 plus 生成新对象）
        TokenUsage usage = TokenUsage.EMPTY;
        // 工具调用计数：key 为「工具名|参数」签名，用于识别死循环
        Map<String, Integer> callCounts = new LinkedHashMap<>();
        int iteration = 0;
        String lastText = "";
        String stopReason = AgentResult.STOP;

        try {
            // ======================= 主循环 =======================
            // 每轮：请求模型 → 判断有无工具调用 → 有则执行并回填，无则收工
            while (iteration < maxIterations) {
                iteration++;
                // 1) 请求模型（同步或流式，由 streaming 决定）
                ChatResponse response = invoke(callback);
                usage = usage.plus(response.usage());
                Message assistant = response.message();

                // 2) 把模型输出解释为工具调用列表（两种模式的差异在此收敛）
                List<ToolCall> calls = resolveToolCalls(assistant);

                // 3a) 没有工具调用 ⇒ 这就是最终答案，正常收工
                if (calls.isEmpty()) {
                    memory.add(assistant);
                    callback.onMessage(assistant);
                    lastText = finalText(assistant);
                    // 优先透传模型自带的结束原因（如 length），缺失时归为 stop
                    stopReason = response.finishReason() == null || response.finishReason().isBlank()
                            ? AgentResult.STOP
                            : response.finishReason();
                    return finish(callback, lastText, iteration, usage, stopReason, true);
                }

                // 3b) 有工具调用 ⇒ 先把模型消息落库，再逐个执行工具
                // 文本模式下"思考"只在真的要调用工具时展示；
                // 最终答案那一轮的思考没有信息量，且会在答案之后打印造成困惑。
                if (mode == ToolCallMode.REACT_TEXT) {
                    String thought = extractThought(assistant.content());
                    if (!thought.isBlank()) {
                        callback.onThought(thought);
                    }
                }

                // 助手消息（含 tool_calls）必须先于工具结果写入，
                // 否则下一轮上下文里会出现「结果在前面、调用在后面」的非法时序
                memory.add(assistant);
                callback.onMessage(assistant);

                boolean loopDetected = false;
                for (ToolCall call : calls) {
                    // 容错：模型偶尔会用 input/query 之类的通用参数名，normalize 会纠正为声明名
                    ToolCall normalized = tools.normalize(call);
                    // 按签名计数，同一调用第几次出现一目了然
                    int count = callCounts.merge(signature(normalized), 1, Integer::sum);
                    callback.onToolCall(normalized);

                    String result;
                    if (count > maxRepeatedToolCalls) {
                        // 不抛异常，而是把一句「请停止重复调用」当作工具结果喂回去。
                        // 注意：该提示会写入记忆，但本次 run 随即结束，模型当场看不到它；
                        // 它的实际作用是在同一会话复用记忆发起下一次 run 时提醒模型。
                        loopDetected = true;
                        result = "错误：相同的工具调用已重复 " + count
                                + " 次，请停止重复调用，直接基于已有信息给出 Final Answer。";
                    } else {
                        // 工具内部异常已被 ToolRegistry 兜住并转成错误文本，不会中断循环
                        result = executeTool(normalized);
                    }
                    callback.onToolResult(normalized, result);
                    // 工具结果的角色/包装形式由模式决定，见 observation()
                    memory.add(observation(normalized, result));
                }

                // 3c) 判定死循环 ⇒ 提前终止，标记为未成功
                if (loopDetected) {
                    lastText = assistant.content();
                    stopReason = AgentResult.LOOP_DETECTED;
                    return finish(callback, lastText, iteration, usage, stopReason, false);
                }
                // 否则回到循环顶部，带着工具结果再问一次模型
            }

            // 4) 轮数耗尽：把最后一次模型的输出作为兜底回答
            lastText = fallbackText();
            return finish(callback, lastText, iteration, usage, AgentResult.MAX_ITERATIONS, false);
        } catch (RuntimeException e) {
            // 统一在此上报错误事件，异常本身继续向上抛，交由调用方处理（重试/展示）
            callback.onError(e);
            throw e;
        }
    }

    /**
     * 发起一次模型调用，按需选择同步或流式，并为不同模式挂上不同的流式监听器。
     */
    private ChatResponse invoke(AgentListener callback) {
        Span span = Spans.open(tracer, "model.call");
        try {
            ChatRequest request = buildRequest();
            if (!streaming) {
                return model.call(request);
            }
            // 原生模式下正文即最终答案，可以原样转发；
            // 文本模式下正文混杂着 Thought/Action，必须门控后才允许下发。
            StreamListener streamListener = mode == ToolCallMode.NATIVE_TOOLS
                    ? tokenForwarder(callback)
                    : new FinalAnswerGate(callback);
            return model.stream(request, streamListener);
        } finally {
            Spans.close(span);
        }
    }

    /**
     * 执行一次工具调用，并包在 {@code tool.execute} span 内，把工具名记为属性。
     * <p>
     * 与 {@link com.zheng.fishagent.tool.ToolRegistry#execute} 的唯一区别就是这层埋点，
     * 因此单列出来，让 span 的边界正好落在「一次工具执行」上。
     */
    private String executeTool(ToolCall call) {
        Span span = Spans.open(tracer, "tool.execute");
        try {
            span.attribute("tool", call.name());
            return tools.execute(call);
        } finally {
            Spans.close(span);
        }
    }

    /**
     * 拼装一次模型请求。
     * <p>
     * 上下文顺序固定为：系统提示词 → 记忆中的历史消息。
     * 注意系统提示词是每次请求临时加进去的，<b>不写入记忆</b>，
     * 避免它被滑动窗口当作普通消息裁剪掉（窗口实现本身也会保底保留 SYSTEM 消息）。
     */
    private ChatRequest buildRequest() {
        List<Message> context = new ArrayList<>();
        context.add(Message.system(systemPrompt()));
        context.addAll(memory.messages());

        return ChatRequest.builder()
                .messages(context)
                // 只有原生模式才下发结构化工具；文本模式下工具写在提示词里
                .tools(mode == ToolCallMode.NATIVE_TOOLS ? tools.specs() : List.of())
                .temperature(temperature)
                .stream(streaming)
                .build();
    }

    /**
     * 把模型输出解释为工具调用列表。
     * <p>
     * 这是两种模式的关键分叉点之一：原生模式直接读结构化字段，
     * 文本模式则交给 {@link ReActOutputParser} 解析文本协议。
     */
    private List<ToolCall> resolveToolCalls(Message assistant) {
        if (mode == ToolCallMode.NATIVE_TOOLS) {
            return assistant.toolCalls();
        }
        ReActStep step = ReActOutputParser.parse(assistant.content());
        if (step instanceof ReActStep.ToolRequest request) {
            return List.of(ToolCall.of(request.tool(), request.arguments()));
        }
        // FinalAnswer / Unparsed 都按「没有工具调用」处理，即循环可以在这一轮结束
        return List.of();
    }

    /**
     * 文本模式下工具结果的回填形式：用 USER 角色包装 Observation，
     * 兼容不理解 tool 角色的模型。
     * <p>
     * 这是两种模式的另一个分叉点。用 {@code user} 角色是刻意的妥协——
     * 部分模型对非标准角色会报错或忽略该消息。
     */
    private Message observation(ToolCall call, String result) {
        if (mode == ToolCallMode.NATIVE_TOOLS) {
            return Message.tool(call.name(), result);
        }
        return Message.user(ReActPrompt.observationBlock(call.name(), result));
    }

    /**
     * 统一的收尾出口：快照当前记忆并触发 {@link AgentListener#onFinish}。
     * <p>
     * 三个出口（正常/轮数耗尽/死循环）都走这里，保证事件序列一致。
     */
    private AgentResult finish(AgentListener callback, String text, int iterations,
                               TokenUsage usage, String stopReason, boolean success) {
        // AgentResult 内部会对 messages 做 List.copyOf，得到不可变快照，
        // 因此返回值不会随后续 run 修改记忆而发生变化
        AgentResult result = new AgentResult(text, memory.messages(), iterations, usage, stopReason, success);
        callback.onFinish(result);
        return result;
    }

    /**
     * 文本模式下需要把 "Thought / Final Answer" 包装剥掉，只保留真正的回答内容。
     * <p>
     * switch 针对 sealed 接口 {@link ReActStep} 做穷尽匹配，编译器会保证三个分支都被覆盖。
     */
    private String finalText(Message assistant) {
        if (mode == ToolCallMode.NATIVE_TOOLS) {
            return assistant.content();
        }
        ReActStep step = ReActOutputParser.parse(assistant.content());
        return switch (step) {
            // 正常拿到了 Final Answer，取其正文
            case ReActStep.FinalAnswer answer -> answer.text();
            // 格式不符：按原样兜底返回，总比返回空字符串好
            case ReActStep.Unparsed unparsed -> unparsed.raw();
            // 走到这里说明还在要工具，理论上不该出现在终止路径上，返回原文以免丢信息
            case ReActStep.ToolRequest ignored -> assistant.content();
        };
    }

    /**
     * 轮数耗尽时的兜底回答：倒序找最近一条「有内容的助手消息」。
     * <p>
     * 局限：那条消息很可能只是一句「我接下来要查询……」的过程语，
     * 并非合格的答案。因此只作为最后手段，并配合 {@code success = false} 让调用方自行判断。
     */
    private String fallbackText() {
        List<Message> messages = memory.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == com.zheng.fishagent.message.Role.ASSISTANT && !message.content().isBlank()) {
                return finalText(message);
            }
        }
        // 一条可用消息都没有（例如模型全程只输出空内容）
        return "";
    }

    /**
     * 从助手消息中提取思考文本，仅用于文本模式的事件展示。
     */
    private String extractThought(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        ReActStep step = ReActOutputParser.parse(content);
        return switch (step) {
            case ReActStep.FinalAnswer answer -> answer.thought();
            case ReActStep.ToolRequest request -> request.thought();
            // 解析失败时不臆造思考内容
            case ReActStep.Unparsed ignored -> "";
        };
    }

    /**
     * 死循环检测用的调用签名。
     * <p>
     * 以「工具名 + 参数」为键：同名工具换参数属于正常的多步推理，只有完全相同的调用才算重复。
     */
    private static String signature(ToolCall call) {
        return call.name() + "|" + call.arguments();
    }

    /**
     * 原生模式的流式监听器：把 token 原样转发给上层。
     * <p>
     * 注意这里不区分「过程」与「答案」——原生模型的正文通常就是答案。
     * 若模型会在调用工具前输出推理文本，这些文本同样会被转发出去。
     */
    private StreamListener tokenForwarder(AgentListener callback) {
        return new StreamListener() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }
        };
    }

    /**
     * 文本 ReAct 模式下的流式门控：模型会先输出 Thought/Action 才能得到答案，
     * 这些中间步骤不应该直接呈现给用户。这里只在检测到 "Final Answer:" 后开始向下游推送。
     * <p>
     * 实现要点：由于 token 是任意切分的，标记可能跨越多个 token，
     * 因此必须<b>先缓冲、再扫描</b>。用 {@code emittedUpTo} 游标记录已下发的边界，
     * 保证每段文本只推送一次。
     * <p>
     * 一个实例对应一次模型调用，不可跨轮复用。
     */
    private static final class FinalAnswerGate implements StreamListener {

        /** 匹配 "Final Answer:" 标签（容忍前导空格/引用符/列表符，以及加粗与中英文冒号）。 */
        private static final Pattern FINAL_LABEL = Pattern.compile(
                "(?im)^[\\s>*-]*Final\\s*Answer\\s*[*]*\\s*[:：]\\s*");

        /** 匹配 "Action:" 标签，用于判断本轮是工具调用而非最终答案。 */
        private static final Pattern ACTION_LABEL = Pattern.compile(
                "(?im)^[\\s>*-]*Action\\s*[*]*\\s*[:：]");

        private final AgentListener callback;
        /** 累积的所有 token，扫描与切片都基于它。 */
        private final StringBuilder buffer = new StringBuilder();
        /** 已推送到下游的位置；-1 表示尚未发现 Final Answer 标记。 */
        private int emittedUpTo = -1;

        private FinalAnswerGate(AgentListener callback) {
            this.callback = callback;
        }

        @Override
        public void onToken(String token) {
            if (token == null || token.isEmpty()) {
                return;
            }
            buffer.append(token);
            if (emittedUpTo < 0) {
                // 门尚未打开：在缓冲区中寻找 Final Answer 标记
                Matcher matcher = FINAL_LABEL.matcher(buffer);
                if (matcher.find()) {
                    // 标记之后的正文立即下发，并打开门（此后不再走扫描逻辑）
                    emittedUpTo = matcher.end();
                    emitTail();
                    return;
                }
                if (ACTION_LABEL.matcher(buffer).find()) {
                    // 本步是工具调用，丢包并重置缓冲，继续等待真正的 Final Answer。
                    // 重置是必要的：否则下一轮的 Thought 会和上一轮的残留拼在一起，
                    // 可能误判出根本不存在的 Final Answer 标记。
                    buffer.setLength(0);
                }
                return;
            }
            // 门已打开：缓冲区新增部分直接下发
            emitTail();
        }

        /** 推送 {@code [emittedUpTo, buffer.length())} 区间，并推进游标。 */
        private void emitTail() {
            if (emittedUpTo < 0 || emittedUpTo > buffer.length()) {
                return;
            }
            String tail = buffer.substring(emittedUpTo);
            if (!tail.isEmpty()) {
                emittedUpTo = buffer.length();
                callback.onToken(tail);
            }
        }
    }

    // ------------------------------------------------------------------ Builder

    /**
     * {@link ReActAgent} 的构建器。
     * <p>
     * <b>必填项只有 {@link #model(ChatModel)}</b>，未设置时 {@link #build()} 会快速失败。
     * 其余字段都有安全默认值：{@link ToolCallMode#NATIVE_TOOLS} 模式、流式输出、
     * 不限轮数的工具集、进程内记忆与 {@link Tracer#NOOP} 追踪。
     */
    public static final class Builder {

        private ChatModel model;
        private ToolRegistry tools;
        private ChatMemory memory;
        private MemoryLock memoryLock = MemoryLock.local();
        private String instructions = ReActPrompt.DEFAULT_INSTRUCTIONS;
        private String systemPromptOverride;
        private int maxIterations = 8;
        private int maxRepeatedToolCalls = 3;
        private Double temperature;
        private boolean streaming = true;
        private ToolCallMode mode = ToolCallMode.NATIVE_TOOLS;
        private Tracer tracer = Tracer.NOOP;

        private Builder() {
        }

        /** 必填项：模型实现。 */
        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        public Builder memory(ChatMemory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * 记忆的互斥实现。缺省为 {@link MemoryLock#local()}，仅在当前 JVM 内提供互斥。
         * <p>
         * 当同一份记忆会被<b>多个实例</b>并发访问时，需注入自行实现的跨进程
         * {@link MemoryLock}；否则并发写入可能导致消息丢失或顺序错乱。
         * 传入 {@code null} 时回退为默认本地实现。
         * <p>
         * 该锁会被 {@link LockedChatMemory} 用于包裹 {@link #memory(ChatMemory)} 配置的记忆；
         * 若传入的记忆本身已是 {@code LockedChatMemory}，则不再重复包裹。
         */
        public Builder memoryLock(MemoryLock memoryLock) {
            this.memoryLock = memoryLock;
            return this;
        }

        /**
         * 追加行为准则（会与内置的 ReAct 格式说明拼接）。
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * 完全接管系统提示词（优先级高于 {@link #instructions(String)}）。
         * <p>
         * 注意：设了它之后，文本模式下框架注入的工具清单与格式约定也会被一并覆盖，
         * 此时需要自行保证提示词里包含了这些内容。
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPromptOverride = systemPrompt;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 同一个工具调用最多允许重复执行的次数，超过则判定为死循环并终止。
         */
        public Builder maxRepeatedToolCalls(int maxRepeatedToolCalls) {
            this.maxRepeatedToolCalls = maxRepeatedToolCalls;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public Builder mode(ToolCallMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode 不能为空");
            return this;
        }

        /**
         * 链路追踪扩展点。缺省为 {@link Tracer#NOOP}（不追踪、零开销）。
         * <p>
         * 设置后，{@code run} / 模型调用 / 工具执行会自动生成 span，
         * 监听器与业务代码可用 {@link Span#current()} 读取当前 ID。
         */
        public Builder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
