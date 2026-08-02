# Default Capture and Process List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically capture the running process from the current Android project when the inspector opens, while filtering obvious non-UI processes and prioritizing debuggable processes.

**Architecture:** Extend the existing Gradle application-ID detector to return all project application packages, enrich the ADB process catalog with one-shot JDWP debug status, and keep browser startup orchestration in pure, testable helpers. The existing capture endpoint remains the only collection path.

**Tech Stack:** Java 21, JUnit 5, ADB, vanilla JavaScript, Node test runner, IntelliJ Platform/Android Studio plugin.

## Global Constraints

- The inspected application requires no SDK or source-code changes.
- Secondary application processes remain visible because they may own `WindowManager.addView` Windows.
- Global capture and manual target selection remain available.
- No Compose-specific behavior, new UI controls, or new collection backends are added.
- Automatic capture runs at most once per newly opened browser page and is not retried.

---

### Task 1: Detect all current-project application packages

**Files:**
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/ui/ProjectApplicationIdDetector.java`
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/ui/InspectorProjectService.java`
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/server/InspectorApi.java`
- Test: `plugin/src/test/java/com/company/uiinspector/plugin/ui/ProjectApplicationIdDetectorTest.java`

**Interfaces:**
- Produces: `ProjectApplicationIdDetector.detectAll(Path): List<String>` in stable path order.
- Produces: session JSON field `projectPackages: string[]`; retains `defaultPackage` as the first value for compatibility.

- [ ] **Step 1: Write the failing detector test**

Add a test with two `com.android.application` modules and one library module. Assert that `detectAll(project)` returns only the two declared application IDs in sorted path order.

- [ ] **Step 2: Run the detector test and verify RED**

Run: `./gradlew :plugin:test --tests '*ProjectApplicationIdDetectorTest*'`

Expected: compilation fails because `detectAll` does not exist.

- [ ] **Step 3: Implement minimal multi-package detection**

Make `detect(Path)` delegate to `detectAll(Path).stream().findFirst()`. For each Gradle file, accept its `applicationId` only when the same file applies `com.android.application`; de-duplicate with encounter order.

- [ ] **Step 4: Expose packages through the session**

Pass the detected list from `InspectorProjectService` to `InspectorApi`. Emit:

```java
response.put("defaultPackage", projectPackages.isEmpty() ? null : projectPackages.get(0));
response.put("projectPackages", projectPackages);
```

Keep a string-based constructor overload for existing tests and callers.

- [ ] **Step 5: Run the focused and existing plugin tests**

Run: `./gradlew :plugin:test --tests '*ProjectApplicationIdDetectorTest*' --tests '*InspectorApiTest*'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/company/uiinspector/plugin/ui/ProjectApplicationIdDetector.java plugin/src/main/java/com/company/uiinspector/plugin/ui/InspectorProjectService.java plugin/src/main/java/com/company/uiinspector/plugin/server/InspectorApi.java plugin/src/test/java/com/company/uiinspector/plugin/ui/ProjectApplicationIdDetectorTest.java
git commit -m "feat: detect project application processes"
```

### Task 2: Filter and prioritize the process list

**Files:**
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/adb/AdbParsers.java`
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/adb/ProcessCatalog.java`
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/server/InspectorApi.java`
- Test: `plugin/src/test/java/com/company/uiinspector/plugin/adb/AdbParsersTest.java`
- Create: `plugin/src/test/java/com/company/uiinspector/plugin/adb/ProcessCatalogTest.java`

**Interfaces:**
- Produces: `AdbParsers.debuggablePids(String): Set<Integer>`.
- Produces: `ProcessCatalog.listForUi(String, List<String>): List<ProcessInfo>`.

- [ ] **Step 1: Write failing parser and catalog tests**

