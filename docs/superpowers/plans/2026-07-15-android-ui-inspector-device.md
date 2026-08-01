# Android UI Inspector Device Implementation Plan（历史方案）

> 本计划描述早期 SDK 集成路径，已被 `2026-08-01-android-studio-ui-inspector-plugin.md` 取代，不再执行。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a debug-only Android SDK that inspects all traditional View windows in its host process by default and can optionally inspect other apps through Accessibility, with efficient on-device picking, highlighting, hierarchy browsing, and report export.

**Architecture:** A stable `ui-inspector-api` module defines registration and snapshot contracts; `ui-inspector-runtime` implements the process-local registry, collectors, hit testing, overlays, device UI, Accessibility mode, screenshots, and reports; `ui-inspector-noop` keeps release call sites safe; and `ui-inspector-gradle-plugin` instruments debug variants to discover Dialog, PopupWindow, and WindowManager roots. Every collector produces immutable versioned snapshots so a future Android Studio plugin can reuse the format without adding desktop transport now.

**Tech Stack:** Kotlin 2.3.x, Android Gradle Plugin 8.13.2 for the repository build, Gradle 8.13, JDK 17, minSdk 28, compileSdk/targetSdk 36, Android Views/XML, Kotlin coroutines, kotlinx.serialization JSON, ASM through the public AGP instrumentation API, JUnit 4, Robolectric, AndroidX Test, and Gradle TestKit.

## Global Constraints

- Minimum Android version is API 28.
- Inspect traditional Android View only; do not add Compose dependencies or Compose inspection.
- Default to the SDK host process and require no Accessibility permission for that mode.
- Global App mode is opt-in and uses Accessibility nodes, not a claimed real View tree.
- Cover Activity, Dialog, PopupWindow, direct `WindowManager.addView()`, and multiple simultaneous window roots.
- Provide both an AGP 8.x debug-only instrumentation plugin and stable wrapper/manual registration APIs.
- API 28–29 global reports may omit screenshots.
- Do not implement desktop UI, Android Studio plugin, ADB bridge, sockets, or remote control.
- Never rely on hidden APIs for required behavior; experimental `WindowManagerGlobal` reflection remains out of the first implementation.
- Release artifacts must contain no Accessibility service, runtime inspector, overlay, or debug entry point.
- From floating control to selected View requires at most two taps.
- On the benchmark fixture with at most 2,000 nodes: picker ready within 150ms, selection feedback within 300ms, and selection switching within 100ms.

## File and Module Map

```text
UIInspector/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── ui-inspector-api/
│   └── src/main/kotlin/com/company/uiinspector/api/
│       ├── UiInspector.kt
│       ├── InspectorConfig.kt
│       ├── RootRegistration.kt
│       ├── RootMetadata.kt
│       └── snapshot/
│           ├── GeometrySnapshot.kt
│           ├── UiNodeSnapshot.kt
│           ├── WindowRootSnapshot.kt
│           └── InspectionSnapshot.kt
├── ui-inspector-runtime/
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/kotlin/com/company/uiinspector/runtime/
│   │   ├── UiInspectorRuntime.kt
│   │   ├── registry/RootViewRegistry.kt
│   │   ├── registry/DefaultRootViewRegistry.kt
│   │   ├── tracking/ActivityWindowTracker.kt
│   │   ├── tracking/TrackingWindowManager.kt
│   │   ├── collect/ViewTreeCollector.kt
│   │   ├── collect/AccessibilityTreeCollector.kt
│   │   ├── hit/HitTestEngine.kt
│   │   ├── overlay/PickerOverlayController.kt
│   │   ├── overlay/HighlightOverlayController.kt
│   │   ├── accessibility/InspectorAccessibilityService.kt
│   │   ├── screenshot/ScreenshotProvider.kt
│   │   ├── report/InspectionReportWriter.kt
│   │   └── ui/InspectorActivity.kt
│   └── src/main/res/
│       ├── layout/activity_ui_inspector.xml
│       ├── layout/view_inspector_floating_panel.xml
│       ├── xml/ui_inspector_accessibility_service.xml
│       └── values/strings.xml
├── ui-inspector-noop/
│   └── src/main/kotlin/com/company/uiinspector/api/NoopUiInspectorDelegate.kt
├── ui-inspector-gradle-plugin/
│   └── src/main/kotlin/com/company/uiinspector/plugin/
│       ├── UiInspectorPlugin.kt
│       ├── UiInspectorExtension.kt
│       ├── InspectorClassVisitorFactory.kt
│       └── WindowCallClassVisitor.kt
└── sample-app/
    └── src/main/kotlin/com/company/uiinspector/sample/
        ├── MainActivity.kt
        └── WindowScenarioController.kt
```

