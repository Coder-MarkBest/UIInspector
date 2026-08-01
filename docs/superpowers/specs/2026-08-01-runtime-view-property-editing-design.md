# 运行时 View 属性编辑设计

## 1. 目标

在现有 Android Studio UI Inspector 中增加运行时属性编辑。业务工程仍然不接入 SDK、不修改源码、APK、Gradle 或 Manifest；用户在浏览器属性面板中修改目标应用当前进程里的真实 View，修改立即反映在设备界面中。

本功能采用“方案 A”：每次提交属性修改时，插件向用户明确选中的 debuggable 进程临时挂载一次 Agent，完成修改和重新采集后清理部署文件。目标进程中不保留常驻命令服务。

## 2. 能力边界

- 只允许编辑 `CaptureSource.JVMTI` 的 Real View 和同一真实 Window 的受支持参数。
- 目标进程必须是当前选择的 debuggable 进程，并支持 `run-as` 与 `attach-agent`。
- Accessibility 语义节点、全局模式节点、non-debuggable 进程和系统进程保持只读。
- 修改只影响当前进程中的对象；重新 inflate、列表复用、业务重新赋值、Activity 重建或进程重启后可能恢复。
- 不执行任意 Java 表达式，不开放任意方法名，不修改监听器，不构造复杂 Android 对象。
- `Drawable`、`Typeface`、`ColorStateList`、`Rect`、`Paint`、监听器及其他复杂参数类型保持只读，并显示不支持原因。

支持的输入类型：

- `boolean` / `Boolean`。
- `byte`、`short`、`int`、`long`、`float`、`double` 及包装类型。
- `char` / `Character`。
- `String` / `CharSequence`。
- Java enum。
- 颜色文本：`#RRGGBB` 与 `#AARRGGBB`。
- 已知 Android 常量，如 `VISIBLE`、`INVISIBLE`、`GONE`、`MATCH_PARENT` 与 `WRAP_CONTENT`。

## 3. 采用方案

### 3.1 备选方案

1. **每次修改临时挂载 Agent（采用）**：复用现有部署、`run-as`、`attach-agent` 和清理链路。没有常驻服务，失败边界清晰，代价是一次提交可能需要数百毫秒到数秒。
2. **常驻 Agent 命令通道（不采用）**：可支持连续滑杆式实时编辑，但需要长期持有进程内服务、端口和对象引用，生命周期、安全性及车机兼容成本更高。
3. **仅 Accessibility（不采用）**：只能点击、滚动、输入或执行公开 Action，不能满足 View setter 属性编辑。

### 3.2 编辑能力发现

采集真实 View 时，为每个已查询属性计算可选的编辑描述。属性只有同时满足以下条件才可编辑：

1. 属性来自 Real View 或受支持的真实 Window 参数。
2. 存在名称匹配的 public 单参数 setter，且参数属于支持类型；或者命中本设计列出的专用适配器。
3. setter 候选能被确定性选择。存在重载时，优先选择与对应 getter 返回类型一致的 setter；其次使用属性专用规则。例如 `TextView.text` 固定选择 `setText(CharSequence)`，不能把数字文本误当成字符串资源 ID。

编辑描述随属性值序列化，至少包含：

```text
PropertyEditor
├── target: VIEW | LAYOUT_PARAMS | WINDOW_PARAMS
├── valueType: BOOLEAN | INTEGER | FLOAT | STRING | CHARACTER | ENUM | COLOR
├── setter/adapter: 内部稳定标识
├── choices[]: 可选常量或 enum 值
└── supported: true
```

没有编辑描述的属性在浏览器中只读。浏览器不能根据属性名称自行猜测可编辑性。

## 4. 专用属性适配

除标准 public 单参数 setter 外，首版支持这些聚合或语义不同的属性：

- `paddingLeft/Top/Right/Bottom`：保留另外三边并调用 `View.setPadding()`。
- `layoutParamWidth/layoutParamHeight`：修改 `ViewGroup.LayoutParams`，再调用 `setLayoutParams()`。
- `marginLeft/Top/Right/Bottom/Start/End`：修改 `MarginLayoutParams` 并重新设置 LayoutParams。
- `textSizePx`：使用 PX 单位调用 `TextView.setTextSize()`。
- `textColor`：解析颜色后调用 `TextView.setTextColor(int)`。
- `imageScaleType`：解析 `ImageView.ScaleType` enum。
- `checked`：通过 `Checkable.setChecked(boolean)`。
- Window 的 `x`、`y`、width、height、gravity、alpha、dimAmount 和 softInputMode：修改对应 `WindowManager.LayoutParams` 后调用 `WindowManager.updateViewLayout()`。

`boundsInScreen`、measured size、shown、focused、hasOnClickListeners、bitmap 信息、class、resourceId、节点层级、Window type/flags/format 等派生、身份或高风险字段保持只读。

## 5. 节点定位与防误改

真实节点 ID 当前编码 Window root 下标与 child index 路径，例如 `v:n:0:0.1.2`。修改时 Agent 重新读取当前 `WindowManagerGlobal` roots，并按该路径查找对象。

请求必须同时携带最近一次快照中的：

