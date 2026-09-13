package com.zheng.fishagent.agent;

import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.llm.ChatRequest;
import com.zheng.fishagent.llm.ChatResponse;
import com.zheng.fishagent.llm.StreamListener;
import com.zheng.fishagent.llm.TokenUsage;
import com.zheng.fishagent.message.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 按脚本依次返回响应的模型，用于离线测试 Agent 循环。
 */
public final class FakeChatModel implements ChatModel {

    private final Deque<ChatResponse> script = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private final int chunkSize;

    public FakeChatModel() {
        this(2);
    }

    public FakeChatModel(int chunkSize) {
        this.chunkSize = Math.max(1, chunkSize);
    }

    public FakeChatModel then(ChatResponse response) {
        script.addLast(response);
        return this;
    }

    public FakeChatModel thenText(String content) {
        return then(new ChatResponse(Message.assistant(content), new TokenUsage(1, 1), "stop"));
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public ChatRequest lastRequest() {
        return requests.getLast();
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        requests.add(request);
        ChatResponse next = script.pollFirst();
        if (next == null) {
            throw new IllegalStateException("脚本已耗尽，模型被调用了 " + requests.size() + " 次");
        }
        return next;
    }

    @Override
    public ChatResponse stream(ChatRequest request, StreamListener listener) {
        ChatResponse response = call(request);
        String content = response.message().content();
        for (int i = 0; i < content.length(); i += chunkSize) {
            listener.onToken(content.substring(i, Math.min(content.length(), i + chunkSize)));
        }
        return response;
    }

    @Override
    public String modelName() {
        return "fake-model";
    }
}
