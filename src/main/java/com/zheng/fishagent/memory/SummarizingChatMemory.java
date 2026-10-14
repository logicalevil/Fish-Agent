package com.zheng.fishagent.memory;

import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.llm.ChatRequest;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 上下文摘要压缩装饰器：把超出阈值的早期消息交给模型压成一条摘要，
 * 使 {@link #messages()} 返回「摘要 + 最近若干条原文」，而不是像
 * {@link MessageWindowChatMemory} 那样把早期消息直接丢弃。
 * <p>
 * <h2>为什么需要它</h2>
 * 滑窗裁剪的缺陷是「早期信息硬丢失」——窗口一滑，此前的结论、约定与事实都不再进入模型视野。
 * 摘要则让早期信息以压缩形态继续可见，代价是有损。
 * <p>
 * <h2>何时触发</h2>
 * 在 {@link #add} 时判断「尚未被摘要的消息数」是否超过 {@code triggerMessages}，
 * 超过则<b>同步</b>执行一次压缩。框架不创建线程（见 {@code agent-execution} 规范），
 * 因此压缩必然占用调用线程；好在触发频率低（每累积 {@code triggerMessages} 条才一次）。
 * <p>
 * <h2>产出形态</h2>
 * <pre>
 *   [摘要消息（USER 角色，带固定前缀）] [尾部第 1 条] ... [最新一条]
 * </pre>
 * 摘要消息使用 USER 而非 SYSTEM 角色，因为 SYSTEM 通道由框架的提示词独占
 * （见 {@code ReActAgent}：系统提示词按请求前置且从不写入记忆）。
 * <p>
 * <h2>不要嵌套窗口装饰器</h2>
 * {@code delegate} <b>必须保存完整原文</b>（例如 {@link InMemoryChatMemory}、
 * {@link FileChatMemory}）。若把 {@link MessageWindowChatMemory} 套在里层，
 * 本类读到的「原文」其实是窗口裁剪后的结果，会同时破坏三件事：
 * <ul>
 *   <li>摘要只能看到最近的若干条，早期信息压缩不到；</li>
 *   <li>底层条数被窗口封顶后永不增长，压缩阈值可能再也触发不了；</li>
 *   <li>{@link #fullHistory()} 名不副实，返回的是裁剪结果。</li>
 * </ul>
 * 精简上下文本来就是本类的职责，无需再叠加窗口——两者应<b>并列</b>而非嵌套：
 * <pre>{@code
 * SummarizingChatMemory summary = SummarizingChatMemory.of(FileChatMemory.of("s1"), model, 20, 5);
 * MessageWindowChatMemory window = MessageWindowChatMemory.windowing(FileChatMemory.of("s2"), 20);
 * ChatMemory memory = 需要摘要 ? summary : window;   // 二选一，共享同一份落盘原文
 * }</pre>
 * <p>
 * <h2>链式累积</h2>
 * 再次压缩时，上一次的摘要会作为输入的一部分参与新的摘要。否则二次压缩会把
 * 「上一次摘要」本身压掉，导致最早期的信息彻底消失。
 * <p>
 * <h2>失败回退</h2>
 * 摘要生成失败（模型抛异常或返回空白）时不会中断对话，而是退化为「只保留最近
 * {@code keepMessages} 条」，即窗口裁剪语义。摘要属于优化手段，不该让对话失败。
 * <p>
 * <b>原文始终保留</b>：压缩只改变 {@link #messages()} 的返回结果，
 * 全部原始消息可通过 {@link #fullHistory()} 读取（前提是 delegate 未做裁剪，见上）。
 */
public final class SummarizingChatMemory implements ChatMemory {

    /**
     * 摘要消息的固定前缀，用于把「摘要」与真实用户输入区分开。
     */
    public static final String SUMMARY_PREFIX = "Here is a summary of the conversation so far:";

    /** 渲染待摘要消息时，单条工具结果的截断长度。避免摘要输入过长。 */
    private static final int TOOL_RESULT_LIMIT = 500;

    private static final String SUMMARY_PROMPT_TEMPLATE = """
            请把下面这段对话压缩成一段简短的摘要，用于让后续对话继续时能了解已发生的事。
            摘要必须覆盖以下几点，尽量精简（不超过 200 字）：
            1. 用户的目标或意图
            2. 已经确认的结论与做出的决定
            3. 已产生的关键数据或产物
            4. 尚未完成、需要继续处理的事项

            对话内容：
            %s""";

    private final ChatMemory delegate;
    private final ChatModel model;
    private final int triggerMessages;
    private final int keepMessages;

    /** 摘要正文；从未成功压缩过时为 null。 */
    private String summary;

    /**
     * 已进入摘要的消息条数（delegate 中的下标）。
     * 压缩后推进到「尾部起点」，避免同一批消息被反复摘要。
     */
    private int summarizedUpTo;

    /** 上一次摘要是否成功，便于诊断降级情况。 */
    private boolean lastCompactionSucceeded = true;

    private SummarizingChatMemory(ChatMemory delegate, ChatModel model, int triggerMessages, int keepMessages) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.model = Objects.requireNonNull(model, "model 不能为空");
        if (triggerMessages < 1) {
            throw FishAgentException.of("triggerMessages 必须大于 0，当前为 %d", triggerMessages);
        }
        if (keepMessages < 1) {
            throw FishAgentException.of("keepMessages 必须大于 0，当前为 %d", keepMessages);
        }
        if (keepMessages >= triggerMessages) {
            throw FishAgentException.of(
                    "keepMessages(%d) 必须小于 triggerMessages(%d)，否则压缩后会立即再次触发",
                    keepMessages, triggerMessages);
        }
        this.triggerMessages = triggerMessages;
        this.keepMessages = keepMessages;
    }

    /**
     * 构建摘要记忆。
     *
     * @param delegate        底层记忆，原文由它全量保存；<b>不要传窗口装饰器</b>，
     *                        否则摘要只能看到被裁剪过的历史（见类注释）
     * @param model           用于生成摘要的模型；可以是与主对话不同的（例如更轻量的）模型
     * @param triggerMessages 未摘要消息数超过该值时触发压缩
     * @param keepMessages    压缩后保留的最近消息条数，必须小于 {@code triggerMessages}
     */
    public static SummarizingChatMemory of(ChatMemory delegate, ChatModel model,
                                           int triggerMessages, int keepMessages) {
        return new SummarizingChatMemory(delegate, model, triggerMessages, keepMessages);
    }

    /**
     * 使用默认参数构建：超过 20 条未摘要消息时压缩，保留最近 5 条。
     */
    public static SummarizingChatMemory of(ChatMemory delegate, ChatModel model) {
        return new SummarizingChatMemory(delegate, model, 20, 5);
    }

    /**
     * 当前摘要正文；从未成功压缩过时为 {@code null}。
     */
    public String summary() {
        return summary;
    }

    /**
     * 上一次摘要是否成功。为 {@code false} 表示最近一次压缩已降级为窗口裁剪。
     */
    public boolean lastCompactionSucceeded() {
        return lastCompactionSucceeded;
    }

    /**
     * 触发阈值。
     */
    public int triggerMessages() {
        return triggerMessages;
    }

    /**
     * 压缩后保留的最近消息条数。
     */
    public int keepMessages() {
        return keepMessages;
    }

    /**
     * 未被压缩的完整原文，直接来自 {@code delegate}。
     * <p>
     * 若 {@code delegate} 本身是窗口装饰器，返回的会是裁剪结果——本类要求 delegate
     * 保存全量原文（见类注释）。
     */
    public List<Message> fullHistory() {
        return delegate.messages();
    }

    /**
     * 底层记忆，便于诊断与测试。
     */
    public ChatMemory delegate() {
        return delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public void add(Message message) {
        if (message == null) {
            return;
        }
        delegate.add(message);
        // 只统计「尚未进入摘要」的消息：delegate 中位于 summarizedUpTo 之后的部分。
        // 严格大于：keep 条被压缩后剩下的尾部即 pending，等于阈值时还不需要压缩
        if (Math.max(0, delegate.messages().size() - summarizedUpTo) > triggerMessages) {
            compact();
        }
    }

    @Override
    public List<Message> messages() {
        List<Message> all = delegate.messages();
        if (summary == null) {
            // 尚未成功压缩过：退化为窗口语义，避免首次压缩前的消息无限增长
            return tail(all, keepMessages);
        }
        List<Message> result = new ArrayList<>();
        result.add(summaryMessage(summary));
        result.addAll(tail(all, keepMessages));
        return result;
    }

    @Override
    public void clear() {
        delegate.clear();
        summary = null;
        summarizedUpTo = 0;
        lastCompactionSucceeded = true;
    }

    /**
     * 执行一次同步压缩：把待摘要区间的消息交给模型生成摘要，并推进游标。
     * <p>
     * 失败时不抛异常——退化为窗口裁剪并推进游标，避免每次 {@code add} 都重试导致持续变慢。
     */
    private void compact() {
        List<Message> all = delegate.messages();
        if (all.size() <= keepMessages) {
            return;
        }
        int cutoff = all.size() - keepMessages;
        List<Message> prefix = all.subList(0, cutoff);

        try {
            String generated = generateSummary(prefix);
            if (generated == null || generated.isBlank()) {
                throw FishAgentException.of("摘要模型返回了空内容");
            }
            summary = generated;
            lastCompactionSucceeded = true;
        } catch (Exception e) {
            // 摘要只是优化：失败时保留上一份摘要（若有），退化为窗口裁剪
            lastCompactionSucceeded = false;
        } finally {
            // 无论成功与否都推进游标，避免同一批消息被反复摘要
            summarizedUpTo = cutoff;
        }
    }

    /**
     * 调用模型生成摘要。链式累积：已有摘要时，把它并入本次输入，使早期信息不会丢失。
     */
    private String generateSummary(List<Message> prefix) {
        List<Message> inputs = new ArrayList<>();
        if (summary != null) {
            // 把上一次的摘要作为一条普通消息带回输入，让本次摘要在其基础上累积
            inputs.add(summaryMessage(summary));
        }
        inputs.addAll(prefix);

        String prompt = SUMMARY_PROMPT_TEMPLATE.formatted(render(inputs));
        ChatResponse response = model.call(ChatRequest.builder()
                .message(Message.user(prompt))
                .build());
        return response == null || response.text() == null ? null : response.text().strip();
    }

    /**
     * 把消息渲染为供摘要模型阅读的文本。
     * <p>
     * 工具结果会被截断：摘要是压缩操作，没有必要按原文长度把全部工具输出喂给摘要模型。
     */
    private static String render(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(label(message.role())).append(truncate(message.content())).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String label(Role role) {
        return switch (role) {
            case USER -> "Human: ";
            case ASSISTANT -> "AI: ";
            case TOOL -> "Tool: ";
            case SYSTEM -> "System: ";
        };
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TOOL_RESULT_LIMIT
                ? text
                : text.substring(0, TOOL_RESULT_LIMIT) + "…（已截断）";
    }

    /** 构造摘要消息：USER 角色 + 固定前缀，便于识别这是摘要而非真实输入。 */
    private static Message summaryMessage(String summary) {
        return Message.user(SUMMARY_PREFIX + "\n\n" + summary);
    }

    /**
     * 取最近 {@code count} 条消息。SYSTEM 消息不参与计数，但会保留在最前面——
     * 与 {@link MessageWindowChatMemory} 的语义保持一致。
     */
    private static List<Message> tail(List<Message> all, int count) {
        List<Message> systems = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message message : all) {
            if (message.role() == Role.SYSTEM) {
                systems.add(message);
            } else {
                others.add(message);
            }
        }
        List<Message> result = new ArrayList<>(systems);
        if (others.size() <= count) {
            result.addAll(others);
        } else {
            result.addAll(others.subList(others.size() - count, others.size()));
        }
        return result;
    }

    @Override
    public String toString() {
        return "SummarizingChatMemory(id=" + id()
                + ", trigger=" + triggerMessages
                + ", keep=" + keepMessages
                + ", summarized=" + (summary != null)
                + ", lastSucceeded=" + lastCompactionSucceeded
                + ", delegate=" + delegate.getClass().getSimpleName() + ")";
    }
}
