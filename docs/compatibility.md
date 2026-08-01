# 兼容性与采集边界

## 支持矩阵

| 项目 | 支持范围 |
| --- | --- |
| Android Studio | 保证 Quail 2 / 2026.1.2 Patch 1（AI-261）；插件元数据限制为 261.* |
| 桌面系统 | macOS、Windows；Linux 未纳入本阶段验证 |
| Android 设备 | API 28 及以上 |
| UI 技术 | 传统 View；本阶段不支持 Compose |
| 目标应用改动 | 无；不接 SDK、不改源码/Gradle/Manifest |

## 两种保真度

**Semantic · Accessibility** 是默认和全局能力。它可以读取系统公开给无障碍服务的 Window 与节点，包括多个应用和部分系统 Window。结果受应用/OEM 的无障碍实现、节点重要性、WebView 语义暴露和系统权限边界限制。

**Real View · JVMTI** 仅对用户选中的 debuggable 进程尝试。它读取该进程 `WindowManagerGlobal` 中的传统 View 根，因此可覆盖 Activity、Dialog、PopupWindow 和直接 `WindowManager.addView()`。附加失败时自动保留 Semantic 结果，并在诊断区说明原因。

## Window 与 View 层次

- Window 3D 使用系统/进程返回的 Window layer；父子 Window 关系单独保留，不把两者混成一个概念。
- View 3D 只展示所选 Window 根内部的 View depth。
- API 30 及以上可从 AccessibilityService 获取所有公开 display 的 Window；API 28–29 使用默认 display 的公开 Window 列表。
- ADB `screencap` 当前提供默认屏幕截图；其他 display 仍可查看树和属性，但不保证有对应截图。

## 无法或不会绕过的边界

- 非 debuggable 应用不能使用本工具的 JVMTI 增强采集。
- 系统未公开的内部 Window/View、厂商私有安全界面和被裁剪的无障碍节点无法保证获取。
- `FLAG_SECURE`、DRM 和系统隐私策略不会被绕过；截图可能为空或黑色，但已公开的层级与属性仍会尽量保留。
- Collector 仅允许 ADB shell 或自身 UID 读取快照；本地网页服务只绑定 `127.0.0.1`，并使用随机会话令牌和同源校验。
