# Android UI Inspector 设备端设计

## 1. 背景

Android 研发在真机上定位 UI 问题时，通常需要连接电脑并使用 Layout Inspector、UI Automator 或临时日志。该工具以 debug SDK 形式集成到公司 Android 调试工具中，让研发可以直接在设备上拾取 UI、查看属性和层级，并覆盖 Activity、Dialog、PopupWindow 以及通过 `WindowManager.addView()` 创建的窗口。

当前阶段只交付设备端。未来桌面形态保留为 Android Studio 插件，仅在数据模型和模块边界上预留扩展能力，本阶段不实现桌面 UI、ADB Bridge、端口转发或远程控制。

## 2. 已确认范围

### 2.1 目标

- 最低支持 Android 9（API 28）。
- 第一版只检查传统 Android View，不检查 Jetpack Compose UI。
- SDK 集成在哪个应用进程，默认检查该进程中的 UI。
- 默认模式不要求用户开启无障碍服务。
- 用户可主动切换到“全局 App”模式，检查当前屏幕上其他应用公开的 Accessibility UI。
- 当前进程模式覆盖：
  - Activity
  - Dialog
  - PopupWindow
  - `WindowManager.addView()` 添加的 View
  - 同时存在的多个 Window Root
- 提供 debug-only Gradle 插件自动插桩。
- 提供 WindowManager 包装器和少量显式注册 API 作为稳定兜底。
- 支持坐标拾取、目标高亮、节点属性、层级路径、窗口切换和报告导出。
- API 28–29 的全局模式不强制提供截图。

### 2.2 非目标

- 不解析或还原 Compose Composition/Semantics 树。
- 不保证获取第三方 App 的真实 View 树；全局模式只展示 Accessibility 节点。
- 不按 PID 筛选第三方 App。Accessibility 节点通常只能可靠标识包名和窗口。
- 不实现桌面客户端、Android Studio 插件或实时屏幕镜像。
- 不使用 Root 权限。
- 不把非 SDK/隐藏 API 作为核心能力依赖。
- 不进入 Release APK/AAB。

## 3. 产品模式

### 3.1 当前进程模式

这是默认模式。SDK 从 `Context.packageName` 和 `Application.getProcessName()` 确认宿主包与当前进程，通过进程内 View 采集器读取真实 View Root 和属性。

优势：

- 不需要无障碍授权。
- 获取真实 View 类型和父子关系。
- 可读取布局、绘制、交互和 Window 参数。
- 能覆盖未暴露 Accessibility 语义的 View。

若 SDK 初始化在没有 UI 的远程进程中，界面显示“当前进程没有可检查的 Window”，并允许用户切换到全局模式。

### 3.2 全局 App 模式

用户主动选择后，工具检查 AccessibilityService 是否启用；未启用则引导进入系统设置。服务默认不设置 `packageNames`，即查看所有应用。全局模式采集多个 `AccessibilityWindowInfo`，生成 Accessibility 节点快照。

全局模式必须标注数据来源，并提示：Accessibility 节点树不保证与目标应用真实 View 树一一对应，属性也少于当前进程模式。

## 4. 总体架构

```text
ui-inspector-api
├── 公共配置
├── Root 注册 API
└── 稳定数据模型

ui-inspector-runtime
├── 会话与模式控制
├── 进程内 View 采集
├── Accessibility 全局采集
├── RootViewRegistry
├── 坐标命中
├── 高亮与设备端面板
├── 截图适配
└── 报告导出

ui-inspector-noop
└── Release 空实现

ui-inspector-gradle-plugin
├── AGP 8.x Debug Variant 插桩
└── 插桩覆盖报告
```

依赖建议：

```kotlin
plugins {
    id("com.company.ui-inspector")
}

dependencies {
    debugImplementation("com.company:ui-inspector-runtime:<version>")
    releaseImplementation("com.company:ui-inspector-noop:<version>")
}
```

Gradle 插件第一版支持 AGP 8.2.2–8.13.2。发现不兼容版本时构建失败并给出明确提示，不静默跳过插桩。