---

### Task 1: Scaffold the Android multi-module build and a View-only sample app

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `ui-inspector-api/build.gradle.kts`
- Create: `ui-inspector-runtime/build.gradle.kts`
- Create: `ui-inspector-noop/build.gradle.kts`
- Create: `ui-inspector-gradle-plugin/build.gradle.kts`
- Create: `sample-app/build.gradle.kts`
- Create: `sample-app/src/main/AndroidManifest.xml`
- Create: `sample-app/src/main/kotlin/com/company/uiinspector/sample/MainActivity.kt`
- Create: `sample-app/src/main/res/layout/activity_main.xml`
- Test: `sample-app/src/test/kotlin/com/company/uiinspector/sample/MainActivityTest.kt`

**Interfaces:**
- Consumes: None.
- Produces: A buildable JDK 17/Gradle 8.13/AGP 8.13.2 project with Android library modules, one Gradle plugin module, and a minSdk 28 sample app.

- [ ] **Step 1: Add the settings and version catalog**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "UIInspector"
include(":ui-inspector-api", ":ui-inspector-runtime", ":ui-inspector-noop")
include(":ui-inspector-gradle-plugin", ":sample-app")
```

Pin `agp = "8.13.2"`, `gradle = "8.13"`, `kotlin = "2.3.20"`, `compileSdk = "36"`, `minSdk = "28"`, `junit = "4.13.2"`, and `serialization = "1.9.0"` in `gradle/libs.versions.toml`. Configure all Kotlin/Java modules with toolchain 17.

- [ ] **Step 2: Create the module build files**

Use `com.android.library` for API/runtime/noop, `com.android.application` for sample, and `java-gradle-plugin` plus `org.jetbrains.kotlin.jvm` for the Gradle plugin. Runtime and noop depend on API. Sample uses `implementation(project(":ui-inspector-api"))`, `debugImplementation(project(":ui-inspector-runtime"))`, and `releaseImplementation(project(":ui-inspector-noop"))`. Keep Compose disabled in every Android module.

- [ ] **Step 3: Write a failing sample smoke test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test fun launchesWithInspectorEntry() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<View>(R.id.openInspector))
    }
}
```

- [ ] **Step 4: Run the test and verify it fails before the layout exists**

Run: `./gradlew :sample-app:testDebugUnitTest --tests '*MainActivityTest'`

Expected: FAIL because `R.id.openInspector` or the activity layout is missing.

- [ ] **Step 5: Implement the minimal XML Activity**

Create a vertical `LinearLayout` containing buttons with IDs `openInspector`, `showDialog`, `showPopup`, and `showWindowManagerView`. `MainActivity` calls `setContentView(R.layout.activity_main)` without Compose.

- [ ] **Step 6: Verify the scaffold**

Run: `./gradlew :sample-app:testDebugUnitTest :sample-app:assembleDebug :sample-app:assembleRelease`

Expected: BUILD SUCCESSFUL and the smoke test passes.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle ui-inspector-api ui-inspector-runtime ui-inspector-noop ui-inspector-gradle-plugin sample-app
git commit -m "build: scaffold UI Inspector modules"
```

### Task 2: Define the stable public API and versioned snapshot format

**Files:**
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/UiInspector.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/InspectorConfig.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/RootMetadata.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/RootRegistration.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/snapshot/GeometrySnapshot.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/snapshot/UiNodeSnapshot.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/snapshot/WindowRootSnapshot.kt`
- Create: `ui-inspector-api/src/main/kotlin/com/company/uiinspector/api/snapshot/InspectionSnapshot.kt`
- Test: `ui-inspector-api/src/test/kotlin/com/company/uiinspector/api/snapshot/InspectionSnapshotTest.kt`

