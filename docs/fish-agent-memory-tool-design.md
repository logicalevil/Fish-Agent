# Fish-Agent 记忆系统与工具系统设计总结

> 日期：2026-11-14
> 范围：记忆分层、存储选型、长期记忆、工具扩展、崩溃恢复、MCP 接入

---

## 0. 结论速览

- **记忆分三层**：工作记忆（请求级、可重建）、会话记忆（会话级、唯一真相）、长期记忆（用户级、带版本）。
- **摘要不是一层**，是工具 + 缓存：`Summarizer` 压缩，`SummaryState` 存文本和游标，`SummaryStore` 负责读写。
- **工具分两类**：只读工具重跑无害；副作用工具必须落执行记录并幂等。
- **崩溃恢复不是重放，是续跑**：只补没做完的一步，已完成的一律不重做。
- **核心原则**：消息日志是唯一真相，其他都是派生视图；恢复靠断点，不靠记忆；副作用靠幂等，不靠运气。

---

## 1. 记忆系统

### 1.1 三层模型

| 层 | 作用域 | 内容 | 存储 | 能否重建 | 是否真相 |
| --- | --- | --- | --- | --- | --- |
| **工作记忆** | 单次请求 | 摘要 + 最近 K 条原文 | 进程内 | 能 | 否 |
| **会话记忆** | 单个会话 | 全量消息，append-only | MySQL | 不能 | **是** |
| **长期记忆** | 用户（跨会话） | 偏好 / 事实 / 记事 | MySQL | 可重放但成本高 | 是（带版本） |
| 摘要缓存 | 单个会话 | summary + 游标 | Redis | 能 | 否 |

### 1.2 命名修正

- “内存记忆”改叫**工作记忆**：介质（内存/Redis）不该进名字。
- “短时记忆”改叫**会话记忆**：它其实是会话档案，并不“短”。
- “长期记忆”是**用户级**的，不是会话级：会话记忆回答“这次聊了什么”，长期记忆回答“这个人是谁”。

### 1.3 存储选型

- **MySQL**：会话消息 + 长期记忆条目，唯一存档。
- **Redis**：摘要缓存、可选的热会话、分布式锁。
- **MD 文件**：只适合本地开发/单机，不做生产方案。
- **ES / 向量库**：长期记忆的检索增强，第三阶段再上；第一版用关键词 + 时间 + 置信度。

### 1.4 表清单

| 表 | 用途 | 是否必须 |
| --- | --- | --- |
| `fish_agent_message` | 会话消息日志，唯一真相 | ✅ |
| `fish_agent_session` | 会话目录（元数据、统计） | ✅ |
| `fish_agent_memory_item` | 长期记忆条目 | ✅ |
| `fish_agent_memory_cursor` | 长期记忆抽取游标 | ✅ |
| `fish_agent_memory_block` | 永久屏蔽规则 | ⭕ v2 |
| `fish_agent_run` | 运行状态 / 租约 / 断点 | ⭕ 需恢复时 |
| `tool_execution` | 副作用工具执行记录 | ⭕ 接副作用工具时 |

### 1.5 长期记忆

#### 条目模型

长期记忆必须**条目化**，不能存一整段 profile 文本。

核心字段：

- `memory_type`：大类（PREFERENCE / FACT / NOTE / GOAL），决定怎么用；
- `memory_key`：具体键（如 `coffee_preference`），决定去重和冲突；
- `content`、`confidence`、`sensitivity`、`tags`；
- `source_session_id` + `source_message_ids`（JSON 数组，一条记忆可来自多条消息）；
- `valid_from` / `valid_to` / `superseded_by` / `version`：版本化，不覆盖；
- `extractor_version`。

`memory_type` 和 `memory_key` 的区别：

- `type` 是大类，决定召回策略和注入位置；
- `key` 是具体条目，决定“是哪一条”，用于去重和版本替换。

#### 增量抽取

- 游标按 `(tenant_id, user_id, session_id)` 记 `last_message_id`；
- 每次只抽 `id > last_message_id` 的消息；
- 抽取和游标推进在**同一个事务**里；
- 按会话记游标，因为一个用户会同时有多个会话；
- `extractor_version` 变更时重置游标，重新抽取。