- node/window ID；
- 目标 class；
- resourceId（存在时）；
- package、process 与 pid；
- property name 和编辑描述标识。

Agent 在调用 setter 前重新校验 class 与 resourceId。Window 数量、child 路径或身份不匹配时拒绝修改，返回“布局已变化，请重新采集”，不能退化为修改路径当前位置的其他 View。

## 6. 修改链路

```text
Browser property editor
  -> POST /api/mutate (session token + same-origin)
  -> InspectorApi validates current target snapshot and editor metadata
  -> AgentDeployer writes a mutation command JSON
  -> ADB pushes helper, Agent and command into the selected debuggable package
  -> cmd activity attach-agent
  -> native bootstrap obtains current Window roots
  -> helper resolves the View and executes mutation on Android main thread
  -> requestLayout() + invalidate(), or WindowManager.updateViewLayout()
  -> helper captures a fresh real View snapshot and writes MutationResult
  -> plugin merges screenshot/process information and returns the updated snapshot
  -> browser preserves selection and redraws tree, 2D/3D stage and properties
```

命令文件而不是 Agent 参数直接承载 JSON，避免 Unicode、逗号、长度和 Shell 转义问题。命令与结果文件位于目标应用 `code_cache/uiinspector`，操作结束后尽力清理。

## 7. API 与结果

新增受现有 session token 和 Origin 保护的 `POST /api/mutate`。请求只能引用服务端当前快照中带编辑描述的属性。

结果包含：

- `success`；
- `previousValue` 与 `appliedValue`；
- 失败时的稳定错误代码和用户可读信息；
- 成功后重新采集的 `InspectionSnapshot`。

主要错误代码：目标不是 Real View、目标不可调试、快照已过期、属性只读、值格式错误、setter 抛异常、Agent attach 失败和修改超时。错误响应不包含应用私有文本或完整堆栈。

## 8. 浏览器交互

- 属性面板默认仍是查看状态；可编辑属性显示编辑按钮。
- boolean 使用开关，enum/常量使用下拉框，数字、字符、字符串和颜色使用对应输入框。
- 用户修改输入值后点击“应用”；仅输入不会修改设备。
- 提交期间锁定当前编辑器并显示进度，其他属性仍可查看。
- 成功后自动刷新快照并保持当前节点、Window、2D/3D 模式和深度筛选。
- 浏览器为每个“进程 + 节点 + 属性”记录本次会话首次编辑前的值，显示“恢复”按钮。恢复通过同一个安全 mutation API 执行，不是本地伪更新。
- 重新采集、切换设备/进程/范围或节点身份变化时清理不再安全的恢复记录。
- 只读属性不显示禁用输入框；Real View 但参数类型不支持时，在属性来源旁显示简短原因。

## 9. 安全与稳定性

- mutation API 只监听 `127.0.0.1`，继续使用高熵 session token、POST 和同源校验。
- 服务端从当前快照读取 editor metadata，不接受浏览器提供任意 setter 名。
- Agent 端再次计算和验证允许的 setter/adapter，不能只信任桌面请求。
- 字符串设置最大长度；数值必须完整解析并检查目标类型范围；float/double 拒绝 NaN 与 Infinity。
- 所有 View 操作在主线程执行；成功后统一调用 `requestLayout()` 与 `invalidate()`。
- setter 抛出的异常被捕获并转化为失败结果，不能导致 Agent 等待超时而没有诊断。
- 一次请求只修改一个属性，不提供批量脚本或任意反射入口。
- Accessibility 全局能力与 mutation 完全隔离，全局节点不出现编辑描述。

## 10. 测试与验收

自动化测试覆盖：

- 协议可序列化编辑描述、mutation 命令和结果，旧的只读属性仍可解析。
- Agent helper 能发现并修改 boolean、数字、字符串、enum、颜色和字符 setter。
- setter 重载选择确定，`TextView.text` 不会调用资源 ID 重载。
- padding、margin、LayoutParams、textSize、textColor、checked 和 Window 参数适配正确。
- complex object、派生属性、Accessibility 节点和 non-debuggable 目标不可编辑。
- 节点路径、class 或 resourceId 过期时拒绝修改。
- 插件正确部署命令文件、调用 attach-agent、读取结果并清理。
- `/api/mutate` 拒绝无 token、跨 Origin、GET、非当前节点和只读属性。
- 浏览器按类型渲染编辑器，应用成功后刷新，失败时保留输入，并能恢复编辑前值。

设备端验收使用现有 API 28+ 示例应用：

1. 修改 TextView 文本并在设备上立即观察到变化。
2. 修改 `enabled` boolean、`alpha` float、visibility 常量和 ImageView scale type。
3. 修改 padding、margin 与 LayoutParams 尺寸，确认布局刷新。
4. 修改 `WindowManager.addView()` root 的 Window x/y 或尺寸。
5. 恢复每项编辑前值。
6. 重新采集后数值与设备画面一致。
7. 全局模式、Semantic 节点、non-debuggable 进程和复杂对象属性保持只读。
8. 目标应用源码、APK、Manifest 与 Gradle 无任何改动；重启目标进程后运行时修改消失。

完整 Gradle 测试、Android 构建、插件验证、Web 测试以及真实模拟器端到端验证必须全部通过。