**Interfaces:**
- Consumes: Android `View`, `WindowManager`, and serializable primitive snapshot types.
- Produces: `UiInspector.install(Application, InspectorConfig)`, `UiInspector.registerRoot(View, RootMetadata): RootRegistration`, `UiInspector.registerRootUntilDetached(View, RootMetadata): RootRegistration`, `UiInspector.wrap(WindowManager): WindowManager`, and `InspectionSnapshot` with `schemaVersion = 1`.

- [ ] **Step 1: Write the failing JSON round-trip test**

```kotlin
class InspectionSnapshotTest {
    @Test fun roundTripsSchemaVersionAndNullableScreenshot() {
        val snapshot = InspectionSnapshot.empty(
            packageName = "com.example",
            processName = "com.example"
        )
        val json = InspectorJson.encode(snapshot)
        val decoded = InspectorJson.decode(json)
        assertEquals(1, decoded.schemaVersion)
        assertNull(decoded.screenshot)
        assertEquals("com.example", decoded.packageName)
    }
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew :ui-inspector-api:testDebugUnitTest --tests '*InspectionSnapshotTest'`

Expected: FAIL because snapshot types and `InspectorJson` do not exist.

- [ ] **Step 3: Implement immutable snapshot types**

Use `@Serializable` types. Represent rectangles as four integers, flags as a serializable data class, and arbitrary properties as `Map<String, String>`. Do not place Android `Rect`, `View`, `Bitmap`, `Parcelable`, or `AccessibilityNodeInfo` in exported snapshot types.

```kotlin
@Serializable
data class InspectionSnapshot(
    val schemaVersion: Int = 1,
    val capturedAtEpochMs: Long,
    val packageName: String,
    val processName: String?,
    val source: InspectionSource,
    val windows: List<WindowRootSnapshot>,
    val nodes: Map<String, UiNodeSnapshot>,
    val selectedNodeId: String?,
    val screenshot: ScreenshotSnapshot?
)
```

- [ ] **Step 4: Implement the public facade as a runtime delegate**

`UiInspector` stores a volatile `UiInspectorDelegate` installed by runtime. Before installation, every method is safe and returns a closed/no-op registration. This allows the release module to share source-level API behavior without retaining Views.

- [ ] **Step 5: Verify API tests**

Run: `./gradlew :ui-inspector-api:testDebugUnitTest`

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-api
git commit -m "feat: define UI Inspector API and snapshots"
```

### Task 3: Implement the process-local RootViewRegistry

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/registry/RootViewRegistry.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/registry/DefaultRootViewRegistry.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/registry/RootRecord.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/registry/DefaultRootViewRegistryTest.kt`

**Interfaces:**
- Consumes: `View`, `RootMetadata`, and `DiscoverySource`.
- Produces: `register(view, source, metadata): RootRegistration`, `update(view, layoutParams)`, `unregister(view, source)`, and `snapshot(): List<RegisteredRoot>`.

- [ ] **Step 1: Write failing tests for identity deduplication and source counting**

```kotlin
@RunWith(RobolectricTestRunner::class)
class DefaultRootViewRegistryTest {
    @Test fun sameViewFromTwoSourcesProducesOneRootUntilBothClose() {
        val view = View(ApplicationProvider.getApplicationContext())
        val registry = DefaultRootViewRegistry(MainThreadChecker.immediate())
        val first = registry.register(view, DiscoverySource.MANUAL, RootMetadata.custom("one"))
        val second = registry.register(view, DiscoverySource.INSTRUMENTATION, RootMetadata.custom("two"))
        assertEquals(1, registry.snapshot().size)
        first.close()
        assertEquals(1, registry.snapshot().size)
        second.close()
        assertTrue(registry.snapshot().isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest --tests '*DefaultRootViewRegistryTest'`

Expected: FAIL because registry classes do not exist.

- [ ] **Step 3: Implement a main-thread-confined identity registry**

