## 1. 建立规范基线

- [ ] 1.1 从 `FishAgent` / `ReActAgent` / `ToolRegistry` / `ChatMemory` / `OllamaChatModel` / `Tracer` 六处提取对外可观察行为
- [ ] 1.2 交叉核对 35 个测试的断言，确保每条 Scenario 有对应的可验证行为
- [ ] 1.3 写入 12 份能力规范到 `openspec/changes/bootstrap-baseline-specs/specs/`
- [ ] 1.4 运行 `openspec validate bootstrap-baseline-specs` 直到无错误

## 2. 记录架构决策

- [ ] 2.1 在 `design.md` 中记录 7 项决策及其理由（规范切分粒度、现状即规范、Scenario 可验证性、门控独立成规范、删除空文件、修正 README、中英混排约定）
- [ ] 2.2 明确列出四项「记录现状而非理想行为」的取舍：非线程安全、提示词快照冻结、监听器异常不隔离、`wrap()` 无错误码
- [ ] 2.3 画出分层结构快照并列出五条关键不变量

## 3. 清理仓库

- [ ] 3.1 删除 `src/main/java/module-info.java`（零字节，空模块描述符无意义）
- [ ] 3.2 删除 `src/main/java/com/zheng/Main.java`（零字节脚手架残留）
- [ ] 3.3 确认删除后 `mvn -f pom.xml clean test` 仍通过（35 个测试全绿）

## 4. 修正文档漂移

- [ ] 4.1 修正 README「自定义模型」示例：`chat(...)` / `name()` → `call(...)` / `stream(...)` / `modelName()`
- [ ] 4.2 补齐 README「项目结构」清单：加入 `Tracer` / `Span` / `Spans` / `SimpleTracer`
- [ ] 4.3 在 README 的特性列表中补上链路追踪扩展点
- [ ] 4.4 在 README 增加一节说明 OpenSpec 规范位置与工作流入口

## 5. 建立 AI 协作入口

- [ ] 5.1 写入 `openspec/project.md`：定位、技术栈、模块划分、构建与测试命令、规范编写约定
- [ ] 5.2 写入 `AGENTS.md`：指向 `openspec/project.md` 与 `openspec/specs/`，说明「改行为前先看规范、改完行为要同步规范」

## 6. 固化基线

- [ ] 6.1 运行 `openspec validate --all` 确认提案与全部规范有效
- [ ] 6.2 归档变更：`openspec archive bootstrap-baseline-specs`，使 12 份规范进入 `openspec/specs/` 成为主规范
- [ ] 6.3 运行 `openspec list --specs` 确认 12 份规范已就位
- [ ] 6.4 运行 `mvn -f pom.xml clean test` 最终确认行为未受影响（仅删除空文件，预期 35 个测试全绿）
