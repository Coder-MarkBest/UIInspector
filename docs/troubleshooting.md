# 故障排查

## 页面找不到设备

运行 `adb devices -l`，确认设备状态为 `device`，不是 `unauthorized` 或 `offline`。若项目 SDK 未配置，请设置 `ANDROID_HOME`，或确保 `adb` 位于 `PATH`。

## 提示开启无障碍服务

首次采集只会安装 Collector，不会替用户修改系统安全设置。在 Android Studio 工具窗口点“打开采集权限”，然后在设备无障碍设置中开启 **UI Inspector Collector**。

车机系统可能修改或隐藏标准无障碍设置入口；这属于系统能力限制，需要设备系统提供等价的用户授权入口。

## 目标进程列表没有应用

先启动目标应用，再刷新浏览器页面或重新选择设备。默认进程来自当前工程可解析到的 `applicationId`；多 flavor、动态脚本或自定义构建逻辑无法静态解析时需要手动选择。

## 只看到 Semantic，没有 Real View

确认目标 APK 是 debuggable，进程仍在运行，并允许 `run-as <package>`。插件不会对非 debuggable 进程强行附加 Agent。查看右侧“采集诊断”可看到 ABI、`run-as`、attach 或等待快照失败的具体原因。

## Dialog、PopupWindow 或 WindowManager View 未出现

- 全局 Semantic 模式只显示系统实际公开给 AccessibilityService 的 Window/节点。
- 对 debuggable 目标使用“目标进程”模式，JVMTI 会从 `WindowManagerGlobal` 读取独立根。
- 确认相关窗口在采集瞬间仍然显示；关闭后重新采集不会保留历史状态。

## 截图为空或黑色

目标可能使用 `FLAG_SECURE`、DRM 或厂商隐私保护。本工具不绕过这些策略。层级树和公开属性可能仍可查看。

## 浏览器提示会话无效

本地服务随 Android Studio 工程生命周期关闭，URL 还包含一次性随机令牌。回到工具窗口重新点击“打开浏览器”，不要复用旧地址。

## 完全卸载

从 Android Studio 插件设置卸载插件，并在设备上执行：

```bash
adb uninstall com.company.uiinspector.collector
adb uninstall com.company.uiinspector.sample
```

JVMTI 帮助文件只临时写入 debuggable 目标的 `code_cache/uiinspector`，每次采集后会尽力清理，系统也会随应用数据清理它们。