Use `IdentityHashMap<View, RootRecord>`. A `RootRecord` contains `WeakReference<View>`, an `EnumMap<DiscoverySource, Int>`, current metadata, add sequence, and the last `WindowManager.LayoutParams` snapshot. Reject off-main mutations in debug tests; production entry points post to main.

- [ ] **Step 4: Add detach cleanup and closed-handle idempotency tests**

Test that calling `close()` twice does not underflow source counts and that `registerRootUntilDetached` removes its source on detach.

- [ ] **Step 5: Verify registry tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest --tests '*registry*'`

Expected: all registry tests PASS.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/registry ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/registry
git commit -m "feat: add process root registry"
```

### Task 4: Track Activity roots and provide wrapper/manual registration

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/UiInspectorRuntime.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/tracking/ActivityWindowTracker.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/tracking/TrackingWindowManager.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/tracking/ActivityWindowTrackerTest.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/tracking/TrackingWindowManagerTest.kt`

**Interfaces:**
- Consumes: `RootViewRegistry` from Task 3.
- Produces: runtime installation, lifecycle-driven Activity root registration, and a complete `WindowManager` delegate that tracks add/update/remove operations.

- [ ] **Step 1: Write failing Activity lifecycle tests**

Test two resumed Activities, destroying one, and verify the other root remains. Verify transparent/multi-window cases do not overwrite a single global `currentActivity` slot.

- [ ] **Step 2: Write failing WindowManager delegate tests**

Use a fake `WindowManager` that records calls. Verify `addView` reaches the delegate before registry registration, a delegate exception does not register a root, `updateViewLayout` updates metadata, and both remove methods unregister only after delegate success.

- [ ] **Step 3: Implement trackers**

`ActivityWindowTracker` implements `Application.ActivityLifecycleCallbacks` and keeps registrations keyed by Activity identity. `TrackingWindowManager` delegates `getDefaultDisplay`, `addView`, `updateViewLayout`, `removeView`, and `removeViewImmediate` without changing behavior.

- [ ] **Step 4: Connect the public facade**

`UiInspectorRuntime.install(application, config)` installs one process singleton, records `application.packageName` and `Application.getProcessName()`, starts Activity tracking, and installs the API delegate. Repeated installation returns the existing runtime.

- [ ] **Step 5: Run tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest --tests '*tracking*'`

Expected: all lifecycle and delegate tests PASS.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/UiInspectorRuntime.kt ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/tracking ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/tracking
git commit -m "feat: track activity and WindowManager roots"
```

### Task 5: Collect real View trees into immutable snapshots

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/collect/ViewPropertyReader.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/collect/ViewTreeCollector.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/collect/WindowSnapshotFactory.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/collect/ViewTreeCollectorTest.kt`

**Interfaces:**
- Consumes: `List<RegisteredRoot>`.
- Produces: `ViewTreeCollector.collect(roots, limits): InspectionSnapshot` with stable node IDs scoped to one capture.

- [ ] **Step 1: Write a failing nested View test**

Build a Robolectric hierarchy with duplicate IDs, translated children, invisible nodes, margins, padding, alpha, clickable state, and content descriptions. Assert parent IDs, child order, screen bounds, resource names, and flags.

- [ ] **Step 2: Write failing limit tests**

Set `maxNodes = 3` and `maxDepth = 2`; assert `truncated = true`, selected ancestry can still be retained, and collection terminates deterministically.

- [ ] **Step 3: Implement main-thread reading and immutable conversion**

Read only a property whitelist: class, resource ID, text for `TextView`, content description, visibility, shown, alpha, enabled, selected, activated, clickable, long-clickable, focusable, focused, scrollability, padding, layout params, elevation, translation, scale, rotation, bounds, visible rect, and accessibility importance. Catch individual property failures and record them in diagnostics instead of aborting the capture.

- [ ] **Step 4: Move serialization work off main**

The collector returns immutable Kotlin data after View reads. JSON encoding, report writing, and formatting occur on `Dispatchers.Default` or `Dispatchers.IO`, never while holding View references.

- [ ] **Step 5: Verify collector tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest --tests '*ViewTreeCollectorTest'`

Expected: all collector and limit tests PASS.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/collect ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/collect
git commit -m "feat: collect immutable View snapshots"
```

