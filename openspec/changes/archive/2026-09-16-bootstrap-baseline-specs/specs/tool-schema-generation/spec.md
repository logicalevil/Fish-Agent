## ADDED Requirements

### Requirement: 方法参数映射为 JSON Schema 类型

`ToolSchemaGenerator.forType(Type)` SHALL 把 Java 类型映射为 JSON Schema 类型：文本类映射为 `string`，布尔类映射为 `boolean`，整数类映射为 `integer`，浮点类映射为 `number`，枚举映射为带 `enum` 取值列表的 `string`，数组与集合映射为带 `items` 的 `array`，映射映射为 `object`，record 映射为带生成属性与必填列表的 `object`，其它类型映射为空 `object`。

#### Scenario: 文本类型映射为 string
- **WHEN** 类型为 `String`、`CharSequence`、`Character` 或 `char`
- **THEN** Schema 类型为 `string`

#### Scenario: 布尔类型映射为 boolean
- **WHEN** 类型为 `boolean` 或 `Boolean`
- **THEN** Schema 类型为 `boolean`

#### Scenario: 整数类型映射为 integer
- **WHEN** 类型为 `byte`、`short`、`int`、`long`、`BigInteger` 或对应的包装类
- **THEN** Schema 类型为 `integer`

#### Scenario: 浮点类型映射为 number
- **WHEN** 类型为 `float`、`double`、`BigDecimal` 或对应的包装类
- **THEN** Schema 类型为 `number`

#### Scenario: 枚举映射为带取值列表的 string
- **WHEN** 类型为含常量 `NATIVE_TOOLS` 与 `REACT_TEXT` 的枚举
- **THEN** Schema 类型为 `string`
- **AND** Schema 的 `enum` 列表为 `["NATIVE_TOOLS", "REACT_TEXT"]`

#### Scenario: 数组映射为带元素项的 array
- **WHEN** 类型为 `int[]`
- **THEN** Schema 类型为 `array`，且 `items.type` 为 `integer`

#### Scenario: 集合映射为带元素项的 array
- **WHEN** 类型为 `List<String>`
- **THEN** Schema 类型为 `array`，且 `items.type` 为 `string`

#### Scenario: 原始集合类型使用默认元素类型
- **WHEN** 无法从集合参数确定元素类型
- **THEN** `items.type` 为 `string`

#### Scenario: 映射类型映射为无属性的 object
- **WHEN** 类型为 `Map<String, Object>`
- **THEN** Schema 类型为 `object`

#### Scenario: record 映射为带必填项的对象
- **WHEN** 类型为含 `city` 与 `unit` 组件的 record
- **THEN** Schema 类型为 `object`
- **AND** `properties` 包含 `city` 与 `unit`
- **AND** `required` 包含这两个组件名

#### Scenario: 未知类型退化为空对象
- **WHEN** 类型既非 record、集合、映射也非标量
- **THEN** Schema 类型为 `object`，且 `properties` 为空

#### Scenario: 无法解析的类型与 Object 退化为 string
- **WHEN** 类型为 `Object` 或无法解析出原始类
- **THEN** Schema 类型为 `string`

### Requirement: 方法参数 Schema 组合元数据与必填标记

`ToolSchemaGenerator.forMethod(Method)` SHALL 生成对象 Schema，其 `properties` 以解析后的参数名为键，其 `required` 列表包含「标注了 `@ToolParam(required = true)`」或「完全未标注 `@ToolParam`」的参数。当该列表为空时，`required` 键 SHALL 被整体省略。

#### Scenario: 标注为必填的参数
- **WHEN** 方法声明 `@ToolParam("a 的值") int a`
- **THEN** 属性 `a` 的类型为 `integer`，描述为 `a 的值`
- **AND** `a` 出现在 `required` 中

#### Scenario: 显式声明为可选的参数
- **WHEN** 方法声明 `@ToolParam(value = "对方名字", required = false) String name`
- **THEN** `name` 不出现在 `required` 中
- **AND** 当没有其它参数需要必填时，Schema 不含 `required` 键

