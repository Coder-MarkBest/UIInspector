# Android Studio UI Inspector 插件设计

## 1. 目标

交付一个完全独立的 Android Studio 插件。业务工程不引入 SDK、不修改源码、Gradle 或 Manifest。插件通过 ADB 安装自带的 Collector APK，在本机启动仅监听回环地址的 Web 服务，并在浏览器中查看设备当前 UI。

首版约束：

- 最低设备版本 Android 9（API 28）。
- 保证兼容 Android Studio Quail 2 | 2026.1.2 Patch 1。
- 桌面平台支持 macOS 与 Windows，不要求 Linux。
- 只检查传统 Android View；不解析 Compose Composition 或 Semantics。
- 默认选择当前 Android Studio 工程对应的应用进程；用户可以切换为全局窗口。
- 尽可能覆盖 Activity、Dialog、PopupWindow、`WindowManager.addView()`、SystemUI、多窗口和多显示器。
- 不使用 Root，不修改安全策略，不绕过 `FLAG_SECURE`、debuggable 或系统权限检查。

## 2. 能力边界

采集分为两级，浏览器必须显示来源和可信度：

1. **语义采集（全局）**：Collector 的 `AccessibilityService` 使用 `getWindows()` 或 API 30+ 的 `getWindowsOnAllDisplays()`，读取公开的 `AccessibilityWindowInfo` 与 `AccessibilityNodeInfo`。它覆盖所有允许无障碍检查的应用和系统窗口，但节点不等同于真实 View，属性可能缺失。
2. **真实 View 增强（目标进程）**：仅对运行中且 `debuggable=true` 的应用，插件用官方 `adb shell cmd activity attach-agent` 加载只读 JVMTI Agent。Agent 在目标进程中枚举 `WindowManagerGlobal` 的 root views，并由随 Agent 部署的 helper APK 在主线程生成真实 View 快照。业务包不需要预先接入代码。

自动降级规则：

- 目标进程可 attach：目标模式优先展示真实 View 快照。
- 不能 attach、目标为系统进程或增强采集失败：展示语义快照，并解释降级原因。
- `FLAG_SECURE`：不抓截图；仍展示公开可获得的窗口/节点信息。
- SurfaceView、OpenGL、Canvas 自绘和不可访问的 Web 内容：只展示宿主 View 或公开语义节点，不声称还原其内部内容。
- Accessibility 只返回可交互窗口；被模态窗口完全遮挡且不可交互的窗口可能缺失。这是数据限制而不是工具错误。

## 3. 产品结构

```text
Android Studio Plugin
├── Device/Process discovery (ADB)
├── Collector installer and status
├── Semantic capture client
├── Debuggable attach-agent client
├── Capture merger and diagnostics
└── Loopback web server
        └── Browser inspector

Device
├── Collector APK
│   ├── setup activity
│   ├── AccessibilityService
│   └── shell-only snapshot ContentProvider
└── Optional debuggable target process
    ├── native JVMTI bootstrap .so
    └── helper APK loaded by DexClassLoader
```

插件 ZIP 内自带 Collector APK、helper APK 和四种 ABI 的 Agent AAR。运行时只在用户选中的设备上安装/部署这些文件。

## 4. 会话流程

1. 用户打开 `Tools > UI Inspector > Open Inspector` 或插件工具窗口。
2. 插件解析项目 Android SDK；找不到时回退到 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 和 PATH 中的 `adb`。
3. 用户选择设备。插件自动安装或升级 Collector APK。
4. Collector 无障碍服务未开启时，插件显示明确指引，并可打开设备的无障碍设置。
5. 插件列出运行进程，默认选中当前工程 application ID 对应进程；无法确定时选中前台包。
6. 插件采集语义窗口、窗口管理诊断与截图；若目标进程可调试，再采集真实 View。
7. 插件在 `127.0.0.1` 随机端口启动 Web 服务并打开带随机 session token 的浏览器地址。
8. 浏览器通过同源 API 读取会话数据。用户刷新采集、切换全局/目标模式或切换进程时，不需要修改业务应用。

## 5. 数据协议

协议版本固定为 `1`。快照由不可变、可序列化数据组成，不跨进程传递 Android 对象。

```text
InspectionSnapshot
├── schemaVersion, capturedAt, device, scope, diagnostics
├── screenshot? { mimeType, dataUrl, secureOrUnavailableReason? }
├── processes[] { pid, user, packageName, processName, debuggable }
├── windows[]
│   ├── id, source, packageName, processName?, displayId
│   ├── type, layer, parentWindowId?, childWindowIds[]
│   ├── bounds, active, focused, title, rootNodeId?
│   └── properties
└── nodes: Map<nodeId, UiNode>
    ├── id, source, windowId, parentId?, childIds[], depth
    ├── className, resourceId?, text?, contentDescription?
    ├── bounds, visibleBounds?, drawingOrder?
    └── propertyGroups
```