### Task 6: Implement layered window and node hit testing

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/hit/HitCandidate.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/hit/HitTestEngine.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/hit/HitTestEngineTest.kt`

**Interfaces:**
- Consumes: `InspectionSnapshot`, display ID, and screen x/y.
- Produces: ordered `HitResult(primaryNodeId, nodeCandidates, windowCandidates)` plus deterministic parent/child/overlap navigation.

- [ ] **Step 1: Write failing overlap and visibility tests**

Test two roots with intersecting bounds, one added later; children with the same bounds; an alpha-zero node; clipped children; and Inspector-owned roots. Assert the visible, specific, topmost non-Inspector candidate wins.

- [ ] **Step 2: Write failing navigation tests**

Assert `selectParent`, `selectDeepestChildAtPoint`, `selectNextOverlap`, and `selectNextWindow` wrap predictably and never return an ID absent from the snapshot.

- [ ] **Step 3: Implement scoring and stable tie-breakers**

Use window layer hint, add sequence, visible containment, View visibility, area, depth, semantic value, and interaction flags. Keep the scoring function pure and document numeric weights in one `HitScorePolicy` object.

- [ ] **Step 4: Verify hit-test tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest --tests '*HitTestEngineTest'`

Expected: all hit-testing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/hit ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/hit
git commit -m "feat: add layered UI hit testing"
```

### Task 7: Build the on-device picker, highlighter, and compact panel

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/overlay/InspectorOverlayTag.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/overlay/FloatingControlController.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/overlay/PickerOverlayController.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/overlay/HighlightOverlayController.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/overlay/FloatingPanelController.kt`
- Create: `ui-inspector-runtime/src/main/res/layout/view_inspector_floating_panel.xml`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/overlay/PickerOverlayControllerTest.kt`
- Test: `ui-inspector-runtime/src/androidTest/kotlin/com/company/uiinspector/runtime/overlay/PickerInteractionTest.kt`

**Interfaces:**
- Consumes: runtime session, collector, and hit-test engine.
- Produces: a two-tap pick flow, non-touchable highlight, compact properties, and parent/child/overlap/window switching.

- [ ] **Step 1: Write failing one-shot picker tests**

Verify the first `ACTION_UP` emits one point, removes the touchable picker immediately, ignores further events, and does not remove the non-touchable highlighter. Verify cancellation removes the picker without selection.

- [ ] **Step 2: Implement overlay ownership and exclusion**

Every Inspector root carries `InspectorOverlayTag`. Root collection and hit testing exclude tagged roots. In current-process mode, create one short-lived `TYPE_APPLICATION_PANEL` picker per eligible Root using that Root's `windowToken` and screen bounds; the first `ACTION_UP` wins and removes every picker panel. This covers Activity, Dialog, PopupWindow, and direct WindowManager roots without `SYSTEM_ALERT_WINDOW`. The highlight panel is anchored to the selected Root token and uses `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`; picker panels are touchable only for the single selection gesture.

- [ ] **Step 3: Implement compact interaction**

Use a visual floating control with at least a 48dp touch target, edge snapping, and persisted position. After selection, show class, ID, text, bounds, state, and window source plus fixed actions for parent, child, overlap, next window, collapse, and close.

- [ ] **Step 4: Add instrumentation timing assertions**

On the benchmark fixture, record elapsed realtime for picker activation, initial highlight, and candidate switching. Assert 150ms/300ms/100ms budgets with a warm process and no screenshot work in the critical path.

- [ ] **Step 5: Run tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest :ui-inspector-runtime:connectedDebugAndroidTest`

