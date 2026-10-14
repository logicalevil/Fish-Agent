package com.zheng.fishagent.memory;

import com.zheng.fishagent.agent.FakeChatModel;
import com.zheng.fishagent.core.FishAgentException;
import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.llm.ChatRequest;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.llm.StreamListener;
import com.zheng.fishagent.llm.TokenUsage;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizingChatMemoryTest {

    // ---------- 触发时机 ----------

    @Test
    void 未超阈值时不应调用模型() {
        FakeChatModel model = new FakeChatModel();
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 20, 5);

        for (int i = 0; i < 20; i++) {
            memory.add(Message.user("m" + i));
        }

        assertTrue(model.requests().isEmpty(), "未超阈值不应触发摘要");
        assertNull(memory.summary());
    }

    @Test
    void 超过阈值应触发一次摘要() {
        FakeChatModel model = new FakeChatModel().thenText("这是摘要内容");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 2);

        for (int i = 0; i < 6; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(1, model.requests().size(), "应恰好压缩一次");
        assertEquals("这是摘要内容", memory.summary());
        assertTrue(memory.lastCompactionSucceeded());
    }

    @Test
    void 压缩后同一批消息不应重复压缩() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 2);

        for (int i = 0; i < 8; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(1, model.requests().size(), "同一批消息不应被反复摘要");
    }

    // ---------- 产出形态 ----------

    @Test
    void 压缩后应返回摘要加保留条数() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 2);

        for (int i = 0; i < 6; i++) {
            memory.add(Message.user("m" + i));
        }

        List<Message> context = memory.messages();
        assertEquals(3, context.size(), "应为 1 条摘要 + 2 条尾部");
        assertEquals(Role.USER, context.getFirst().role());
        assertTrue(context.getFirst().content().startsWith(SummarizingChatMemory.SUMMARY_PREFIX));
    }

    @Test
    void 摘要应在最前且尾部保持原样() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 2);

        for (int i = 0; i < 6; i++) {
            memory.add(Message.user("m" + i));
        }

        List<Message> context = memory.messages();
        assertTrue(context.get(0).content().contains("S1"), "第一条应为摘要");
        assertEquals("m4", context.get(1).content(), "尾部消息应逐字保留");
        assertEquals("m5", context.get(2).content());
    }

    @Test
    void 摘要消息应带固定前缀且角色为USER() {
        // 「若未在会话中前插系统提示词，摘要内容应包含摘要前缀」
        FakeChatModel model = new FakeChatModel().thenText("摘要正文");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 3, 1);

        for (int i = 0; i < 4; i++) {
            memory.add(Message.user("m" + i));
        }

        Message summaryMessage = memory.messages().getFirst();
        assertEquals(Role.USER, summaryMessage.role());
        assertTrue(summaryMessage.content().startsWith(SummarizingChatMemory.SUMMARY_PREFIX));
        assertTrue(summaryMessage.content().contains("摘要正文"));
    }

    // ---------- 链式累积 ----------

    @Test
    void 二次压缩应把上次摘要并入输入() {
        FakeChatModel model = new FakeChatModel().thenText("S1").thenText("S2");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 4, 1);

        for (int i = 0; i < 5; i++) {
            memory.add(Message.user("m" + i));
        }
        assertEquals("S1", memory.summary());

        // 再累积到再次超阈值
        for (int i = 5; i < 10; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(2, model.requests().size(), "应触发第二次压缩");
        String secondPrompt = model.requests().getLast().messages().getFirst().content();
        assertTrue(secondPrompt.contains("S1"), "第二次压缩的输入应包含上一次摘要");
        assertEquals("S2", memory.summary());
    }

    // ---------- 原文保留 ----------

    @Test
    void 全文应完整保留且不因压缩减少() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 2);

        for (int i = 0; i < 10; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(10, memory.fullHistory().size(), "原文条数不应因压缩减少");
        assertEquals("m0", memory.fullHistory().getFirst().content());
    }

    @Test
    void 压缩前后底层条数不变() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        InMemoryChatMemory base = new InMemoryChatMemory("s1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(base, model, 5, 2);

        for (int i = 0; i < 6; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(6, base.size());
        assertEquals(6, memory.fullHistory().size());
    }

    // ---------- 失败回退 ----------

    @Test
    void 摘要模型异常时不应中断对话() {
        ChatModel broken = new ThrowingChatModel();
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), broken, 3, 1);

        for (int i = 0; i < 4; i++) {
            memory.add(Message.user("m" + i));    // 不应抛异常
        }

        assertFalse(memory.lastCompactionSucceeded());
        assertNull(memory.summary());
    }

    @Test
    void 失败后仍可继续写入且上下文受限() {
        ChatModel broken = new ThrowingChatModel();
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), broken, 3, 1);

        for (int i = 0; i < 10; i++) {
            memory.add(Message.user("m" + i));
        }

        List<Message> context = memory.messages();
        assertEquals(1, context.size(), "失败时退化为窗口裁剪");
        assertEquals("m9", context.getFirst().content());
    }

    @Test
    void 摘要返回空白时视为失败() {
        FakeChatModel model = new FakeChatModel().thenText("   ");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 3, 1);

        for (int i = 0; i < 4; i++) {
            memory.add(Message.user("m" + i));
        }

        assertFalse(memory.lastCompactionSucceeded());
        assertNull(memory.summary());
    }

    @Test
    void 失败后不应反复重试同一批消息() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(ChatRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("模型不可用");
            }

            @Override
            public ChatResponse stream(ChatRequest request, StreamListener listener) {
                return call(request);
            }
        };
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), broken, 3, 1);

        for (int i = 0; i < 4; i++) {
            memory.add(Message.user("m" + i));
        }
        int afterFirst = calls.get();

        // 再写入 2 条，尚未再次超阈值
        memory.add(Message.user("m4"));
        memory.add(Message.user("m5"));

        assertEquals(afterFirst, calls.get(), "不应在未再次超阈值时重试");
    }

    // ---------- 参数校验 ----------

    @Test
    void keepMessages不小于trigger时应快速失败() {
        FakeChatModel model = new FakeChatModel();
        assertThrows(FishAgentException.class,
                () -> SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 20, 20));
        assertThrows(FishAgentException.class,
                () -> SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 8));
    }

    @Test
    void 非法数值应快速失败() {
        FakeChatModel model = new FakeChatModel();
        assertThrows(FishAgentException.class,
                () -> SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 0, 1));
        assertThrows(FishAgentException.class,
                () -> SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 5, 0));
    }

    @Test
    void 合法参数应构造成功() {
        SummarizingChatMemory memory =
                SummarizingChatMemory.of(new InMemoryChatMemory("s1"), new FakeChatModel(), 20, 5);

        assertEquals(20, memory.triggerMessages());
        assertEquals(5, memory.keepMessages());
    }

    @Test
    void 默认参数为二十与五() {
        SummarizingChatMemory memory =
                SummarizingChatMemory.of(new InMemoryChatMemory("s1"), new FakeChatModel());

        assertEquals(20, memory.triggerMessages());
        assertEquals(5, memory.keepMessages());
    }

    @Test
    void 空依赖应快速失败() {
        assertThrows(NullPointerException.class,
                () -> SummarizingChatMemory.of(null, new FakeChatModel(), 20, 5));
        assertThrows(NullPointerException.class,
                () -> SummarizingChatMemory.of(new InMemoryChatMemory("s1"), null, 20, 5));
    }

    // ---------- 组合与透传 ----------

    @Test
    void 标识应透传底层() {
        SummarizingChatMemory memory =
                SummarizingChatMemory.of(new InMemoryChatMemory("s1"), new FakeChatModel(), 20, 5);

        assertEquals("s1", memory.id());
    }

    @Test
    void 包装文件记忆时消息应正常落盘(@TempDir Path tempDir) {
        FileChatMemory file = FileChatMemory.of("s1", tempDir);
        SummarizingChatMemory memory = SummarizingChatMemory.of(file, new FakeChatModel().thenText("S1"), 3, 1);

        for (int i = 0; i < 4; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(4, FileChatMemory.of("s1", tempDir).size(), "消息应已落盘");
    }

    @Test
    void 摘要输入应基于完整原文而非窗口裁剪结果() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        InMemoryChatMemory base = new InMemoryChatMemory("s1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(base, model, 5, 2);

        // 前 3 条故意远离尾部：若摘要基于「窗口裁剪后的最近若干条」，它们不会出现在提示词里
        memory.add(Message.user("最早的单据编号 20260101"));
        memory.add(Message.user("这件事的处理结论是不退款"));
        memory.add(Message.user("用户当初留下的联系方式是 13800000000"));
        for (int i = 0; i < 5; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(1, model.requests().size(), "应触发一次压缩");
        String prompt = model.requests().getFirst().messages().getFirst().content();
        assertTrue(prompt.contains("最早的单据编号 20260101"), "早期消息应进入摘要输入");
        assertTrue(prompt.contains("这件事的处理结论是不退款"), "早期结论应进入摘要输入");
        assertTrue(prompt.contains("13800000000"), "早期关键数据应进入摘要输入");
    }

    @Test
    void 底层不做窗口裁剪时摘要才可能看到全部原文(@TempDir Path tempDir) {
        // 并列结构：摘要直接建立在落盘记忆上，原文与上下文互不干扰
        FakeChatModel model = new FakeChatModel().thenText("S1");
        FileChatMemory file = FileChatMemory.of("s1", tempDir);
        SummarizingChatMemory memory = SummarizingChatMemory.of(file, model, 5, 2);

        for (int i = 0; i < 6; i++) {
            memory.add(Message.user("m" + i));
        }

        assertEquals(6, memory.fullHistory().size(), "落盘原文应完整");
        assertEquals(6, FileChatMemory.of("s1", tempDir).size(), "重新打开后仍应读到全部原文");
        String prompt = model.requests().getFirst().messages().getFirst().content();
        assertTrue(prompt.contains("m0"), "早期消息应进入摘要输入");
        assertTrue(prompt.contains("m3"));
    }

    @Test
    void 与窗口装饰器双向组合均应可用() {
        // 摘要在外，窗口在内
        ChatMemory outerSummary = SummarizingChatMemory.of(
                MessageWindowChatMemory.of("s1", 50), new FakeChatModel().thenText("S1"), 5, 2);
        for (int i = 0; i < 6; i++) {
            outerSummary.add(Message.user("m" + i));
        }
        assertTrue(outerSummary.messages().size() <= 3);

        // 窗口在外，摘要在内
        ChatMemory outerWindow = MessageWindowChatMemory.windowing(
                SummarizingChatMemory.of(new InMemoryChatMemory("s2"), new FakeChatModel().thenText("S2"), 5, 2), 10);
        for (int i = 0; i < 6; i++) {
            outerWindow.add(Message.user("m" + i));
        }
        assertTrue(outerWindow.messages().size() <= 10);
    }

    @Test
    void 清空应重置摘要状态() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 3, 1);
        for (int i = 0; i < 4; i++) {
            memory.add(Message.user("m" + i));
        }
        assertNotNull(memory.summary());

        memory.clear();

        assertNull(memory.summary());
        assertTrue(memory.isEmpty());
        assertTrue(memory.lastCompactionSucceeded());
    }

    @Test
    void tostring应包含关键状态() {
        SummarizingChatMemory memory =
                SummarizingChatMemory.of(new InMemoryChatMemory("s1"), new FakeChatModel(), 20, 5);

        String text = memory.toString();

        assertTrue(text.contains("s1"));
        assertTrue(text.contains("trigger=20"));
        assertTrue(text.contains("keep=5"));
    }

    @Test
    void 系统消息应参与保留且不占尾部配额() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 4, 1);

        memory.add(Message.system("系统说明"));
        for (int i = 0; i < 5; i++) {
            memory.add(Message.user("m" + i));
        }

        List<Message> context = memory.messages();
        long systemCount = context.stream().filter(m -> m.role() == Role.SYSTEM).count();
        assertEquals(1, systemCount, "SYSTEM 消息应被保留");
    }

    @Test
    void 渲染待摘要消息时应截断过长的工具结果() {
        FakeChatModel model = new FakeChatModel().thenText("S1");
        SummarizingChatMemory memory = SummarizingChatMemory.of(new InMemoryChatMemory("s1"), model, 3, 1);

        memory.add(Message.tool("someTool", "x".repeat(5000)));
        memory.add(Message.user("m1"));
        memory.add(Message.user("m2"));
        memory.add(Message.user("m3"));

        String prompt = model.requests().getFirst().messages().getFirst().content();
        assertTrue(prompt.contains("已截断"), "过长的工具结果应被截断后再送摘要模型");
        assertTrue(prompt.length() < 2000, "提示词长度应受控");
    }

    /** 所有调用都抛异常的模型。 */
    private static final class ThrowingChatModel implements ChatModel {

        @Override
        public ChatResponse call(ChatRequest request) {
            throw new IllegalStateException("模型不可用");
        }

        @Override
        public ChatResponse stream(ChatRequest request, StreamListener listener) {
            return call(request);
        }
    }
}
