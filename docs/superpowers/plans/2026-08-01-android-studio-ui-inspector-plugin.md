# Android Studio UI Inspector Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a no-business-integration Android Studio plugin that installs a Collector APK, captures global semantic UI and debuggable-process View trees, and presents Window/View hierarchy in the approved browser UI.

**Architecture:** A pure Java protocol module defines versioned snapshots. An Android Collector exports shell-only accessibility snapshots, an optional JVMTI bootstrap loads a View capture helper into debuggable processes, and an IntelliJ Platform plugin orchestrates ADB plus a loopback web server. A dependency-free browser frontend renders the approved three-column 2D/3D interaction.

**Tech Stack:** Gradle 9, Java 21 plugin runtime/bytecode, Java 17 Android bytecode, Android Gradle Plugin 9.0.1, compileSdk 36, minSdk 28, NDK 27.1.12297006, CMake 3.22.1, IntelliJ Platform Gradle Plugin 2.18.1, JUnit 5/4, Gson, vanilla HTML/CSS/JavaScript.

## Global Constraints

- Device minimum is Android 9 (API 28).
- Guaranteed IDE baseline is Android Studio Quail 2 | 2026.1.2 Patch 1.
- Desktop support is macOS and Windows; Linux is not required.
- No target project source, Gradle, Manifest, or SDK integration is allowed.
- No Compose inspection, Root, security-policy modification, or `FLAG_SECURE` bypass.
- Global scope defaults to Collector accessibility data; target scope prefers JVMTI only for debuggable processes and otherwise degrades visibly.
- Window layer and View depth remain separate visual modes.
- Anomaly rules are disabled by default.

---

