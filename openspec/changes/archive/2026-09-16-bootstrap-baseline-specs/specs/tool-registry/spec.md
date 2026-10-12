## ADDED Requirements

### Requirement: 注册表按名称存储工具并保持注册顺序

`ToolRegistry` SHALL 用一个按键名索引、保持插入顺序的映射存储工具；`all()` 与 `specs()` SHALL 按注册顺序返回。

#### Scenario: 顺序被保留
- **WHEN** 按 `add`、`greet`、`explode` 的顺序注册三个工具
- **THEN** `all()` 按该顺序返回

#### Scenario: 注册支持链式调用
- **WHEN** 调用 `new ToolRegistry().register(toolA).register(toolB)`
- **THEN** 返回同一个注册表实例以便继续链式调用

### Requirement: 重复的工具名被拒绝

注册名称已存在的工具 SHALL 抛出 `FishAgentException` 并指明重复的名称。原有的工具 SHALL 保持在注册表中。

#### Scenario: 用同一名称注册第二个工具
- **WHEN** 名为 `same` 的工具被注册两次
- **THEN** 抛出 `RuntimeException`
- **AND** 原先注册的工具仍可由 `find("same")` 取得

#### Scenario: 注册 null 工具为空操作
- **WHEN** 调用 `register((Tool) null)`
- **THEN** 不抛异常且注册表保持不变

### Requirement: 注册表支持查找与规模查询

`ToolRegistry` SHALL 提供返回 `Optional<Tool>` 的 `find(String)`，以及 `contains(String)`、`size()`、`isEmpty()`、`all()`。

#### Scenario: 查找已注册的工具
- **WHEN** 在包含 `add` 的注册表上调用 `find("add")`
- **THEN** 返回的 Optional 包含描述为 `两数相加` 的工具

#### Scenario: 查找未注册的工具
- **WHEN** 调用 `find("missing")`
- **THEN** 返回空 Optional

### Requirement: 注解 Bean 的方法被扫描并注册

`register(Object bean)` SHALL 扫描 Bean 的类继承链（直到但不含 `Object`）中被 `@AgentTool` 标注的方法，跳过合成方法与桥接方法，并为每个被标注的方法注册一个工具。若 Bean 自身实现了 `Tool`，SHALL 直接注册它而不做扫描。

#### Scenario: 含注解方法的 Bean
- **WHEN** 注册一个声明了三个 `@AgentTool` 方法的 Bean
- **THEN** `size()` 为 3，且每个工具名与其方法名一致

#### Scenario: 工具描述的解析顺序
- **WHEN** 被标注的方法只声明了 `@AgentTool("两数相加")`
- **THEN** 注册工具的 `description()` 为 `两数相加`

#### Scenario: description 属性优先于 value 属性
- **WHEN** 被标注的方法同时声明了 `@AgentTool.value()` 与 `@AgentTool.description()`
- **THEN** 注册工具的描述取自 `description()`

#### Scenario: 显式工具名优先于方法名
- **WHEN** 名为 `add` 的方法上声明了 `@AgentTool(name = "sum")`
- **THEN** 注册后的工具可通过 `sum` 访问

#### Scenario: 实现 Tool 的 Bean 被直接注册
- **WHEN** 把一个实现了 `Tool` 的 Bean 传给 `register(Object)`
- **THEN** 注册表中包含该工具本身

#### Scenario: 无注解方法的 Bean 被拒绝
- **WHEN** 把未声明任何 `@AgentTool` 方法的 Bean 传给 `register(Object)`
- **THEN** 抛出 `FishAgentException`，消息包含 `未找到标注 @AgentTool 的方法`

#### Scenario: 注册 null Bean 为空操作
- **WHEN** 调用 `register((Object) null)`
- **THEN** 不抛异常且注册表保持不变

### Requirement: 注解工具通过反射执行并完成类型转换

由注解方法生成的工具 SHALL 按 `@ToolParam` 名称解析每个参数，把传入值转换为声明的 Java 类型，在 Bean 实例上调用该方法（方法为静态时直接静态调用），并把返回值序列化为字符串。

#### Scenario: 基本类型参数转换
- **WHEN** 以参数 `{"a": "1", "b": 2}` 调用 `add`，且 `a`、`b` 声明为 `int`
- **THEN** 工具返回 `"3"`

#### Scenario: 字符串参数直接透传
- **WHEN** 以 `{"name": "小明"}` 调用 `greet`
- **THEN** 工具返回 `你好，小明`

#### Scenario: Map 返回值序列化为 JSON
- **WHEN** 被标注的方法返回一个 `Map`
- **THEN** 工具结果为该 Map 的 JSON 序列化

#### Scenario: record 与自定义对象返回值序列化为 JSON
- **WHEN** 被标注的方法返回 record 或非 `java.*` 对象
- **THEN** 工具结果为该对象的 JSON 序列化

#### Scenario: 标量返回值转为字符串
- **WHEN** 被标注的方法返回 `Number`、`Boolean` 或 `Character`
- **THEN** 工具结果为该值的 `String.valueOf`

#### Scenario: null 返回值变为空字符串
- **WHEN** 被标注的方法返回 `null`
- **THEN** 工具结果为空字符串

#### Scenario: 调用失败被包装
- **WHEN** 被标注的方法抛出异常
- **THEN** 该失败以 `FishAgentException` 形式暴露，指明工具名并保留原始 cause

### Requirement: 工具暴露规格快照

每个 `Tool` SHALL 暴露 `name()`、`description()`、`parameters()`，以及返回 `ToolSpec` 的默认方法 `spec()`。`ToolSpec` SHALL 拒绝 null 名称，并 SHALL 把 null 描述规范化为空串、null 参数规范化为不含属性的对象 Schema。

#### Scenario: 规格由工具推导而来
- **WHEN** 对某个已注册工具调用 `spec()`
- **THEN** 所得 `ToolSpec` 的名称、描述、参数与该工具一致

#### Scenario: null 描述被规范化
- **WHEN** 以 null 描述构造 `ToolSpec`
- **THEN** `description()` 为空字符串

### Requirement: Lambda 工具可用内联 Schema 构建

`FunctionTool.builder()` SHALL 支持不经注解声明工具，通过 `name`、`description`、`parameter(name, type, description, required)`、`parameters(Map)`、`function(Function)` 完成。

#### Scenario: 声明的参数生成 JSON Schema
- **WHEN** 某个 `FunctionTool` 声明 `keyword` 为必填 string、`limit` 为可选 integer
- **THEN** `parameters().get("required")` 等于 `["keyword"]`

#### Scenario: 调用时收到参数映射
- **WHEN** 以 `{"keyword": "鱼"}` 执行该工具
- **THEN** 传入的函数收到该映射，其返回值即工具结果

#### Scenario: 名称必填
- **WHEN** 构建 `FunctionTool` 时未提供名称
- **THEN** 抛出 `NullPointerException`，消息包含 `工具名不能为空`

#### Scenario: 执行函数必填
- **WHEN** 构建 `FunctionTool` 时提供了名称但未提供函数
- **THEN** 抛出 `NullPointerException`，消息包含 `工具执行函数不能为空`

#### Scenario: 函数返回 null 变为空字符串
- **WHEN** 传入的函数返回 `null`
- **THEN** 工具结果为空字符串

#### Scenario: 完整 Schema 可替换声明的参数
- **WHEN** 以一份完整的对象 Schema 调用 `parameters(Map)`
- **THEN** 该 Schema 被原样发布，且其中的 `properties` 与 `required` 保持同步
