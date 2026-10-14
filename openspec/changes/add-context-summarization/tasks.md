## 1. 摘要压缩实现

- [ ] 1.1 新增 `memory/SummarizingChatMemory.java`：装饰器，持有 `delegate` + `ChatModel`
- [ ] 1.2 静态工厂 `of(delegate, model, triggerMessages, keepMessages)`
- [ ] 1.3 构造时校验：`keepMessages < triggerMessages`、两者均 ≥ 1，否则抛 `FishAgentException`
- [ ] 1.4 常量 `SUMMARY_PREFIX = "Here is a summary of the conversation so far:"`
- [ ] 1.5 `add()`：写入 delegate 后计算「未摘要条数」，超过阈值则同步触发压缩
- [ ] 1.6 `messages()`：存在摘要时返回 `[摘要] + 最近 keepMessages 条`；否则返回原文
- [ ] 1.7 摘要消息用 `Role.USER`，内容带 `SUMMARY_PREFIX`
- [ ] 1.8 `fullHistory()`：返回 delegate 的完整消息
- [ ] 1.9 `clear()`：清空 delegate 并重置摘要状态
- [ ] 1.10 暴露 `summary()` 与 `lastCompactionSucceeded()` 供诊断
- [ ] 1.11 `toString()` 包含 id、条数、是否已摘要、上次是否成功

## 2. 摘要生成逻辑

- [ ] 2.1 构造摘要提示词：把待压缩消息渲染为文本（USER → `Human:`、ASSISTANT → `AI:`、TOOL → `Tool:`）
- [ ] 2.2 工具结果在渲染时截断（如超过 500 字符），避免摘要输入过长
- [ ] 2.3 链式累积：若已有摘要，把上一条摘要消息并入本次输入
- [ ] 2.4 提示词要求输出简短，并覆盖「意图 / 决策 / 产物 / 后续步骤」
- [ ] 2.5 调用 `model.call(...)` 生成摘要，取返回文本并 `strip()`
- [ ] 2.6 推进「已摘要到第几条」的位置，避免重复压缩
- [ ] 2.7 失败回退：异常或空白输出时，标记失败、推进位置、退化为窗口裁剪
- [ ] 2.8 确认压缩过程不创建线程

## 3. 测试

- [ ] 3.1 `SummarizingChatMemoryTest`：用 `FakeChatModel` 脚本化摘要返回，保证离线可跑
- [ ] 3.2 未超阈值时不调用模型
- [ ] 3.3 超过阈值触发一次压缩，之后 `messages()` 返回 `[摘要] + keepMessages 条`
- [ ] 3.4 压缩后同一批不重复压缩
- [ ] 3.5 摘要消息 role 为 USER 且带固定前缀
- [ ] 3.6 摘要在最前，尾部消息逐字保持原样
- [ ] 3.7 链式累积：二次压缩的提示词中包含上次摘要内容
- [ ] 3.8 `fullHistory()` 始终返回全部原文，条数不因压缩减少
- [ ] 3.9 摘要模型抛异常时不中断，退化为窗口且状态可观测
- [ ] 3.10 摘要返回空白时的回退行为
- [ ] 3.11 非法参数（`keepMessages >= triggerMessages`、小于 1）抛 `FishAgentException`
- [ ] 3.12 包装 `FileChatMemory` 时消息正常落盘
- [ ] 3.13 与 `MessageWindowChatMemory` 双向组合均可构造与工作
- [ ] 3.14 `id()` 透传被包装记忆的标识
- [ ] 3.15 确认全套测试离线可跑、不依赖网络

## 4. 文档

- [ ] 4.1 README 新增「上下文摘要压缩」章节：触发条件、产出形态、链式累积、失败回退
- [ ] 4.2 明确说明同步压缩会带来一次额外模型调用（跨阈值时）
- [ ] 4.3 给出推荐参数与组合方式（外层摘要 + 内层窗口）
- [ ] 4.4 说明摘要是有损操作，原文可通过 `fullHistory()` 读取
- [ ] 4.5 `ChatMemory` 的 Javadoc 补充「实现可以做压缩」的说明（不改签名）

## 5. 归档

- [ ] 5.1 `openspec validate add-context-summarization`
- [ ] 5.2 确认所有 Scenario 有对应测试
- [ ] 5.3 `openspec archive add-context-summarization`