#### 冲突与版本

- 同 key 内容不同：旧条目 `valid_to = now`、`superseded_by = 新 id`，插入新条目；
- 低置信度（如 < 0.6）不直接覆盖，存候选或丢弃；
- 不直接 `UPDATE content`，否则丢失历史，无法审计和回滚。

#### 检索与注入

- 长期记忆**绝不裸进 prompt**；
- 常驻型（偏好、稳定事实）直接进系统提示词；
- 按需型（记事、大量事实）按关键词/标签召回 top-K；
- 需要一个 `ContextAssembler` 扩展点做 token 预算分配，否则三层记忆没有汇合点。

#### 注入前的过滤链

```
状态过滤 → 屏蔽过滤 → 敏感度过滤 → 置信度过滤
→ 相关性排序 → 去重 → 预算截断 → 注入
```

### 1.6 黑名单

- **v1 不建 blocklist 表**：单条“忘掉”用 `valid_to` 软删；敏感信息用 `sensitivity = SECRET` 控制不注入。
- **只在需要“永久屏蔽某个 key 或某类记忆，且未来抽取也别再写”时**，才加一张轻量规则表（按 KEY / TYPE 屏蔽）。
- 不做 `MEMORY_ID` 级别的屏蔽表，因为那和软删重复。

### 1.7 摘要的定位

摘要不是一个存储类，而是三件小东西：

- `Summarizer`：工具，把消息列表压成摘要；
- `SummaryState`：数据，摘要文本 + 游标 + 版本；
- `SummaryStore`：缓存，负责读写（内存 / Redis）。

摘要缓存丢了，从会话记忆重新压缩即可，不丢原文。

---

## 2. 工具系统

### 2.1 扩展方式

- 工具通过 `ToolSource` SPI 接入；
- 工具注册时声明两个关键属性：
  - `hasSideEffect`：是否改变外部状态；
  - `retryable`：崩溃后能否自动重跑。

### 2.2 两类工具，两套策略

| 类型 | 例子 | 恢复策略 |
| --- | --- | --- |
| 只读工具 | 查天气、读文件、搜索 | 重跑无害，不落执行记录 |
| 副作用工具 | 写文件、发邮件、扣款、建工单 | 必须落执行记录 + 幂等 |

### 2.3 通用执行记录表

不为每个工具建表，用一张 `tool_execution` 覆盖所有副作用工具。

核心字段：

- `run_id` + `tool_call_id`：唯一锁定一次调用；
- `resource_id`：作用对象（文件路径 / 邮件 ID / 订单号）；
- `status`：成功 / 失败 / 进行中；
- `result_hash` 或结果摘要；
- `completed_at`：判断新旧。

它同时解决三件事：幂等判断、资源归属溯源、崩溃后免重跑还原结果。

### 2.4 tool_call_id

- 由**模型生成**，框架不生成；
- 回传结果时必须原样使用，不能改名；
- 作用是让“调用”和“结果”一一配对，支撑并行调用、异步执行、崩溃恢复和审计；
- **唯一性范围是「一次 run」**，不是全局；
- 四层保证：
  1. 所有查询和唯一键都带 `run_id`；
  2. 解析响应时检查单次响应内 id 是否重复，重复即拒绝该响应；
  3. 数据库唯一键 `(run_id, tool_call_id)` 兜底；
  4. 执行时用 `run_id + tool_call_id` 做幂等键。
- 恢复时用**已落库的旧 id**，不要重新调模型生成新计划。

### 2.5 工具注入流程

```
配置声明工具来源
   ↓
SPI 发现 ToolSourceProvider
   ↓
provider.create(config) → ToolSource
   ↓
ToolSource.tools() → List<Tool>
   ↓
ToolRegistry 合并 + 重名检测
   ↓
ReActAgent 组装请求 → 模型
```

---

## 3. 崩溃恢复

### 3.1 核心认知

> 恢复不是“重放”，是“续跑”。只补没做完的那一步，已经完成的一律不重做。