### Task 1: Build scaffold and protocol contracts

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `protocol/build.gradle.kts`
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/*.java`
- Test: `protocol/src/test/java/com/company/uiinspector/protocol/SnapshotJsonTest.java`

**Interfaces:**
- Produces: `InspectionSnapshot`, `WindowSnapshot`, `UiNode`, `PropertyGroup`, `Diagnostic`, `ProcessInfo`, `SnapshotJson.encode/decode`.

- [x] Write a failing JSON round-trip test using a literal snapshot containing a parent Window, child Window, one View node, grouped properties, diagnostics, and no screenshot.
- [x] Run `./gradlew :protocol:test --tests '*SnapshotJsonTest'` and confirm missing protocol classes are the failure.
- [x] Implement immutable Java records/classes and strict schema-version validation; no Android type may appear in the protocol.
- [x] Run the protocol tests and commit `build: scaffold plugin protocol`.

### Task 2: Collector APK and semantic Window/View capture

**Files:**
- Create: `collector/build.gradle.kts`, `collector/src/main/AndroidManifest.xml`
- Create: `collector/src/main/res/xml/accessibility_service_config.xml`
- Create: `collector/src/main/java/com/company/uiinspector/collector/CollectorActivity.java`
- Create: `collector/src/main/java/com/company/uiinspector/collector/UiInspectorAccessibilityService.java`
- Create: `collector/src/main/java/com/company/uiinspector/collector/AccessibilitySnapshotCollector.java`
- Create: `collector/src/main/java/com/company/uiinspector/collector/SnapshotProvider.java`
- Test: `collector/src/test/java/com/company/uiinspector/collector/AccessibilitySnapshotCollectorTest.java`

**Interfaces:**
- Consumes: Android accessibility windows/nodes and query parameters `scope`, `package`, `maxNodes`.
- Produces: `content://com.company.uiinspector.collector/snapshot` streamed JSON and setup status.

- [x] Write failing tests for multiple Windows sorted by layer, explicit Window parent/child IDs, View parent/child depth, package filtering, node truncation, and property groups.
- [x] Run `./gradlew :collector:testDebugUnitTest` and confirm the collector is missing.
- [x] Implement the service with `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, `FLAG_REPORT_VIEW_IDS`, and `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`; use all displays on API 30+.
- [x] Implement a shell-only streaming provider and setup Activity that opens Accessibility settings.
- [x] Run unit tests plus `:collector:assembleDebug`; commit `feat: collect global accessibility windows`.

### Task 3: ADB orchestration and snapshot merge core

**Files:**
- Create: `plugin/build.gradle.kts`, `plugin/src/main/resources/META-INF/plugin.xml`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/adb/*.java`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/capture/*.java`
- Test: `plugin/src/test/java/com/company/uiinspector/plugin/adb/*.java`
- Test: `plugin/src/test/java/com/company/uiinspector/plugin/capture/*.java`

**Interfaces:**
- Produces: `AdbLocator`, `AdbClient`, `Device`, `ProcessCatalog`, `CollectorManager`, `SemanticCapture`, `CaptureCoordinator`.

- [x] Write failing parser tests using literal outputs from `adb devices -l`, `ps -A`, `dumpsys package`, provider JSON, and stderr failure cases.
- [x] Run `./gradlew :plugin:test` and confirm missing classes fail.
- [x] Implement `ProcessBuilder` execution with argument arrays, timeouts, UTF-8 output, binary stdout support, and cancellation.
- [x] Implement Collector install/version checks, settings launch, semantic capture, screencap, package/process mapping, and visible fallback diagnostics.
- [x] Run plugin core tests; commit `feat: orchestrate collector over adb`.

### Task 4: Debuggable-process JVMTI View capture

**Files:**
- Create: `agent-helper/build.gradle.kts`, `agent-helper/src/main/AndroidManifest.xml`
- Create: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewCaptureBridge.java`
- Create: `agent-native/build.gradle.kts`, `agent-native/src/main/AndroidManifest.xml`
- Create: `agent-native/src/main/cpp/CMakeLists.txt`, `agent-native/src/main/cpp/agent.cpp`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/capture/AgentDeployer.java`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/capture/EnhancedCapture.java`
- Test: `agent-helper/src/test/java/com/company/uiinspector/agent/ViewCaptureBridgeTest.java`
- Test: `plugin/src/test/java/com/company/uiinspector/plugin/capture/AgentDeployerTest.java`

**Interfaces:**
- Consumes: selected process, target package, ABI, helper APK and Agent AAR resources.
- Produces: enhanced `InspectionSnapshot` file in target `code_cache`, or a typed degradation diagnostic.

- [x] Write failing tests for real nested View property extraction, root Window params, `WindowManager.addView()` as a separate root, ABI selection, debuggable/run-as checks, deploy command arguments, and secure cleanup.
- [x] Run helper/plugin tests and confirm missing implementation failures.
- [x] Implement helper main-thread capture with bounded traversal and the eight approved property groups.
- [x] Implement `Agent_OnAttach`: obtain `WindowManagerGlobal` roots/Window params with JNI, load helper APK via `DexClassLoader`, and invoke capture without modifying app classes.
- [x] Implement deploy, `cmd activity attach-agent`, bounded wait/pull, parse, and cleanup; never attempt attach after a debuggable/run-as failure.
- [x] Run helper/native/plugin builds and tests; commit `feat: capture debuggable View roots with JVMTI`.

### Task 5: Loopback server and Android Studio integration

**Files:**
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/server/*.java`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/ui/InspectorToolWindowFactory.java`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/ui/InspectorPanel.java`
- Create: `plugin/src/main/java/com/company/uiinspector/plugin/actions/OpenInspectorAction.java`
- Test: `plugin/src/test/java/com/company/uiinspector/plugin/server/InspectorServerTest.java`

**Interfaces:**
- Produces: loopback session API `/api/session`, `/api/devices`, `/api/processes`, `/api/capture`, `/api/status`; IDE tool window and menu action.

- [x] Write failing HTTP tests for loopback binding, token rejection, Origin rejection, static resource MIME types, capture response, and closed-session behavior.
- [x] Run `./gradlew :plugin:test --tests '*InspectorServerTest'` and confirm failures.
- [x] Implement the JDK HTTP server, in-memory session, temp-file lifecycle, and asynchronous capture state.
- [x] Implement the tool window with device/process/scope controls, Collector status, capture/open-browser actions, and actionable errors.
- [x] Run tests and `:plugin:buildPlugin`; commit `feat: add Android Studio inspector session`.

### Task 6: Approved browser tree, 2D/3D, properties, and rules

**Files:**
- Create: `plugin/src/main/resources/web/index.html`
- Create: `plugin/src/main/resources/web/styles.css`
- Create: `plugin/src/main/resources/web/app.js`
- Create: `plugin/src/main/resources/web/inspector-core.js`
- Create: `plugin/src/test/java/com/company/uiinspector/plugin/web/WebAssetContractTest.java`
- Create: `plugin/src/test/js/inspector-core.test.mjs`

**Interfaces:**
- Consumes: protocol v1 JSON and same-origin session APIs.
- Produces: approved horizontal three-column inspector and pure functions `buildWindowLayers`, `buildViewDepths`, `filterDepth`, `evaluateRules`, `groupProperties`.

- [x] Write failing JavaScript tests for Window parent vs layer separation, per-Window View depth, single-depth filtering, selection synchronization, property group order, default-off rules, oversized layout, and oversized image evidence.
- [x] Run Node tests and confirm the module is missing.
- [x] Implement pure frontend state and rule functions, then make tests pass.
- [x] Implement the approved UI: Window-first tree, 2D overlay, separate Window/View CSS-3D stages, depth/layer slider, right grouped properties, scope/process controls, and optional anomaly list.
- [x] Add responsive fallback, keyboard focus, reduced motion, empty/loading/error states; run browser regression screenshots at desktop widths.
- [x] Run JS, plugin resource, and HTTP tests; commit `feat: add browser hierarchy inspector`.

### Task 7: End-to-end sample and delivery verification

**Files:**
- Create: `sample-target/build.gradle.kts`, `sample-target/src/main/AndroidManifest.xml`
- Create: `sample-target/src/main/java/com/company/uiinspector/sample/MainActivity.java`
- Create: `sample-target/src/main/res/layout/activity_main.xml`
- Create: `README.md`, `docs/compatibility.md`, `docs/troubleshooting.md`
- Create: `scripts/smoke-test.sh`, `scripts/smoke-test.ps1`

**Interfaces:**
- Produces: a debuggable fixture with Activity, Dialog, PopupWindow, direct `WindowManager.addView()`, nested Views, image rule fixture, and secure Window fixture.

- [x] Write Android tests proving the sample creates each requested Window/root scenario.
- [x] Implement only those fixtures and run `:sample-target:connectedDebugAndroidTest` when an emulator/device is available.
- [x] Add platform scripts that build, install, enable/guide Collector, launch sample, capture, and validate protocol v1 without requiring business changes.
- [x] Document install, first-run permission, target/global scopes, fidelity badges, API 28/multi-display limits, `FLAG_SECURE`, and uninstall/cleanup.
- [x] Run `./gradlew clean test assemble buildPlugin`, native builds, Node tests, plugin verifier, and shell/PowerShell syntax checks.
- [x] Inspect the final plugin ZIP, Collector APK, helper APK, Agent AAR/ABIs, screenshots, git diff, and requirement checklist; commit `docs: finish UI Inspector delivery`.
