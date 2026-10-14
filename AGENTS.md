# AGENTS.md

给 AI 协作者的入口说明。**先读 `openspec/project.md`**，那里有项目定位、技术栈、模块划分与已知取舍。

## 这个仓库用 OpenSpec 管理行为契约

行为规范不在代码注释里，而在 `openspec/` 下：

```
openspec/
├── project.md      项目上下文（先读这个）
├── specs/          已实现能力的正式规范（12 份基线）
└── changes/        进行中的变更提案
    └── archive/    已归档的变更
```

规范用 `### Requirement:` + `#### Scenario:` 描述「系统应该做什么」。Scenario 是可验证的行为，不是愿望。

## 动手之前

1. **读规范**：`openspec list --specs` 看有哪些能力，再 `openspec show <capability> --type spec` 读相关的。
2. **判断改动性质**：
   - 只改实现、行为不变 → 直接改代码，规范不用动。
   - 改变对外可观察行为 → 必须走变更提案，先改规范再改代码。
3. **查已知取舍**：`openspec/project.md` 末尾的表格列了四项「看起来像 bug、其实是设计」的行为。不要顺手「修」它们。

## 变更流程

```powershell
openspec new change <kebab-case-name>       # 1. 新建提案
# 2. 依次写 proposal.md → specs/ → design.md → tasks.md
openspec status --change <name>             # 3. 看工件完成度
openspec validate <name>                    # 4. 校验
# 5. 按 tasks.md 实施
openspec archive <name>                     # 6. 归档，规范并入 openspec/specs/
```

写规范的硬性要求：

- Scenario 必须用**恰好四个** `#`（`#### Scenario:`）。三个 `#` 或用列表项会静默失败。
- 每条 Requirement 至少一个 Scenario。
- 正文用中文，`SHALL` / `MUST` / `WHEN` / `THEN` 保留英文。
- 修改既有 Requirement 时，`## MODIFIED Requirements` 下必须贴**完整**的需求块（从 `### Requirement:` 到所有 Scenario），只贴改动部分会在归档时丢内容。

## 代码约定

- **依赖**：运行时只有 Jackson。新增依赖需要充分理由。
- **测试**：`mvn -f pom.xml test`，35 个测试必须全绿。测试**不得依赖真实模型或网络**——用 `FakeChatModel`（按脚本返回响应）或 `com.sun.net.httpserver`（HTTP 桩）。
- **异常**：用 `FishAgentException`，不要抛裸 `RuntimeException`。
- **工具失败**：`ToolRegistry.execute` 必须兜住所有异常并返回以 `错误：` 开头的文本，绝不向上抛。
- **Builder 风格**：链式、返回 `this`、必填项在 `build()` 里校验并快速失败。
- **不改动的结构**：见 `openspec/project.md` 的「关键不变量」——终止条件、模式差异收敛点、提示词不进记忆等。

## 不要做的事

- 不要引入 Spring / OpenTelemetry / OkHttp 等重量级依赖。
- 不要让 `ReActAgent` 变成线程安全（这是刻意的取舍，见 project.md）。
- 不要把规范写成「理想行为」——记录现状，对取舍写明理由。
- 不要在未读规范的情况下改动 `ReActAgent` 的循环结构。