Expected: one-shot picker unit tests and device interaction tests PASS.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/overlay ui-inspector-runtime/src/main/res/layout/view_inspector_floating_panel.xml ui-inspector-runtime/src/test ui-inspector-runtime/src/androidTest
git commit -m "feat: add efficient on-device UI picker"
```

### Task 8: Add the full device hierarchy and property screen

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/ui/InspectorActivity.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/ui/InspectorViewModel.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/ui/HierarchyAdapter.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/ui/PropertyAdapter.kt`
- Create: `ui-inspector-runtime/src/main/res/layout/activity_ui_inspector.xml`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/ui/InspectorViewModelTest.kt`
- Test: `ui-inspector-runtime/src/androidTest/kotlin/com/company/uiinspector/runtime/ui/InspectorActivityTest.kt`

**Interfaces:**
- Consumes: immutable snapshots and current selection.
- Produces: Window list, expandable hierarchy, ID/text/class search, grouped properties, source/coverage diagnostics, and selection synchronization.

- [ ] **Step 1: Write failing ViewModel tests**

Assert that selecting a node expands every ancestor, produces a flattened visible tree with depth, keeps selection after search when still visible, debounces search by 150ms, and exposes `IN_PROCESS_VIEW` versus `ACCESSIBILITY` labels.

- [ ] **Step 2: Implement state without retaining Views**

`InspectorViewModel` owns only `InspectionSnapshot`, selected node ID, expanded IDs, query, and diagnostics. Adapters consume immutable row models.

- [ ] **Step 3: Build the XML screen**

Use standard Views and RecyclerView. Display mode, package/process, Accessibility status, window count, source badge, tree, property groups, copy, refresh, coverage diagnosis, and report export. Opening the screen scrolls to the selected node.

- [ ] **Step 4: Verify UI behavior**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest :ui-inspector-runtime:connectedDebugAndroidTest`

Expected: ViewModel and Activity tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/ui ui-inspector-runtime/src/main/res/layout/activity_ui_inspector.xml ui-inspector-runtime/src/test ui-inspector-runtime/src/androidTest
git commit -m "feat: add device hierarchy inspector"
```

### Task 9: Add opt-in global Accessibility inspection

**Files:**
- Modify: `ui-inspector-runtime/src/main/AndroidManifest.xml`
- Create: `ui-inspector-runtime/src/main/res/xml/ui_inspector_accessibility_service.xml`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/accessibility/InspectorAccessibilityService.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/accessibility/AccessibilityModeController.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/collect/AccessibilityTreeCollector.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/collect/AccessibilityTreeCollectorTest.kt`
- Test: `ui-inspector-runtime/src/androidTest/kotlin/com/company/uiinspector/runtime/accessibility/AccessibilityModeTest.kt`

**Interfaces:**
- Consumes: `AccessibilityWindowInfo` roots and one-shot screen coordinates.
- Produces: global `InspectionSnapshot` values with source `ACCESSIBILITY`, package/window filtering, stale-node-safe traversal, and user-visible capability state.

- [ ] **Step 1: Write failing fake-tree tests**

Wrap platform nodes behind `AccessibilityNodeReader` so unit tests can model stale children, null roots, overlapping windows, missing IDs/text, and an Inspector package. Assert fresh traversal skips failures per node, excludes Inspector overlays, and emits diagnostics instead of throwing.

- [ ] **Step 2: Declare the service**

Use `canRetrieveWindowContent="true"`, `canTakeScreenshot="true"`, `flagReportViewIds`, `flagIncludeNotImportantViews`, and `flagRetrieveInteractiveWindows`. Do not declare a package filter; `packageNames = null` means global mode. Keep the service only in the runtime manifest so the noop/release dependency never contributes it.

- [ ] **Step 3: Implement progressive permission flow**

Current-process mode never prompts. Selecting Global App checks connection state, explains the capability, and opens Accessibility settings only after explicit confirmation. Returning without enabling restores current-process mode with a concise status.

- [ ] **Step 4: Implement fresh multi-window capture**

Call `getWindows()` at pick time, order by reported layer, copy properties immediately, and never cache `AccessibilityNodeInfo`. Catch stale-node errors per operation. Reuse the same hit result model as Task 6.

- [ ] **Step 5: Verify Accessibility tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest :ui-inspector-runtime:connectedDebugAndroidTest`

Expected: unit tests PASS; the instrumentation fixture verifies mode switching and node selection on API 28+.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-runtime/src/main/AndroidManifest.xml ui-inspector-runtime/src/main/res/xml ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/accessibility ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/collect/AccessibilityTreeCollector.kt ui-inspector-runtime/src/test ui-inspector-runtime/src/androidTest
git commit -m "feat: add global Accessibility inspection"
```