## 5. 当前进程 Window Root 发现

Android 没有公开 API 允许普通 SDK 无侵入枚举本进程的全部 View Root。因此采用“稳定注册表为核心、自动发现与兜底并存”的方案。

### 5.1 RootViewRegistry

所有发现方式统一写入进程级注册表：

```kotlin
interface RootViewRegistry {
    fun register(
        view: View,
        source: DiscoverySource,
        metadata: RootMetadata
    ): RootRegistration

    fun update(view: View, layoutParams: ViewGroup.LayoutParams?)
    fun unregister(view: View)
    fun snapshot(): List<RegisteredRoot>
}
```

约束：

- 按 View 对象身份去重，而不是按 `hashCode()` 去重。
- 注册表不应强持有已移除的 Root；记录使用 WeakReference，并监听 attach/detach。
- 同一 Root 可以有多个发现来源，使用来源集合或引用计数管理。
- 所有 Root 变更在主线程串行化；快照转换完成后才交给后台线程处理。
- Inspector 自身的悬浮 Root 使用内部标记排除。

### 5.2 Activity 自动跟踪

通过 `Application.ActivityLifecycleCallbacks` 跟踪 Activity Window，不只保存最后一个 Activity。透明 Activity、多窗口模式下可能同时存在多个可见 Root。

Activity 销毁时注销对应 DecorView；暂时不可见但仍附着的 Root 保留状态，由命中阶段根据可见区域过滤。

### 5.3 Gradle 插件插桩

插件只处理 Debug Variant，自动跟踪以下调用：

- `WindowManager.addView()`
- `WindowManager.updateViewLayout()`
- `WindowManager.removeView()`
- `WindowManager.removeViewImmediate()`
- `Dialog.show()` / `dismiss()` / `cancel()`
- `PopupWindow.showAsDropDown()` / `showAtLocation()` / `dismiss()`

插件记录调用点元数据，包括模块、类、方法和可用的源码行号，供设备端问题报告以及未来 Android Studio 插件源码导航使用。

插件默认只插桩当前工程和明确配置的公司模块，不修改任意第三方预编译依赖。构建结束输出覆盖报告和可疑的 add/remove 不对称警告。

### 5.4 WindowManager 包装器

为公司统一悬浮窗框架提供透明代理：

```kotlin
val tracked = UiInspector.wrap(windowManager)
tracked.addView(view, params)
tracked.updateViewLayout(view, params)
tracked.removeView(view)
```

包装器调用真实 WindowManager 后，同步通知 RootViewRegistry。公司已有统一 WindowManager 管理器时优先在公共入口接入一次。

### 5.5 显式注册

特殊窗口使用句柄式 API：

```kotlin
val registration = UiInspector.registerRoot(
    view = root,
    metadata = RootMetadata(
        type = RootType.CUSTOM,
        name = "VideoFloatingPanel"
    )
)

registration.close()
```

同时提供 `registerRootUntilDetached()`，在 View detach 后自动注销。No-op 实现不得保存 View 引用。

### 5.6 非 SDK 反射与 Accessibility 兜底

`WindowManagerGlobal` 反射只作为默认关闭的实验能力。反射失败不能影响 Inspector 主流程，也不能要求研发修改设备全局 `hidden_api_policy`。

若全局 Accessibility 已开启，并发现属于宿主包但未进入进程内注册表的窗口，运行时提示存在采集盲区。该窗口可使用 Accessibility 节点临时展示，但必须标记属性不完整。

## 6. 快照模型

运行时对象不得把 `View` 或 `AccessibilityNodeInfo` 直接交给展示、导出或未来桌面端。每次采集生成不可变快照。