#### Scenario: 未标注参数默认为必填
- **WHEN** 方法参数未携带 `@ToolParam`
- **THEN** 该参数出现在 `required` 中

#### Scenario: 描述回退到 value 属性
- **WHEN** `@ToolParam` 只声明了 `value`
- **THEN** 属性描述取自该 value

#### Scenario: description 属性优先
- **WHEN** `@ToolParam` 同时声明了 `value` 与 `description`
- **THEN** 属性描述取自 `description`

#### Scenario: 空描述被省略
- **WHEN** 参数解析出的描述为空白
- **THEN** 该属性的 Schema 不含 `description` 键

### Requirement: 参数名按明确的优先级解析

参数名 SHALL 按以下顺序解析：`@ToolParam(name)` 的值（当其非空白）、反射得到的参数名（当其存在）、`arg<索引>`。

#### Scenario: 显式注解名优先
- **WHEN** 参数声明 `@ToolParam(name = "city")`
- **THEN** 属性键为 `city`，与源码参数名无关

#### Scenario: 可用时使用编译期保留的参数名
- **WHEN** 参数没有显式名称且类以 `-parameters` 编译
- **THEN** 属性键为源码中的参数名

#### Scenario: 回退为按位置命名
- **WHEN** 参数既无显式名称也无保留的参数名
- **THEN** 属性键为 `arg` 加该参数的零基索引

### Requirement: 传入的参数值被转换为声明的 Java 类型

`ToolSchemaGenerator.convert(Object, Class)` SHALL 把模型给出的值强制转换为目标类型，接受已经类型正确的值、解析文本形式，并拒绝无法转换的值。

#### Scenario: 类型正确的值直接透传
- **WHEN** 值已是目标类型的实例且不是 `String`
- **THEN** 原样返回值

#### Scenario: 数字字符串转换为数字
- **WHEN** 值为字符串 `"3"` 且目标为 `int`
- **THEN** 结果为整数 `3`

#### Scenario: 小数字符串转换为浮点
- **WHEN** 值为字符串 `"1.5"` 且目标为 `double`
- **THEN** 结果为 `1.5`

#### Scenario: 文本到布尔值的强制转换
- **WHEN** 值为字符串 `"true"` 且目标为 `boolean`
- **THEN** 结果为 `true`

#### Scenario: 枚举转换忽略大小写
- **WHEN** 值为 `"native_tools"` 且目标为 `ToolCallMode`
- **THEN** 结果为 `ToolCallMode.NATIVE_TOOLS`

#### Scenario: 非法枚举值被拒绝
- **WHEN** 值与目标枚举的任何常量都不匹配
- **THEN** 抛出 `FishAgentException`，消息指明该值与枚举类型

#### Scenario: 非数字文本用于数字目标时被拒绝
- **WHEN** 值为 `"abc"` 且目标为 `int`
- **THEN** 抛出 `FishAgentException`

#### Scenario: 空文本用于数字目标时得零
- **WHEN** 值为空串或空白串且目标为数字类型
- **THEN** 结果为零

#### Scenario: null 值得到基本类型默认值
- **WHEN** 值为 null 且目标为基本类型
- **THEN** 结果为该基本类型的默认值（`false`、`'\\0'`、`0`、`0L`、`0d` 或 `0f`）
- **AND** 目标为非基本类型时结果为 null

#### Scenario: 字符转换取首个字符
- **WHEN** 值为多字符串且目标为 `char`
- **THEN** 结果为第一个字符
- **AND** 空字符串得到 `'\\0'`

#### Scenario: 结构化值反序列化为目标类型
- **WHEN** 值为 `Map` 且目标为 record 或 Bean 类型
- **THEN** 该映射被绑定到目标类型的新实例上

#### Scenario: 数组与集合值直接透传
- **WHEN** 目标为数组或集合类型
- **THEN** 传入值被原样返回，不做元素级转换

#### Scenario: 大数类型使用精确解析
- **WHEN** 目标为 `BigDecimal` 或 `BigInteger`
- **THEN** 值由其文本形式解析，不引入浮点舍入