### Task 10: Add optional screenshots, redacted JSON reports, and diagnostics

**Files:**
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/screenshot/ScreenshotProvider.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/screenshot/PixelCopyScreenshotProvider.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/screenshot/AccessibilityScreenshotProvider.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/report/TextRedactor.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/report/InspectionReportWriter.kt`
- Test: `ui-inspector-runtime/src/test/kotlin/com/company/uiinspector/runtime/report/InspectionReportWriterTest.kt`

**Interfaces:**
- Consumes: `InspectionSnapshot`, optional Bitmap result, redaction configuration, and app-private output directory.
- Produces: a versioned JSON report plus optional sampled PNG, with explicit unsupported/secure/timeout status.

- [ ] **Step 1: Write failing report tests**

Test no-screenshot API 28 global reports, phone/email/custom-regex redaction, filename safety, atomic temp-file replacement, and JSON decode after write.

- [ ] **Step 2: Implement screenshot capability selection**

For known process Windows use PixelCopy. For roots without a Window, draw to a sampled Bitmap and record possible Surface/Texture omissions. For global API 30+ use Accessibility screenshot; for API 28–29 return `UNSUPPORTED` without failing capture.

- [ ] **Step 3: Implement private report output**

Write under `context.filesDir/ui-inspector/reports`, use a temp file followed by rename, and expose sharing only through an explicit user action and FileProvider URI. Show a sensitive-text warning before export.

- [ ] **Step 4: Verify report tests**

Run: `./gradlew :ui-inspector-runtime:testDebugUnitTest --tests '*report*'`

Expected: all report, redaction, and optional screenshot tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/screenshot ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/report ui-inspector-runtime/src/test
git commit -m "feat: export redacted inspection reports"
```

### Task 11: Implement the AGP 8.x debug instrumentation plugin

**Files:**
- Create: `ui-inspector-gradle-plugin/src/main/kotlin/com/company/uiinspector/plugin/UiInspectorPlugin.kt`
- Create: `ui-inspector-gradle-plugin/src/main/kotlin/com/company/uiinspector/plugin/UiInspectorExtension.kt`
- Create: `ui-inspector-gradle-plugin/src/main/kotlin/com/company/uiinspector/plugin/InspectorClassVisitorFactory.kt`
- Create: `ui-inspector-gradle-plugin/src/main/kotlin/com/company/uiinspector/plugin/WindowCallClassVisitor.kt`
- Create: `ui-inspector-gradle-plugin/src/main/kotlin/com/company/uiinspector/plugin/InstrumentationReport.kt`
- Create: `ui-inspector-runtime/src/main/kotlin/com/company/uiinspector/runtime/tracking/InstrumentationHooks.kt`
- Test: `ui-inspector-gradle-plugin/src/test/kotlin/com/company/uiinspector/plugin/WindowCallClassVisitorTest.kt`
- Test: `ui-inspector-gradle-plugin/src/test/kotlin/com/company/uiinspector/plugin/UiInspectorPluginTest.kt`

**Interfaces:**
- Consumes: AGP public `AndroidComponentsExtension`, debug variants, ASM method calls, and runtime static hooks.
- Produces: debug-only callbacks `InstrumentationHooks.onWindowAdded/Updated/Removed`, `onDialogShown/Hidden`, and `onPopupShown/Hidden`, plus a build coverage report.

- [ ] **Step 1: Write failing ASM fixture tests**

Compile small Java fixtures that invoke every supported WindowManager, Dialog, and PopupWindow method. Transform the class, inspect bytecode, and execute it with fake hooks. Assert exactly one hook per call and no changed return/exception behavior.

- [ ] **Step 2: Implement a project-scope visitor**

Register through `variant.instrumentation.transformClassesWith(..., InstrumentationScope.PROJECT)` only when `variant.debuggable` is true. Use `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`. Match owners and descriptors exactly; store receiver/arguments in new locals, invoke the original method, then invoke the hook only after success.

- [ ] **Step 3: Record source metadata**

Pass class name, method name, source file, and current line number when available. Do not retain stack traces per window. Aggregate counts by call type for the build report.