```kotlin
enum class InspectionSource {
    IN_PROCESS_VIEW,
    ACCESSIBILITY
}

data class UiNodeSnapshot(
    val id: String,
    val source: InspectionSource,
    val packageName: String,
    val processName: String?,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val boundsInScreen: RectSnapshot,
    val visibleBoundsInScreen: RectSnapshot?,
    val flags: NodeFlags,
    val properties: Map<String, String>,
    val parentId: String?,
    val childIds: List<String>
)

data class WindowRootSnapshot(
    val id: String,
    val packageName: String,
    val processName: String?,
    val type: RootType,
    val discoverySources: Set<DiscoverySource>,
    val title: String?,
    val boundsInScreen: RectSnapshot,
    val displayId: Int,
    val layerHint: Int?,
    val layoutParams: WindowLayoutParamsSnapshot?,
    val rootNodeId: String
)
```

`WindowManager.LayoutParams` 重点保留：type、flags、gravity、x/y、width/height、format、token 摘要、是否可触摸、是否可聚焦以及 displayId。敏感对象不直接序列化。

快照结构从第一版起保持可序列化和带协议版本，以便未来 Android Studio 插件复用，但本阶段不实现网络传输。

## 7. 坐标拾取与窗口命中

### 7.1 当前进程模式

拾取流程：

1. 显示一次性透明取点层。
2. 记录 Display 和屏幕坐标。
3. 立即移除可触摸取点层，只保留不可触摸的高亮层。
4. 获取 RootViewRegistry 的稳定快照。
5. 排除 Inspector 自身 Root、未附着 Root 和不在目标 Display 的 Root。
6. 按窗口层级提示、添加顺序、窗口类型和可见区域排列候选 Root。
7. 在候选 Root 中按逆绘制顺序遍历 View。
8. 对所有包含坐标的候选节点评分。
9. 默认选中最高分节点，并允许切换父节点、重叠节点和下层窗口。

当前进程模式不申请系统悬浮窗权限。实现时针对每个可检查 Root，使用该 Root 的 Window token 和屏幕边界创建短生命周期的 `TYPE_APPLICATION_PANEL` 取点子窗口；任意一个子窗口收到第一次 `ACTION_UP` 后，立即统一移除所有取点子窗口。高亮子窗口同样绑定目标 Root token，但必须不可触摸、不可聚焦。这样才能同时覆盖 Activity、Dialog、PopupWindow 和直接通过 WindowManager 添加的独立 Root。

候选评分考虑：

- 是否显示且附着到 Window
- 实际可见区域是否包含坐标
- 面积大小
- 层级深度
- clickable/focusable/scrollable
- 是否具有 resource ID、文本或 content description
- alpha、visibility 和 enabled 状态
- View 与父节点的裁剪关系

### 7.2 全局模式

通过 Accessibility Overlay 捕获一次坐标，随后调用 `getWindows()` 获取所有可交互窗口，按窗口层级从上到下生成候选节点。节点树必须在拾取时重新获取，不缓存长期存活的 AccessibilityNodeInfo。

### 7.3 坐标差异

统一使用屏幕坐标，并显式处理：

- 状态栏和导航栏 Insets
- 横竖屏变化
- 分屏和自由窗口
- 多 Display
- View translation、scale 和 matrix
- 父节点裁剪

## 8. 设备端交互

### 8.1 入口

公司 Android DebugTools 中提供“UI Inspector”入口，首页显示：

- 当前包名与进程名
- 当前进程/全局 App 模式选择
- Accessibility 状态
- 已发现 Window 数量
- 启动/停止悬浮拾取
- 覆盖诊断

### 8.2 悬浮操作

- 边缘悬浮球可拖动。
- 点击进入一次性拾取模式。
- 选中后绘制矩形高亮。
- 简化面板展示 class、id、text、bounds、交互状态和 Window 来源。
- 提供父节点、子节点、重叠节点和下层 Window 切换。
- 面板可收起，避免长期遮挡目标 UI。
- 完整树放在 DebugTools 内部页面，不塞入小型悬浮窗。

### 8.3 完整检查页

设备端完整页包含：

- Window 列表
- 可展开 View/Accessibility 树
- 节点搜索：ID、文本、类名
- 属性分组
- 节点路径
- 发现方式和数据来源
- 复制属性
- 导出报告

