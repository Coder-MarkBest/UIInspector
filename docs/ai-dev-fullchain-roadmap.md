# 客户端 AI 开发全链路闭环路线图

> 目标：把 UIInspector 从“研发态 UI 采集/观测”升级为覆盖
> **需求 → 设计 → 研发 → 测试 → 验收** 全链路的客户端 AI 开发范式。
>
> 本文档是能力缺口分析与实施路线，不是最终设计；每一项落地前仍需单独出 spec。

## 0. 一句话定位

当前工具已经让 Agent “看得见”运行中的真实 UI（采集 + MCP 查询），
但闭环还差三层能力：

1. **可施加影响（Act）**：读之外还要能点、输入、滑动、触发 Activity / 广播。
2. **可断言（Assert）**：拿到树和截图后要能自动判“通过 / 不通过 / 与验收标准不符”。
3. **链路对象（Traceability）**：需求/验收标准要能落到可自动验证的断言，并能产出证据报告。

把这三点补齐，采集能力就从“眼睛”变成“验收引擎”。

## 1. 全链路能力总览

| 阶段 | 已有能力 | 主要缺口 | 优先级 |
|------|----------|----------|--------|
| 需求 | — | 需求/用例/验收标准结构化存储；需求⇄页面/控件/代码 traceability；AC→断言映射 | P1 形态，P2 沉淀 |
| 设计 | 截图+视图树+节点信息可作为对照基础 | 设计稿/规格自动对照（golden image / design token）；组件/ID/无障碍 schema 约束 | P2 |
| 研发 | Accessibility 树、JVMTI 自定义 View 快照、Window/进程/View 树、截图、语义定位、MCP 只读查询 | 可写/操作（tap/input/scroll/launch/broadcast）；代码⇄运行时 UI 映射；编译→部署→触发→抓快照一体化；场景路由/状态恢复 | **P0** |
| 测试 | 快照/多 Window/无障碍审计（半自动） | 断言框架；录制/回放/回归；快照版本化 diff；无障碍/适配性批量审计；性能/流畅度 trace 关联；CI 接入 | **P0** |
| 验收 | bug 平台拉取（本地 bug-monitor skill） | AC 自动核验+证据集；UI 采集→bug 证据回写→状态流转；人工确认流 | P1 |

## 2. 当前工具的定义（基线）

- `plugin`：Android Studio 插件、本地回环 HTTP 服务、浏览器 UI。
- `collector`：插件自动安装的独立语义采集 APK（AccessibilityService）。
- `agent-native` / `agent-helper`：仅对 debuggable 目标启用的临时 JVMTI 增强采集。
- `protocol`：桌面端与设备端共享的版本化 JSON 协议。
- `mcp`：给 Agent 的 MCP Server（进程/Window/视图树只读查询、语义定位）。
- `sample-target`：端到端验证样例（Activity、Dialog、PopupWindow、WindowManager View、大图、安全窗口）。

结论：**读的能力完整且可用；写、断言、链路对象三类能力基本缺位，是本次路线图的核心。**

## 3. 分层缺口与影响

### 3.1 需求层
缺：
- requirement / use case / acceptance criteria 的结构化存储（本地 YAML/JSON 即可起步）；
- 需求⇄界面（页面/控件）+⇄代码（layout/res/java)的 traceability；
- AC 到“可执行断言脚本”的映射。

### 3.2 设计层
缺：
- 设计稿/规格对照引擎（golden image 或 element-level 对齐）；
- design token / 组件规范 schema（字号、间距、颜色、content-desc 要求）；
- 运行期 vs 设计稿差异自动报告。

### 3.3 研发层
缺：
- 操作注入：tap / long-press / input text / swipe / back / home / rotate / toggle window / launch activity / send broadcast；
- 场景路由：进入指定页面、登录态、多进程组的统一“场景”；
- 代码⇄运行时：从代码/资源 id 定位到运行时树；从运行时节点定位到源码；
- 构建闭环：代码变更 → 编译安装 → 触发场景 → 采集 → 断言，一个动作完成。