Assert that package-like main and secondary processes survive, while `init`, `system_server`, `surfaceflinger`, `webview_zygote`, and hardware-service names are removed. Feed JDWP output `410\n512\n` and assert those PIDs are marked debuggable. Assert ordering: project main, project secondary, other debuggable, then other application-like process.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./gradlew :plugin:test --tests '*AdbParsersTest*' --tests '*ProcessCatalogTest*'`

Expected: compilation fails because the new parser/catalog APIs do not exist.

- [ ] **Step 3: Implement package-like filtering and JDWP parsing**

Keep `AdbParsers.processes` as the raw `ps` parser. Add package validation against the base process name and parse one positive integer per JDWP line. In `ProcessCatalog.listForUi`, execute `ps -A` and `adb jdwp`, map results to `ProcessInfo`, and sort with deterministic comparators.

- [ ] **Step 4: Return the enriched list from `/api/processes`**

Change `InspectorApi.processes` to serialize `processes.listForUi(serial, projectPackages)`.

- [ ] **Step 5: Run focused and plugin tests**

Run: `./gradlew :plugin:test --tests '*AdbParsersTest*' --tests '*ProcessCatalogTest*' --tests '*InspectorApiTest*'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/com/company/uiinspector/plugin/adb/AdbParsers.java plugin/src/main/java/com/company/uiinspector/plugin/adb/ProcessCatalog.java plugin/src/main/java/com/company/uiinspector/plugin/server/InspectorApi.java plugin/src/test/java/com/company/uiinspector/plugin/adb/AdbParsersTest.java plugin/src/test/java/com/company/uiinspector/plugin/adb/ProcessCatalogTest.java
git commit -m "feat: prioritize inspectable Android processes"
```

### Task 3: Automatically capture the preferred project process once

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/test/js/inspector-core.test.mjs`

**Interfaces:**
- Produces: `preferredProjectProcess(processes, projectPackages): Process|null`.
- Produces: `shouldAutoCapture({ attempted, process }): boolean`.

- [ ] **Step 1: Write failing JavaScript tests**

Assert that the helper prefers an exact project main process over a project secondary process, returns the secondary process when no main process runs, returns `null` for unrelated processes, and permits auto-capture only when it has not been attempted and a preferred process exists.

- [ ] **Step 2: Run web tests and verify RED**

Run: `npm run test:web`

Expected: module import failure because the helpers are not exported.

- [ ] **Step 3: Implement the pure selection helpers**

Use exact package equality only. Do not infer project ownership from prefixes.

- [ ] **Step 4: Orchestrate one startup capture**

Store `projectPackages` and `autoCaptureAttempted` in page state. Make `fillDevices` and `loadProcesses` awaitable. During initialization only, select the preferred process, set `autoCaptureAttempted` before invoking `capture()`, and do not auto-capture after device or process changes.

- [ ] **Step 5: Run web tests**

Run: `npm run test:web`

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/resources/web/inspector-core.js plugin/src/main/resources/web/app.js plugin/src/test/js/inspector-core.test.mjs
git commit -m "feat: capture current project on inspector open"
```

### Task 4: Full verification and delivery

**Files:**
- Verify only.

**Interfaces:**
- Consumes all prior tasks.
- Produces a verified plugin ZIP and pushed GitHub branch.

- [ ] **Step 1: Run all automated verification**

Run: `npm run test:web`

Run: `./gradlew test assemble :plugin:buildPlugin :plugin:verifyPlugin`

Expected: both commands succeed and Plugin Verifier reports Compatible.

- [ ] **Step 2: Run browser smoke test**

Start the production local server against a connected device and open a fresh browser page. Verify a current-project process is selected and a snapshot appears without clicking `采集`; verify the process options exclude obvious native processes and list debuggable processes before non-debuggable options.

- [ ] **Step 3: Confirm repository integrity**

Run: `git diff --check && git status --short --branch`.

Expected: clean feature branch and the original worktree's unrelated `gradle.properties` change remains untouched.

- [ ] **Step 4: Push confirmed branches**

Push `main`, `codex/android-studio-ui-inspector`, and `codex/preview-selection-drag` to `Coder-MarkBest/UIInspector`. If HTTPS transport still fails, report the exact failure rather than adding account credentials or repository deploy keys without separate permission.