### 8.4 交互效率约束

设备端交互以“少打断、少步骤、信息渐进展示”为原则：

- 默认检查当前进程，启动时不弹无障碍授权；只有切换到全局模式才请求用户处理权限。
- 从悬浮球进入拾取并选中目标最多两次点击：点击悬浮球、点击目标 View。
- 取点层只消费一次触摸，取得坐标后立即移除，不能持续阻断目标 App 操作。
- 拾取成功提供轻量触觉反馈，并立即显示高亮；失败时保留拾取入口并说明原因。
- 悬浮球视觉尺寸保持克制，但可点击区域不得小于 48dp；支持自动贴边并记住用户位置。
- 高亮层不可触摸，不应改变目标 Window 的焦点、软键盘或原有触摸行为。
- 简化面板默认只展示 class、id、text、bounds、状态和 Window 来源；完整属性按分组展开。
- 简化面板优先出现在目标节点之外；空间不足时允许拖动、贴边和一键收起。
- 父节点、子节点、重叠节点和下层 Window 使用固定位置的快捷操作，切换后保持高亮同步。
- 树页面打开时自动展开并滚动到当前选中节点，不要求用户重新查找。
- 当前进程/全局 App、View/Accessibility 数据来源和能力降级必须始终可见，避免用户误解结果完整度。
- 离开检查页或停止 Inspector 后，所有取点层、高亮和临时面板立即移除。

### 8.5 交互响应指标

在约定的基准设备和节点数不超过 2,000 的页面上，验收目标为：

- 点击悬浮球后，取点层在 150ms 内可用。
- 用户点击目标后，300ms 内显示高亮和基础属性；超过 150ms 时显示非阻塞的采集中状态。
- 父节点、重叠节点和下层 Window 切换在 100ms 内更新高亮。
- 搜索输入采用 150ms 去抖，不能阻塞输入线程。
- 单次采集不得在主线程连续执行超过 50ms 的树转换或属性序列化工作。
- 任何采集、截图和报告导出过程都不能造成宿主 App ANR。

响应指标通过基准页面自动测试和真机操作测试验证；达不到指标时优先降级截图、非关键属性和全树构建，不能牺牲拾取与高亮的即时反馈。

## 9. 截图策略

截图是可选增强，不是节点采集前置条件。

- 当前进程已知 Window：优先使用 PixelCopy。
- 无公开 Window 对象的 Root：可使用 `View.draw(Canvas)` 生成局部图片，并标记 SurfaceView/TextureView 可能缺失。
- 全局模式 API 30+：使用 Accessibility 截图能力。
- 全局模式 API 28–29：允许无截图。
- 截图失败不影响节点树、高亮、属性查看和报告生成。

导出模型中的截图字段始终可空，并记录 unsupported、permission_denied、secure_window、timeout 等失败原因。

## 10. 权限与安全

### 10.1 当前进程模式

不要求无障碍权限，也不要求普通应用悬浮窗权限。优先使用宿主进程内 Window 添加检查 UI。

### 10.2 全局模式

仅在用户主动选择后引导开启 AccessibilityService。使用 `TYPE_ACCESSIBILITY_OVERLAY` 进行取点与高亮，不额外申请 `SYSTEM_ALERT_WINDOW`。

### 10.3 Release 隔离

- Gradle 插件仅修改 Debug Variant。
- Release 使用 No-op 实现。
- Release Manifest 不包含 AccessibilityService。
- Release 不包含报告导出入口、调试端口或 Inspector 资源。
- CI 检查 Release APK/AAB 中不存在 Inspector Service、Receiver、Provider 和特征类。

### 10.4 数据保护

- 报告默认保存在 App 私有目录。
- 分享报告必须由用户显式触发。
- text、content description 等可能包含敏感数据，导出前展示提示。
- 提供文本脱敏开关，至少支持手机号、邮箱和自定义正则。

## 11. 性能与并发

