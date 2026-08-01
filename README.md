# Android UI Inspector

一个完全独立的 Android Studio 调试插件。目标应用不需要接入 SDK，也不需要修改源码、Gradle 或 Manifest。插件通过 ADB 安装自带 Collector APK，并在本机浏览器中横向展示 Window/View 树、2D/3D 层次和分组属性。

## 当前能力

- 默认选择当前 Android Studio 工程的 `applicationId` 对应进程，也可手动选择其他进程或切换全局采集。
- 全局模式通过 Collector 的 AccessibilityService 获取公开的多 Window、系统界面及语义节点。
- 目标进程为 debuggable 时，插件可临时附加 JVMTI Agent，读取真实传统 View 根，包括 Activity、Dialog、PopupWindow 和直接 `WindowManager.addView()` 创建的根。
- 2D 截图叠加；3D 模式分别查看 Window layer 与单个 Window 内的 View depth；可只看单层。
- View 属性按身份、Window/层级、几何/布局、渲染/变换、内容、交互状态、无障碍、采集诊断分组。
- 布局越界和异常大图规则默认关闭，用户开启后才高亮并显示证据。

不支持 Compose、Root、安全策略绕过或 `FLAG_SECURE` 截图绕过。

## 构建与安装

环境要求：JDK 21、Android SDK 36、NDK `27.1.12297006`、CMake `3.22.1`。保证兼容基线为 Android Studio Quail 2 | 2026.1.2 Patch 1，设备最低 Android 9（API 28）。

```bash
./gradlew :plugin:buildPlugin
```

生成文件：`plugin/build/distributions/plugin-0.1.0.zip`。

在 Android Studio 中打开 **Settings / Preferences → Plugins → ⚙ → Install Plugin from Disk**，选择该 ZIP。安装并重启后，从 **Tools → Open UI Inspector** 或右侧 **UI Inspector** 工具窗口打开。

## 首次使用

1. 用 ADB 连接并授权设备。
2. 在插件工具窗口点“打开浏览器”。浏览器页面默认选中本工程应用进程。
3. 首次采集会自动安装独立 Collector APK。如果页面提示无障碍服务未开启，点工具窗口的“打开采集权限”，只需在设备上开启 **UI Inspector Collector**。
4. 选择“目标进程”或“全部应用”，点击“重新采集”。
5. 在左侧选 Window/View，中间查看 2D 或 3D 层级，右侧查看该对象属性。

Collector 权限必须由用户在设备上确认；插件不会自动修改安全设置。

## 仓库结构

- `plugin`：Android Studio 插件、本地回环 HTTP 服务和浏览器 UI。
- `collector`：插件自动安装的独立语义采集 APK。
- `agent-native` / `agent-helper`：仅对 debuggable 目标启用的临时 JVMTI 增强采集。
- `protocol`：桌面端与设备端共享的版本化 JSON 协议。
- `sample-target`：端到端验证 Activity、Dialog、PopupWindow、直接 WindowManager View、大图和安全窗口的调试样例。

更多边界见 [兼容性说明](docs/compatibility.md)，常见问题见 [故障排查](docs/troubleshooting.md)。

## 快速冒烟验证

macOS：

```bash
./scripts/smoke-test.sh [device-serial]
```

Windows PowerShell：

```powershell
.\scripts\smoke-test.ps1 -Serial <device-serial>
```

脚本会构建并安装 Collector 与样例，再校验协议 v1。若无障碍服务尚未开启，它只打开系统设置并提示用户操作。