### 3.2 三个角色

- `fish_agent_run`：谁没跑完、跑到第几步、谁来接管（租约）；
- `fish_agent_message`：已经发生了什么（唯一真相）；
- 状态机：读最后一条消息，判断下一步。

### 3.3 恢复四步

1. **发现**：找 `status = RUNNING` 且租约过期的 run；
2. **抢占**：条件更新租约，只有一个实例能赢，防止双跑；
3. **看尾巴**：
   - 最后是用户消息 → 调模型；
   - 最后是带工具调用的助手消息 → 只补缺失的工具；
   - 最后是工具结果 → 继续调模型；
   - 最后是无工具调用的助手消息 → 已结束，标记成功；
4. **继续**：迭代次数从 run 表接着数，不从零开始。

### 3.4 必需字段

- run 表：`iteration`、`max_iterations`、`owner`、`lease_until`、`resume_count`；
- message 表：`seq`、`tool_call_id`，并用 `(run_id, seq)`、`(run_id, tool_call_id)` 唯一键兜底。

### 3.5 能恢复 / 不能恢复

| 能 | 不能 |
| --- | --- |
| 未调用的模型 | 流式输出到一半、未落库的 token |
| 未执行的工具 | 已产生副作用但结果未写的工具 |
| 只差下一步的流程 | 内存里的中间推理 |
| 只差标记状态的 run | 非幂等工具（只能人工介入） |

**恢复是“至少一次”，不是“恰好一次”**；靠幂等键逼近“不重”。

---

## 4. 代码生成的写冲突

场景：A 写文件成功但结果没落库，实例挂了；用户让 B 重写；A 恢复时发现哈希不对。

**错误做法**：哈希不对就覆盖。

**正确判断顺序**：

1. 同会话有没有比 A 更晚的 run？有 → A 标记 `SUPERSEDED`，不恢复；
2. 归属记录里有没有 A 自己的成功记录？有 → 只补结果消息，不碰文件；
3. 磁盘哈希等于 A 的目标哈希？是 → 补归属记录和结果消息，不碰文件；
4. 最新归属记录是比 A 新的 run？是 → A 不覆盖，标记“已被取代”；
5. 以上都不成立 → 才真正写。

配套原则：

- **原子写**：临时文件 + rename，避免半截文件；
- **一次写一个文件**：恢复粒度细，崩溃窗口小；
- **版本号/乐观锁**：写入时校验版本，防止越权覆盖；
- **内容哈希只用来判断“我是不是写过了”，不用来判断“我该不该写”**；
- 文件不能只放实例临时盘，必须落持久存储。

---

## 5. 已否决 / 已收敛的方案

| 方案 | 结论 |
| --- | --- |
| 用 AOP 注解 + `Message` 继承做落库 | 否决：`Message` 是 final；一次调用产生多条消息；流式无返回值；引入 Spring 依赖 |
| 每个工具一张表 | 否决：用一张通用 `tool_execution` 表，按 `resource_id` 抽象 |
| 长期记忆全量注入 prompt | 否决：必须检索 + 预算截断 |
| 长期记忆直接覆盖旧值 | 否决：用 `valid_to` / `superseded_by` 版本化 |
| v1 就建 blocklist 表 | 否决：软删 + 敏感度已覆盖 90% 场景 |
| 自研定时线程做长期记忆更新 | 否决：SDK 不创建线程，交给调用方触发 |
| 追求 exactly-once | 否决：只能做到“至少一次 + 幂等” |

---

## 6. Spring 适配

- core 保持零 Spring 依赖；
- 单独提供 `fish-agent-spring-boot-starter`，用 `@ConditionalOnMissingBean` 装配 `ChatModel` / `ChatMemory` / `LongTermMemory` / `ToolSource`；
- SPI 接口不变，Spring 只是装配层；
- 不要在 SDK 内部用 `@Scheduled` 起调度，调度权留给应用；
- 插件 ClassLoader 隔离、沙箱执行属于工具层的独立能力，不塞进记忆模块。

---

## 7. 落地顺序

