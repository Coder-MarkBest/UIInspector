# Property Editor, Window View Planes, and Accessibility Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep property editing stable, render flat View overlays inside 3D Window layers, and report accessibility participation and missing accessible names from the current snapshot.

**Architecture:** Add small pure helpers to `inspector-core.js`, keep DOM orchestration in `app.js`, and normalize final accessibility importance in both Android capture paths. The right panel renders audit results from the current immutable snapshot and reuses existing View selection.

**Tech Stack:** Java 17, Android API 28+, JavaScript ES modules, Node test runner, HTML/CSS, Gradle IntelliJ Platform Plugin.

## Global Constraints

- No Compose support.
- No business SDK integration.
- No new capture backend or project-side dependency.
- View nodes inside a 3D Window are flat and do not receive separate Z depth.
- Accessibility analysis is read-only and never mutates the target application.

---

### Task 1: Preserve property group and scroll state

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/index.html`
- Test: `plugin/src/test/js/inspector-core.test.mjs`

**Interfaces:**
- Produces: `propertyGroupKey(targetId, groupName)`, `propertyGroupOpen(groupState, targetId, groupName)`, and `rememberPropertyGroupOpen(groupState, targetId, groupName, open)`.

- [ ] **Step 1: Write a failing test** asserting a lower `accessibility` group stays open and a default group can stay explicitly closed.
- [ ] **Step 2: Run `npm run test:web`** and confirm the imports fail because the group-state helpers do not exist.
- [ ] **Step 3: Implement the pure group-state helpers** with existing default groups `identity`, `geometryLayout`, and `windowHierarchy`.
- [ ] **Step 4: Update `renderProperties()`** to restore panel scroll, read stored group state, and record `<details>` toggle changes.
- [ ] **Step 5: Run `npm run test:web`** and confirm the suite passes.
- [ ] **Step 6: Commit** the test and implementation as `fix: preserve property editor group position`.

### Task 2: Draw Views on 3D Window planes

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/styles.css`
- Test: `plugin/src/test/js/inspector-core.test.mjs`

**Interfaces:**
- Produces: `buildWindowScene(snapshot, displayId)` returning ordered Windows with their own ordered node arrays, and `relativeBounds(bounds, containerBounds)` returning percentage geometry.
- Consumes: existing `buildWindowLayers()`, `buildViewDepths()`, and `selectNodeId()`.

- [ ] **Step 1: Write failing tests** proving nodes stay in their own Window and normalize to that Window's bounds.
- [ ] **Step 2: Run `npm run test:web`** and confirm the new scene helpers are missing.
- [ ] **Step 3: Implement the helpers** without changing View-depth mode.
- [ ] **Step 4: Render Window planes as containers** with flat View buttons, selected/finding states, and stopped click propagation.
- [ ] **Step 5: Run `npm run test:web`** and confirm all browser logic tests pass.
- [ ] **Step 6: Commit** as `feat: render views on 3d window planes`.

### Task 3: Normalize accessibility importance at capture time

**Files:**
- Modify: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewCaptureBridge.java`
- Modify: `collector/src/main/java/com/company/uiinspector/collector/AndroidAccessibilityAdapters.java`
- Test: `agent-helper/src/test/java/com/company/uiinspector/agent/ViewCaptureBridgeTest.java`
- Test: `collector/src/test/java/com/company/uiinspector/collector/AccessibilitySnapshotCollectorTest.java`

**Interfaces:**
- Produces snapshot property `importantForAccessibilityResolved` with string boolean values.

- [ ] **Step 1: Add failing Android tests** expecting `importantForAccessibilityResolved` from both capture paths.
- [ ] **Step 2: Run `./gradlew :agent-helper:test :collector:test`** and confirm both assertions fail because the property is absent.
- [ ] **Step 3: Add the resolved property** using `View.isImportantForAccessibility()` and `AccessibilityNodeInfo.isImportantForAccessibility()`.
- [ ] **Step 4: Run the focused Gradle tests** and confirm they pass.
- [ ] **Step 5: Commit** as `feat: normalize accessibility importance`.

### Task 4: Audit and present accessibility status

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/index.html`
- Modify: `plugin/src/main/resources/web/styles.css`
- Test: `plugin/src/test/js/inspector-core.test.mjs`

**Interfaces:**
- Produces: `auditAccessibility(snapshot)` returning `{ enabled, disabled, issues }` arrays of result records containing `nodeId`, `status`, `reason`, and `missingProperties`.
- Consumes: `flattenProperties()` and existing `selectNodeId(id, true)`.

- [ ] **Step 1: Write failing tests** for a labeled enabled node, an explicitly disabled node, and an interactive enabled node missing an accessible name.
- [ ] **Step 2: Run `npm run test:web`** and confirm `auditAccessibility` is missing.
- [ ] **Step 3: Implement classification and naming rules** with resolved, semantic boolean, raw Android constant, and evidence fallbacks.
- [ ] **Step 4: Add the audit section** with three count filters and clickable results that reveal the corresponding View.
- [ ] **Step 5: Run `npm run test:web`** and confirm all tests pass.
- [ ] **Step 6: Commit** as `feat: add accessibility view audit`.

### Task 5: End-to-end verification

**Files:**
- Verify: `plugin/build/distributions/plugin-0.1.0.zip`

**Interfaces:**
- Consumes all prior tasks; produces the verified plugin artifact.

- [ ] **Step 1: Run `npm run test:web`** and require zero failures.
- [ ] **Step 2: Run `./gradlew test assemble :plugin:buildPlugin :plugin:verifyPlugin`** and require `BUILD SUCCESSFUL` plus `Compatible`.
- [ ] **Step 3: Start the production browser harness** and capture a fresh snapshot.
- [ ] **Step 4: Verify lower-group edit stability** by scrolling to an editable property, opening edit mode, and checking the same group and row remain visible.
- [ ] **Step 5: Verify 3D Window View selection** by selecting a View rectangle and checking tree/property synchronization.
- [ ] **Step 6: Verify accessibility filters** show enabled, disabled, and missing-configuration results and that result selection reveals the View.
- [ ] **Step 7: Commit any test-only corrections**, then confirm `git status --short` is clean.

## Plan self-review

Every design requirement maps to one task. Interfaces use the same function and property names throughout. The plan contains no deferred implementation placeholders and keeps capture normalization separate from browser analysis.