### 3.4 测试层
缺：
- 断言原语：`assertNode(label/class/id)`、`assertText`、`assertRect`、`assertCount`、`assertAccessible`、`assertScreenshotDiff`；
- 录制/回放脚本格式（JSON/YAML 场景脚本）；
- 快照版本化与 diff：图、树、坐标、属性；
- 无障碍/适配性批量审计（content-desc 缺失、点击区域、越界、长文本省略、对比度）；
- 性能 trace 关联（掉帧/卡顿/解码耗时 ↔ snapshot）；
- CI / 夜间回归接入。

### 3.5 验收层
缺：
- AC 自动核验 + 证据集（截图+断言结果+时间戳）；
- 验收报告自动生成；
- 与 bug 平台双向回写（拉取 → 复现采集 → 贴证据 → 更新状态）；
- 人工确认流（Agent 给出“请确认这 3 处与需求不符”，由人来最终签核）。

## 4. 推荐落地顺序（依赖驱动）

### Phase 0：把“读”补成“可断言可操作”（价值最快，且被测试/验收复用）
1. 协议层增加操作指令（tap/input/scroll/launch/broadcast/back）。
2. MCP 增加 Act + Assert 工具（默认仍走进程模式）。
3. 断言原语 + 简单断言结果结构。
4. 快照 diff（基线 vs 当前）首个版本。

交付物：
- MCP 可从“查询工具”升级为“能验证一条 UI 需求”的测试工具。

### Phase 1：场景脚本 + 最小链路对象
1. 场景脚本格式（进入→操作→断言→证据）。
2. Requirement Manifest（需求/AC/脚本/标签 的最小 schema）。
3. 一个 CLI/AdHoc runner 执行 manifest 并产出“证据包”。
4. 人工确认流（pass / fail / review）。

交付物：
- 能在“找出所有无 content-desc 控件”延伸为“按 AC 逐条自动核验并出证据”。

### Phase 2：设计与回归闭环
1. golden image / element-level 视觉对照。
2. design token schema + 采集期校验。
3. 快照版本库 + 回归 diff 报告。
4. CI 接入（提交/夜间跑回归）。

### Phase 3：研发态一体化与代码映射
1. 构建→部署→触发→抓取→断言的一体化 Action（对接 IDE build）。
2. 代码⇄运行时 UI 映射（layout/res/java ⇄ 树节点）。
3. 场景路由与状态恢复。

### Phase 4：验收/项目全链路
1. AC 自动验收报告。
2. bug 平台双向回写。
3. 需求 traceability 可视化（需求⇄控件⇄脚本⇄证据）。

## 5. 优先级与收益

- **P0 = Phase 0 + 1**：把“测试”变成闭环的最小闭环，收益最高、改动可控、复用现有采集。
- **P1 = Phase 2 + 3**：让“设计/研发”也被纳入闭环。
- **P2 = Phase 4**：让“需求/验收”可追溯、可报告、可交接。

## 6. 风险与边界

- JVMTI 对非 debuggable / 用户 build / 部分 ROM 不可用 → Act/Assert 需同时准备“Accessibility + Input/ADB”回退路径。
- Compose、FLAG_SECURE、安全策略不做绕过（沿用兼容性边界）。
- 设计稿来源（Figma/切图/规范）格式不一 → 视觉对照先做“规格化输入”，避免直接啃复杂源文件。
- 车载/多屏/多进程场景需要明确的“场景路由”才能稳定复现，不能只靠裸采集。

## 7. 建议的第一步动作

1. `docs/ai-fullchain-minimal-spec.md`：把“场景脚本 + 断言原语 + Requirement Manifest”写成最小 spec。
2. MCP 增加两个动作：`drive()`（操作）与 `assert_*`（断言），默认进程模式。
3. 做一个最小跑通样例：一条 AC“首页所有可点击控件有 content-desc”→ 自动抓取 → 断言 → 出证据包。