Window 层级来自 `AccessibilityWindowInfo.parent/child`；Z 顺序来自 `layer`。二者分别保留，不能把父子关系误作 Z 顺序。

## 6. 属性分组

右侧属性面板按以下顺序展示，只显示实际获得的字段：

1. **标识**：class、resource id、package、process、window id、node id、capture source。
2. **Window 与层级**：parent、children、depth、drawing order、window type、layer、display id、root。
3. **几何与布局**：screen/local bounds、visible bounds、x/y、measured/layout size、padding、margin、min/max、layout params、gravity。
4. **绘制与变换**：visibility、shown、alpha、elevation/Z、translation、rotation、scale、pivot、clip、background、foreground、layer type。
5. **内容**：text、hint、content description、TextView 与 ImageView 的公开属性、drawable 类型、scale type。
6. **交互与状态**：enabled、clickable、long-clickable、focusable/focused、selected、pressed、activated、checkable/checked、scrollable。
7. **无障碍**：importantForAccessibility、class/role、state description、live region、actions。
8. **采集诊断**：backend、fidelity、capturedAt、字段读取异常、截断原因、降级原因。

真实 View 后端和语义后端使用同一分组名，但不伪造对方才有的属性。

## 7. 浏览器交互

沿用已批准的横向三栏：

```text
┌──────────────── device / process / scope / refresh ────────────────┐
│ Window + View tree │ 2D / 3D stage               │ grouped props   │
│ 252px resizable    │ remaining width             │ 320px resizable │
└─────────────────────────────────────────────────────────────────────┘
```

- 左侧先列 Window，再列该 Window 的 View/语义节点树；支持展开、折叠、搜索和选中。
- 中间支持 2D 截图叠加与 3D。
- 3D 的 **Window 模式** 只显示同一 Display 上不同 Window 的 layer 与 Window 父子关系。
- 3D 的 **View 模式** 必须先选一个 Window，只显示该 Window 内部 View parent-child depth。
- 两种深度不混合。工具栏明确标注当前是 `Window layers` 还是 `View depth`。
- 支持只显示一个 View depth；Window 模式支持只显示某个 Window layer。
- 点击树、2D 框或 3D 平面会同步选中并更新右侧属性。
- 默认关闭异常检查。用户开启后才计算并显示“布局不合理”和“异常大图”候选；选中候选时高亮对应节点。
- 所有操作支持键盘焦点；降低动画系统设置下停用过渡动画。

## 8. 异常检查

规则只生成候选，不自动断言业务错误：

- **布局候选**：零面积但可见、超出父节点可见区域、尺寸显著超过 Window、可点击但可见交集为空、深度超过阈值。
- **异常大图候选**：真实 View 后端中 ImageView/drawable 的 intrinsic 面积或 bitmap byte count 显著超过显示面积/阈值。

每条候选包含 rule id、严重度、原因、证据字段和 node id。全局语义模式拿不到 drawable/bitmap 信息时不运行大图规则。

## 9. 安全与隐私

- Web 服务只监听 `127.0.0.1`，每次会话使用高熵 token；API 校验 token 和 `Origin`。
- Collector ContentProvider 只接受 shell UID 或自身 UID，其他调用返回 `SecurityException`。
- 临时 Agent 文件放在目标应用 `code_cache/uiinspector`，只对 debuggable 包使用 `run-as`，会话结束尽力清理。
- 日志不打印节点文本、content description、截图内容或完整快照。
- 截图和快照仅保存在内存与插件临时目录，关闭会话后删除。

## 10. 验收标准

- 在无业务代码改动的前提下安装插件并自动安装 Collector APK。
- API 28 模拟器上可启用 Collector、抓取至少一个应用 Window 及节点树。
- API 30+ 可读取多显示器窗口集合；没有第二显示器时仍返回 display 0。
- 对 debuggable 示例应用，attach-agent 能输出 Activity 与 `WindowManager.addView()` root 的真实 View 树。
- 对 non-debuggable 目标，自动降级且浏览器说明原因。
- 浏览器能切换全局/目标模式、Window/View 3D、单层深度、节点属性和可选异常结果。
- `FLAG_SECURE` 时不显示截图，也不报采集成功的假状态。
- 单元测试、Android 构建、插件构建与前端浏览器回归检查通过。