- View 和 Accessibility 树读取在各自要求的线程中进行；跨线程前转换为不可变快照。
- 不持续遍历整棵树，仅在用户拾取、手动刷新或窗口稳定后采集。
- 默认限制最大节点数和最大深度，超限时保留局部路径并明确提示截断。
- 属性读取采用白名单；不调用可能触发业务逻辑的任意 getter。
- Accessibility 事件进行去抖，避免页面滚动时频繁重建树。
- 所有 Bitmap 按显示尺寸采样，导出完成后及时释放引用。

## 12. 错误处理

设备端必须对以下状态给出可行动提示：

- 当前进程没有 Window
- Accessibility 未开启或服务未连接
- Window 已在采集过程中移除
- 节点在展示前失效
- 第三方 Window 未被进程内注册
- 截图不支持、超时或被 FLAG_SECURE 阻止
- 节点树超出限制并被截断
- 插件与 Runtime 版本不兼容
- SDK 初始化在非 UI 进程

采集失败不得导致宿主 App 崩溃。所有 Inspector 顶层入口都必须隔离异常并生成诊断日志。

## 13. 测试策略

### 13.1 单元测试

- Root 注册、去重、引用计数和自动清理
- Window 与节点候选排序
- 坐标变换和可见区域计算
- 节点评分
- 快照序列化兼容性
- 文本脱敏
- 能力矩阵和错误映射

### 13.2 插件测试

- AGP 8.x 代表版本的 TestKit 工程
- Debug Variant 发生插桩，Release Variant 不发生插桩
- WindowManager/Dialog/PopupWindow 调用识别
- add/remove 成对事件
- Kotlin、Java 和多模块工程
- 不兼容 AGP 版本明确失败
- Runtime/插件版本不匹配明确失败

### 13.3 设备测试矩阵

至少覆盖：

- API 28、30、34 和当前最高支持版本
- Activity、Dialog、PopupWindow
- Application Context 与 Activity Context 的 WindowManager.addView
- 透明窗口、不可触摸窗口和重叠窗口
- 分屏、横竖屏切换和系统栏 Insets
- SurfaceView/TextureView 截图降级
- 全局 Accessibility 多窗口
- FLAG_SECURE 页面
- Inspector 自身窗口排除
- 多进程应用中 UI 进程与无 UI 远程进程

### 13.4 Release 验证

- Release 构建成功
- Release 清单无 AccessibilityService
- Release 字节码无 Runtime 实现
- No-op 不持有 View
- Release 包体变化在约定阈值内

## 14. 第一阶段验收标准

1. SDK 集成到 AGP 8.x 示例工程后，默认进入当前进程模式且无需无障碍权限。
2. 能拾取并查看 Activity、Dialog、PopupWindow 和 WindowManager.addView 创建的 View。
3. 插件、包装器和显式注册产生的 Root 在注册表中正确去重和清理。
4. 可以在重叠节点、父子节点和多个 Window 之间切换。
5. 用户主动切换全局模式后，可以查看其他 App 暴露的 Accessibility 节点。
6. API 28–29 全局模式没有截图时，其余检查功能正常。
7. 导出的版本化 JSON 报告包含设备、进程、Window、节点、来源和可选截图状态。
8. 采集失败不会导致宿主 App 崩溃，并能显示诊断原因。
9. Release 构建不包含 Inspector Runtime、AccessibilityService 或调试入口。
10. 当前进程模式从悬浮球到选中目标不超过两次点击，并满足 8.5 节的交互响应指标。

## 15. 未来桌面扩展

未来桌面端优先实现为 Android Studio 插件，支持 macOS 和 Windows，不计划支持 Linux。插件复用本设计的版本化快照模型，提供截图、Window/View 树和属性三栏联动，并可根据资源 ID 或插桩调用点跳转源码。

该扩展不属于当前阶段。当前实现只需保证快照模型可序列化、调用点元数据可保留，不需要提前实现网络服务、ADB 协议或桌面专用逻辑。