1. `SummaryStore` + `Summarizer` 拆分，默认内存实现，行为不变；
2. `ContextAssembler` 扩展点，默认保持现有行为；
3. MySQL 会话记忆 + `ArchivingChatMemory`；
4. Redis 摘要缓存；
5. 长期记忆：表 + 手动 `remember/recall`，先不做自动抽取；
6. 自动抽取 + 游标；
7. 副作用工具执行记录表 + 幂等键；
8. run 表 + `resume`；
9. 检索增强（关键词 → 向量）。

---

## 附录 A：MCP 工具注入设计

### A.1 先拆两层，避免混淆

- **SPI 负责“发现能力”**：classpath 上有哪些工具来源、哪些传输实现。
- **配置负责“声明实例”**：要连哪个 MCP Server、用什么命令或 URL、带什么凭证。

MCP 本身不是 ServiceLoader 插件，它是一个跨进程协议。SPI 负责的是“MCP 客户端实现/传输方式”，Server 列表来自配置。

### A.2 SPI 接口

- `ToolSource`：`id()`、`tools()`、`refresh()`、`close()`；
- `ToolSourceProvider`：`type()`、`create(config)`，通过 `META-INF/services` 注册；
- `McpTransportProvider`：负责 stdio / Streamable HTTP 等传输方式。

### A.3 注入流程

1. 构建时读取配置中的工具来源；
2. 用配置里的 `type` 找到对应的 `ToolSourceProvider`；
3. `provider.create(config)` 得到 `ToolSource`；
4. `ToolSource.tools()` 返回本地 `Tool` 列表；
5. `ToolRegistry` 合并内置工具和所有来源，做重名检测；
6. `ReActAgent` 用注册表组装请求。

MCP 的 `ToolSource` 内部做四件事：连接、初始化、`tools/list`、把每个 MCP 工具包装成本地 `Tool`。调用时走 `tools/call`，把返回内容转成工具结果。

### A.4 命名与 Schema

- MCP 工具名可能跨 Server 冲突，统一加前缀：`{serverId}__{toolName}`；
- 模型看到前缀名，调用时路由回对应 Server，并还原原始工具名；
- MCP 的 `inputSchema` 本身就是 JSON Schema，可直接作为本地工具的参数定义；
- 调用前用同一份 Schema 校验参数；
- 根据 MCP 的 `readOnlyHint` / `destructiveHint` 推断 `hasSideEffect` / `retryable`；没有注解时默认按“可能有副作用”处理。

### A.5 生命周期

- 启动时或首次使用时连接，连接失败不能拖垮整个 Agent；
- MCP 的工具列表可能变化（`tools/list_changed`），在两次 run 之间刷新，不要在单次 run 中途换列表；
- 关闭时统一 `close()`，避免 stdio 子进程泄漏；
- 多实例场景下，MCP 连接是实例级的，不要共享有状态的会话。

### A.6 外部插件工具

两条路：

1. **MCP Server**：适合跨语言、跨进程、第三方工具；
2. **Java 插件 jar**：实现 `ToolSourceProvider`，在 `META-INF/services` 注册，适合复用现有 Java 代码和本地资源。

两者最终都产出 `Tool`，注册表不关心来源。区别只在“进程内代码”还是“进程外协议”。

### A.7 配置示例

```yaml
tools:
  sources:
    - type: mcp-stdio
      id: github
      command: npx
      args: ["-y", "@modelcontextprotocol/server-github"]
      env:
        GITHUB_TOKEN: "${GITHUB_TOKEN}"
    - type: mcp-http
      id: search
      url: https://example.com/mcp
      headers:
        Authorization: "Bearer ${SEARCH_TOKEN}"
```

### A.8 安全与取舍

- 工具太多会爆上下文：按 Server 或工具做 allowlist，必要时按需检索；
- 不要让任意 MCP Server 自动注册，必须显式配置和授权；
- 凭证走环境变量或密钥管理，不进入 prompt；
- MCP 的 prompts / resources 可以后续做成 `PromptSource` / `ResourceSource`，第一版先只做 tools。
