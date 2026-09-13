package com.zheng.fishagent.example;

import com.zheng.fishagent.FishAgent;
import com.zheng.fishagent.agent.Agent;
import com.zheng.fishagent.agent.AgentListener;
import com.zheng.fishagent.agent.ToolCallMode;
import com.zheng.fishagent.agent.AgentResult;
import com.zheng.fishagent.llm.ollama.OllamaChatModel;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.FunctionTool;
import com.zheng.fishagent.tool.ToolRegistry;
import com.zheng.fishagent.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 命令行示例：演示如何把 Fish-Agent 组装成一个可交互的本地 Agent。
 *
 * <pre>
 * 运行前先准备本地模型：
 *   ollama pull qwen2.5:7b
 *   ollama serve
 *
 * 运行：
 *   mvn -q compile exec:java -Dexec.mainClass=com.zheng.fishagent.example.ConsoleChat
 * 或直接运行本类 main 方法，支持参数：
 *   --model=qwen2.5:7b --base-url=http://localhost:11434 --mode=native|react
 * </pre>
 */
public final class ConsoleChat {

    private static final String DEFAULT_MODEL = "qwen2.5:7b";

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parseArgs(args);
        String modelName = options.getOrDefault("model",
                System.getenv().getOrDefault("OLLAMA_MODEL", DEFAULT_MODEL));
        String baseUrl = options.getOrDefault("base-url",
                System.getenv().getOrDefault("OLLAMA_BASE_URL", OllamaChatModel.DEFAULT_BASE_URL));
        ToolCallMode mode = "react".equalsIgnoreCase(options.get("mode")) ? ToolCallMode.REACT_TEXT : ToolCallMode.NATIVE_TOOLS;

        ToolRegistry tools = new ToolRegistry()
                // 1) 注解方式：扫描 @AgentTool 方法
                .register(new DemoTools())
                // 2) Lambda 方式：适合简单、需要闭包的场景
                .register(FunctionTool.builder()
                        .name("count_text")
                        .description("统计一段文本的字符数（按 Unicode 码点计）")
                        .parameter("text", "string", "要统计的文本", true)
                        .function(a -> {
                            String text = String.valueOf(a.getOrDefault("text", ""));
                            return "字符数：" + text.codePointCount(0, text.length());
                        })
                        .build());

        // 用统一门面组装：模型 + 工具 + 滑动窗口记忆 + 行为准则
        Agent agent = FishAgent.builder()
                .ollama(baseUrl, modelName)
                .temperature(0.7)
                .tools(tools)
                .session("console", 20)
                .mode(mode)
                .streaming(true)
                .maxIterations(8)
                .instructions("""
                        你是一个运行在用户本机的助手，可以调用工具查询时间、计算表达式、查询天气。
                        涉及时间、计算、天气的问题必须调用工具，不要凭猜测回答。
                        最终回答请使用简体中文，简洁直接。""")
                .build();

        printBanner(modelName, baseUrl, mode, tools);

        AgentListener listener = new ConsoleListener();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, resolveConsoleCharset()))) {
            while (true) {
                System.out.print("\n你 > ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String input = line.trim();
                if (input.isEmpty()) {
                    continue;
                }
                switch (input.toLowerCase()) {
                    case "/exit", "/quit", "exit", "quit" -> {
                        System.out.println("再见。");
                        return;
                    }
                    case "/reset" -> {
                        agent.reset();
                        System.out.println("（已清空当前会话记忆）");
                        continue;
                    }
                    case "/tools" -> {
                        tools.all().forEach(tool ->
                                System.out.println("  - " + tool.name() + "：" + tool.description()));
                        continue;
                    }
                    case "/history" -> {
                        agent.memory().messages().forEach(m ->
                                System.out.println("  [" + m.role().value() + "] " + preview(m.content())));
                        continue;
                    }
                    case "/help" -> {
                        System.out.println("  /tools   查看已注册工具");
                        System.out.println("  /history 查看当前会话消息");
                        System.out.println("  /reset   清空会话记忆");
                        System.out.println("  /exit    退出");
                        continue;
                    }
                    default -> {
                        // 继续走 Agent
                    }
                }

                System.out.print("\nAgent > ");
                try {
                    agent.run(input, listener);
                } catch (Exception e) {
                    System.out.println("\n[执行失败] " + e.getMessage());
                }
            }
        }
        System.out.println("\n再见。");
    }

    private static void printBanner(String modelName, String baseUrl, ToolCallMode mode, ToolRegistry tools) {
        System.out.println("======================================================");
        System.out.println(" Fish-Agent 控制台示例");
        System.out.println(" 模型   : " + modelName);
        System.out.println(" 地址   : " + baseUrl);
        System.out.println(" 模式   : " + (mode == ToolCallMode.NATIVE_TOOLS ? "原生 Tool Calling" : "文本 ReAct"));
        System.out.println(" 工具   : " + tools.all().stream().map(t -> t.name()).toList());
        System.out.println(" 输入 /help 查看命令，/exit 退出");
        System.out.println("======================================================");
    }

    /**
     * 探测输入流的真实编码。
     * <p>
     * Windows 控制台默认是 GBK，如果固定用 UTF-8 读取会把中文变成乱码。
     * 优先级：真实终端编码 → stdin.encoding → native.encoding → UTF-8。
     */
    private static java.nio.charset.Charset resolveConsoleCharset() {
        java.io.Console console = System.console();
        if (console != null) {
            return console.charset();
        }
        for (String key : new String[]{"stdin.encoding", "native.encoding"}) {
            String value = System.getProperty(key);
            if (value != null && !value.isBlank()) {
                try {
                    return java.nio.charset.Charset.forName(value);
                } catch (RuntimeException ignored) {
                    // 继续尝试下一个候选编码
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "..." : oneLine;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int index = arg.indexOf('=');
                options.put(arg.substring(2, index), arg.substring(index + 1));
            }
        }
        return options;
    }

    /**
     * 把 Agent 的执行过程可视化到控制台。
     */
    private static final class ConsoleListener implements AgentListener {

        @Override
        public void onToolCall(ToolCall call) {
            System.out.print("\n[工具调用] " + call.name() + " " + Json.write(call.arguments()) + "\n");
            System.out.flush();
        }

        @Override
        public void onToolResult(ToolCall call, String result) {
            System.out.print("[工具结果] " + preview(result) + "\n");
            System.out.flush();
        }

        @Override
        public void onThought(String thought) {
            System.out.print("[思考] " + preview(thought) + "\n");
            System.out.flush();
        }

        @Override
        public void onToken(String token) {
            System.out.print(token);
            System.out.flush();
        }

        @Override
        public void onFinish(AgentResult result) {
            System.out.print("\n[" + result.iterations() + " 轮 / "
                    + result.usage().totalTokens() + " tokens / " + result.stopReason() + "]\n");
            System.out.flush();
        }

        @Override
        public void onError(Throwable error) {
            System.out.print("\n[错误] " + error.getMessage() + "\n");
            System.out.flush();
        }
    }
}