- [ ] **Step 4: Add Gradle TestKit compatibility fixtures**

Run representative builds with AGP 8.2.2 and 8.13.2 on JDK 17. Assert Debug bytecode contains hooks, Release bytecode does not, and an AGP 9 fixture fails with the documented unsupported-version message.

- [ ] **Step 5: Verify plugin tests**

Run: `./gradlew :ui-inspector-gradle-plugin:test`

Expected: ASM unit tests and TestKit fixtures PASS for both supported AGP versions.

- [ ] **Step 6: Commit**

```bash
git add ui-inspector-gradle-plugin
git commit -m "feat: instrument debug window calls"
```

### Task 12: Enforce release isolation and complete end-to-end device verification

**Files:**
- Create: `ui-inspector-noop/src/main/kotlin/com/company/uiinspector/api/NoopUiInspectorDelegate.kt`
- Modify: `sample-app/src/main/kotlin/com/company/uiinspector/sample/MainActivity.kt`
- Create: `sample-app/src/main/kotlin/com/company/uiinspector/sample/WindowScenarioController.kt`
- Create: `sample-app/src/androidTest/kotlin/com/company/uiinspector/sample/InspectorEndToEndTest.kt`
- Create: `scripts/verify-release-isolation.sh`
- Create: `.github/workflows/android.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: all prior modules.
- Produces: a demonstrable device tool, release-safe artifact checks, documented integration, and a repeatable test matrix.

- [ ] **Step 1: Build all sample scenarios**

The sample must create an Activity hierarchy, Dialog, PopupWindow, Application-context WindowManager view, overlapping transparent window, and a 2,000-node benchmark hierarchy. Each scenario has visible controls to add, update, and remove roots.

- [ ] **Step 2: Write the end-to-end device test**

Automate current-process selection for each root type; assert highlight bounds, source, parent navigation, overlap switching, cleanup after removal, and Inspector self-exclusion. Add an Accessibility fixture app or test Activity for global-mode node capture.

- [ ] **Step 3: Implement release isolation verification**

`scripts/verify-release-isolation.sh` builds `:sample-app:assembleRelease`, runs `apkanalyzer manifest print` and `apkanalyzer dex packages`, and fails if output contains `InspectorAccessibilityService`, `com.company.uiinspector.runtime`, or the Inspector Activity/action. It also verifies debug contains the expected service.

- [ ] **Step 4: Document integration and limitations**

README sections: prerequisites, plugin setup, debug/release dependencies, automatic coverage, WindowManager wrapper, manual registration, current/global mode behavior, API 28 screenshot limitation, report privacy, troubleshooting, and AGP 8.2.2–8.13.2/JDK 17 support.

- [ ] **Step 5: Run the full verification suite**

Run:

```bash
./gradlew clean test :ui-inspector-gradle-plugin:test :sample-app:assembleDebug :sample-app:assembleRelease
./gradlew :sample-app:connectedDebugAndroidTest
./scripts/verify-release-isolation.sh
```

Expected: all unit/TestKit/device tests PASS, both APK variants build, and release isolation reports no runtime inspector components.

- [ ] **Step 6: Manually verify the UX acceptance path**

On API 28 and API 34 physical devices: open sample, start Inspector without Accessibility, pick every window type in two taps, switch candidates, open the full tree, export a report, enable global mode, inspect another App, disable the service, and stop Inspector. Record measured picker/highlight/switch timing and any OEM-specific behavior in `docs/device-test-results.md`.

- [ ] **Step 7: Commit**

```bash
git add ui-inspector-noop sample-app scripts .github README.md docs/device-test-results.md
git commit -m "test: verify device inspector end to end"
```

## Implementation Checkpoints

- After Task 4: review API shape, release calling pattern, and root lifecycle before building collectors.
- After Task 7: perform a real-device interaction review before adding the full hierarchy UI.
- After Task 9: verify Accessibility behavior on at least one API 28 device and one API 34+ device.
- After Task 11: review transformed bytecode and release variant output before enabling the plugin in company projects.
- After Task 12: compare implementation line by line against the design acceptance criteria.
