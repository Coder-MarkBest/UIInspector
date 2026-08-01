# Runtime View Property Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the browser inspector edit every currently queried Real View property whose setter or approved adapter accepts a supported simple value, then refresh and optionally restore the runtime value without any business-project integration.

**Architecture:** Capture-time metadata declares exactly which properties are editable. A token-protected mutation API validates the current target snapshot, deploys a one-shot mutation command through the existing ADB/JVMTI path, applies the setter on Android's main thread, and returns a fresh snapshot. Accessibility/global nodes remain read-only, and the browser never supplies an arbitrary setter name.

**Tech Stack:** Java 17 protocol and Android helper, Java 21 Android Studio plugin, JNI/C++17 bootstrap, Android View/JVMTI/ADB, Gson, JUnit 5, Robolectric/JUnit 4, vanilla JavaScript/CSS, Node test runner.

## Global Constraints

- Business projects must not add an SDK or change source, APK, Manifest, or Gradle files.
- Mutation is allowed only for the explicitly selected debuggable target process and `CaptureSource.JVMTI` data.
- Accessibility semantic nodes, global scope, non-debuggable targets, and system processes remain read-only.
- Supported inputs are boolean, primitive/wrapper numbers, character, String/CharSequence, enum, colors, and approved Android constants.
- Complex objects, listeners, arbitrary expressions, arbitrary method names, Window type/flags/format, and batch scripts are not supported.
- Every edit is session-only and disappears when business code overwrites it or the target process restarts.
- Every request changes one property, runs the actual View operation on the Android main thread, and returns a fresh snapshot.
- The browser server remains loopback-only and requires the existing session token plus same-origin POST checks.
- Minimum device version remains Android 9 / API 28; host support remains macOS and Windows, not Linux.
- Preserve the unrelated local `gradle.properties` modification and do not stage it.

---

## File Structure

New protocol files keep wire types independent of Android and IntelliJ APIs:

- `protocol/.../EditorTarget.java`: `VIEW`, `LAYOUT_PARAMS`, `WINDOW_PARAMS`.
- `protocol/.../EditorValueType.java`: supported browser/Agent value types.
- `protocol/.../PropertyEditor.java`: immutable edit capability metadata.
- `protocol/.../MutationCommand.java`: server-validated command delivered to the Agent.
- `protocol/.../MutationResult.java`: success/failure plus refreshed snapshot.
- `protocol/.../MutationJson.java`: version-checked Gson encoding for commands/results.

New helper files keep capture, lookup, conversion, and mutation orchestration separate:

- `agent-helper/.../PropertyEditors.java`: discover deterministic setters/adapters and describe editability.
- `agent-helper/.../ViewTargetResolver.java`: resolve `v:n:<window>:<child path>` and `v:w:<window>` safely.
- `agent-helper/.../ViewPropertyMutator.java`: parse supported values and invoke setters/adapters.
- `agent-helper/.../ViewMutationBridge.java`: main-thread execution, result writing, and refreshed capture.

Existing native, desktop, server, and Web files are extended without introducing a second transport or UI framework.

---

### Task 1: Define Edit Metadata and Mutation Wire Types

**Files:**
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/EditorTarget.java`
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/EditorValueType.java`
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/PropertyEditor.java`
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/MutationCommand.java`
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/MutationResult.java`
- Create: `protocol/src/main/java/com/company/uiinspector/protocol/MutationJson.java`
- Modify: `protocol/src/main/java/com/company/uiinspector/protocol/PropertyValue.java`
- Modify: `protocol/src/test/java/com/company/uiinspector/protocol/SnapshotJsonTest.java`
- Create: `protocol/src/test/java/com/company/uiinspector/protocol/MutationJsonTest.java`

**Interfaces:**
- Consumes: existing `InspectionSnapshot`, `SnapshotJson`, `PropertyValue`, and Gson dependency.
- Produces: `PropertyValue.editor()`, `PropertyValue.readOnlyReason()`, `MutationJson.encodeCommand/decodeCommand`, and `MutationJson.encodeResult/decodeResult` for all later tasks.

- [ ] **Step 1: Write failing protocol tests**

Extend the snapshot test so a property retains editor metadata and old two-argument construction remains valid:

```java
var editor = new PropertyEditor(
        EditorTarget.VIEW, EditorValueType.BOOLEAN, "setter:setEnabled", List.of());
identity.put("enabled", new PropertyValue("true", "JVMTI", editor, null));
identity.put("class", new PropertyValue("android.view.View", "JVMTI"));

var decoded = SnapshotJson.decode(SnapshotJson.encode(snapshot));
assertEquals(EditorValueType.BOOLEAN,
        decoded.nodes().get("v:1").propertyGroups().get(0)
                .properties().get("enabled").editor().valueType());
assertNull(decoded.nodes().get("v:1").propertyGroups().get(0)
        .properties().get("class").editor());
```

Create `MutationJsonTest` with literal values and both result branches:

```java
@Test
void roundTripsCommandAndSuccessResult() {
    var command = new MutationCommand(
            1, "v:n:0:0.1", "android.widget.TextView", "com.example:id/title",
            "text", "setter:setText", EditorTarget.VIEW, EditorValueType.STRING, "Changed");
    assertEquals("Changed", MutationJson.decodeCommand(MutationJson.encodeCommand(command)).value());

    var result = new MutationResult(
            1, true, "ok", "Applied", "Before", "Changed", snapshot());
    var decoded = MutationJson.decodeResult(MutationJson.encodeResult(result));
    assertTrue(decoded.success());
    assertEquals("Before", decoded.previousValue());
    assertEquals("Changed", decoded.appliedValue());
}

@Test
void rejectsUnknownMutationSchema() {
    assertThrows(IllegalArgumentException.class,
            () -> MutationJson.decodeCommand("{\"schemaVersion\":2}"));
}
```

- [ ] **Step 2: Run protocol tests and verify RED**

Run:

```bash
./gradlew :protocol:test --tests '*SnapshotJsonTest' --tests '*MutationJsonTest'
```

Expected: compilation fails because the editor/mutation types and extended `PropertyValue` constructor do not exist.

- [ ] **Step 3: Implement the wire types**

Use these exact public shapes:

```java
public enum EditorTarget { VIEW, LAYOUT_PARAMS, WINDOW_PARAMS }

public enum EditorValueType {
    BOOLEAN, BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE,
    CHARACTER, STRING, ENUM, COLOR
}

public record PropertyEditor(
        EditorTarget target,
        EditorValueType valueType,
        String adapter,
        List<String> choices) {
    public PropertyEditor {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}

public record PropertyValue(
        String value,
        String source,
        PropertyEditor editor,
        String readOnlyReason) {
    public PropertyValue(String value, String source) {
        this(value, source, null, null);
    }
}

public record MutationCommand(
        int schemaVersion,
        String targetId,
        String expectedClassName,
        String expectedResourceId,
        String propertyName,
        String adapter,
        EditorTarget target,
        EditorValueType valueType,
        String value) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}

public record MutationResult(
        int schemaVersion,
        boolean success,
        String code,
        String message,
        String previousValue,
        String appliedValue,
        InspectionSnapshot snapshot) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

`MutationJson` must use `new GsonBuilder().disableHtmlEscaping().create()`, require schema version `1` for both decode methods, and never silently accept a missing version.

- [ ] **Step 4: Run protocol tests and verify GREEN**

Run:

```bash
./gradlew :protocol:test
```

Expected: all protocol tests pass with zero failures.

- [ ] **Step 5: Commit Task 1**

```bash
git add protocol/src/main/java/com/company/uiinspector/protocol \
  protocol/src/test/java/com/company/uiinspector/protocol
git commit -m "feat: define view mutation protocol"
```

---

### Task 2: Discover Editable Properties During Real View Capture

**Files:**
- Create: `agent-helper/src/main/java/com/company/uiinspector/agent/PropertyEditors.java`
- Modify: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewCaptureBridge.java`
- Modify: `agent-helper/src/test/java/com/company/uiinspector/agent/ViewCaptureBridgeTest.java`

**Interfaces:**
- Consumes: Task 1 `PropertyEditor`, `EditorTarget`, `EditorValueType`, and extended `PropertyValue`.
- Produces: `PropertyEditors.Description(PropertyEditor editor, String readOnlyReason)`, `PropertyEditors.forView(View, String)`, and `PropertyEditors.forWindow(View, WindowManager.LayoutParams, String)`, which are also the Agent-side allowlist used by Task 3.

- [ ] **Step 1: Write failing capture metadata tests**

Add assertions to `ViewCaptureBridgeTest` for supported, overloaded, adapted, and complex properties:

```java
var enabled = property(textNode, "enabled");
assertEquals(EditorValueType.BOOLEAN, enabled.editor().valueType());
assertEquals("setter:setEnabled", enabled.editor().adapter());

var text = property(textNode, "text");
assertEquals(EditorValueType.STRING, text.editor().valueType());
assertEquals("setter:setText:java.lang.CharSequence", text.editor().adapter());

assertEquals(EditorValueType.COLOR, property(textNode, "textColor").editor().valueType());
assertEquals("adapter:textColor", property(textNode, "textColor").editor().adapter());
assertEquals("adapter:marginLeft", property(textNode, "marginLeft").editor().adapter());
assertEquals(EditorValueType.ENUM, property(imageNode, "imageScaleType").editor().valueType());
assertTrue(property(imageNode, "imageScaleType").editor().choices().contains("CENTER_CROP"));

assertNull(property(textNode, "background").editor());
assertEquals("Unsupported setter parameter: android.graphics.drawable.Drawable",
        property(textNode, "background").readOnlyReason());
assertNull(property(textNode, "focused").editor());
```

Add a helper that searches all groups by literal property name; it must return the real captured `PropertyValue`, not reconstruct expectations.

- [ ] **Step 2: Run helper test and verify RED**

Run:

```bash
./gradlew :agent-helper:testDebugUnitTest --tests '*ViewCaptureBridgeTest'
```

Expected: compilation fails because captured properties have no editor metadata and `PropertyEditors` does not exist.

- [ ] **Step 3: Implement deterministic editor discovery**

`PropertyEditors` must:

1. Look up public one-parameter methods named `set` + capitalized property.
2. Prefer a setter whose parameter matches the conventional getter return type (`getX`/`isX`).
3. For `text`, explicitly prefer `CharSequence` over integer resource overloads.
4. Support primitive/wrapper types, char, String/CharSequence, enum, and named color properties.
5. Return a read-only reason only when a matching setter exists but its parameter is complex.
6. Return these exact adapters before generic discovery:

```java
Map.ofEntries(
    entry("paddingLeft", editor(VIEW, INTEGER, "adapter:paddingLeft")),
    entry("paddingTop", editor(VIEW, INTEGER, "adapter:paddingTop")),
    entry("paddingRight", editor(VIEW, INTEGER, "adapter:paddingRight")),
    entry("paddingBottom", editor(VIEW, INTEGER, "adapter:paddingBottom")),
    entry("layoutParamWidth", editor(LAYOUT_PARAMS, INTEGER, "adapter:layoutParamWidth",
            List.of("MATCH_PARENT", "WRAP_CONTENT"))),
    entry("layoutParamHeight", editor(LAYOUT_PARAMS, INTEGER, "adapter:layoutParamHeight",
            List.of("MATCH_PARENT", "WRAP_CONTENT"))),
    entry("marginLeft", editor(LAYOUT_PARAMS, INTEGER, "adapter:marginLeft")),
    entry("marginTop", editor(LAYOUT_PARAMS, INTEGER, "adapter:marginTop")),
    entry("marginRight", editor(LAYOUT_PARAMS, INTEGER, "adapter:marginRight")),
    entry("marginBottom", editor(LAYOUT_PARAMS, INTEGER, "adapter:marginBottom")),
    entry("marginStart", editor(LAYOUT_PARAMS, INTEGER, "adapter:marginStart")),
    entry("marginEnd", editor(LAYOUT_PARAMS, INTEGER, "adapter:marginEnd")),
    entry("textSizePx", editor(VIEW, FLOAT, "adapter:textSizePx")),
    entry("textColor", editor(VIEW, COLOR, "adapter:textColor")),
    entry("imageScaleType", editor(VIEW, ENUM, "adapter:imageScaleType",
            enumNames(ImageView.ScaleType.class))),
    entry("checked", editor(VIEW, BOOLEAN, "adapter:checked"))
);
```

Use this exact result boundary so capture and mutation share one decision:

```java
record Description(PropertyEditor editor, String readOnlyReason) {}

static Description forView(View view, String propertyName);
static Description forWindow(
        View root, WindowManager.LayoutParams params, String propertyName);
```

Only expose a type-specific adapter when the runtime object supports it: margin adapters require `MarginLayoutParams`, text adapters require `TextView`, scale type requires `ImageView`, and checked requires `Checkable`.

Window metadata must expose only `x`, `y`, `layoutWidth`, `layoutHeight`, `gravity`, `windowAlpha`, `dimAmount`, and `softInputMode` using `WINDOW_PARAMS`; it must not expose type, flags, or format.

Change `ViewCaptureBridge.group(...)` to create each `PropertyValue` with the editor/reason supplied by `PropertyEditors`. Accessibility capture continues using the two-argument constructor and therefore remains read-only.

- [ ] **Step 4: Run helper tests and verify GREEN**

Run:

```bash
./gradlew :agent-helper:testDebugUnitTest
```

Expected: all helper tests pass; assertions prove boolean, string overload, color, enum, aggregate adapter, and complex-object behavior.

- [ ] **Step 5: Commit Task 2**

```bash
git add agent-helper/src/main/java/com/company/uiinspector/agent/PropertyEditors.java \
  agent-helper/src/main/java/com/company/uiinspector/agent/ViewCaptureBridge.java \
  agent-helper/src/test/java/com/company/uiinspector/agent/ViewCaptureBridgeTest.java
git commit -m "feat: describe editable real view properties"
```

---

### Task 3: Resolve Targets and Apply Mutations on the Main Thread

**Files:**
- Create: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewTargetResolver.java`
- Create: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewPropertyMutator.java`
- Create: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewMutationBridge.java`
- Create: `agent-helper/src/test/java/com/company/uiinspector/agent/ViewPropertyMutatorTest.java`
- Modify: `agent-helper/src/main/java/com/company/uiinspector/agent/ViewCaptureBridge.java`

**Interfaces:**
- Consumes: Task 1 mutation records/JSON and Task 2 `PropertyEditors` allowlist.
- Produces: native-callable `ViewMutationBridge.mutate(List<View>, List<WindowManager.LayoutParams>, String commandPath, String outputPath)`.

- [ ] **Step 1: Write failing target-resolution and mutation tests**

Create a Robolectric fixture with `FrameLayout root -> LinearLayout container -> TextView title` and literal IDs. Cover one behavior per test:

```java
@Test public void resolvesPathAndRejectsStaleIdentity() {
    assertSame(title, ViewTargetResolver.resolveNode(List.of(root), "v:n:0:0.0"));
    var error = assertThrows(ViewPropertyMutator.MutationFailure.class, () -> mutator.apply(
            List.of(root), List.of(params), command("v:n:0:0.0", "android.widget.Button",
                    resourceName(title), "alpha", "setter:setAlpha", FLOAT, "0.4")));
    assertEquals("stale_target", error.code());
}

@Test public void changesBooleanFloatStringEnumAndColor() {
    assertApplied(commandFor(title, "enabled", "setter:setEnabled", BOOLEAN, "false"));
    assertFalse(title.isEnabled());
    assertApplied(commandFor(title, "alpha", "setter:setAlpha", FLOAT, "0.4"));
    assertEquals(0.4f, title.getAlpha(), 0.001f);
    assertApplied(commandFor(title, "text", "setter:setText:java.lang.CharSequence", STRING, "Changed"));
    assertEquals("Changed", title.getText().toString());
    assertApplied(commandFor(image, "imageScaleType", "adapter:imageScaleType", ENUM, "FIT_CENTER"));
    assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
    assertApplied(commandFor(title, "textColor", "adapter:textColor", COLOR, "#FF112233"));
    assertEquals(0xFF112233, title.getCurrentTextColor());
}

@Test public void appliesAggregateLayoutPropertiesAndRequestsLayout() {
    assertApplied(commandFor(title, "paddingLeft", "adapter:paddingLeft", INTEGER, "24"));
    assertEquals(24, title.getPaddingLeft());
    assertApplied(commandFor(title, "marginTop", "adapter:marginTop", INTEGER, "18"));
    assertEquals(18, ((MarginLayoutParams) title.getLayoutParams()).topMargin);
    assertApplied(commandFor(title, "layoutParamWidth", "adapter:layoutParamWidth", INTEGER, "WRAP_CONTENT"));
    assertEquals(LayoutParams.WRAP_CONTENT, title.getLayoutParams().width);
}

@Test public void rejectsComplexUnknownOverflowAndNonFiniteValues() {
    assertFailure("property_read_only", commandFor(title, "background", "setter:setBackground", STRING, "x"));
    assertFailure("invalid_value", commandFor(title, "alpha", "setter:setAlpha", FLOAT, "NaN"));
    assertFailure("invalid_value", commandFor(title, "minimumWidth", "setter:setMinimumWidth", INTEGER, "999999999999"));
}
```

Add an asynchronous bridge test that calls `ViewMutationBridge.mutate(...)` from a worker thread, drains the main looper, reads the result file, and asserts the returned snapshot contains the applied value.

- [ ] **Step 2: Run helper tests and verify RED**

Run:

```bash
./gradlew :agent-helper:testDebugUnitTest --tests '*ViewPropertyMutatorTest'
```

Expected: compilation fails because the resolver, mutator, bridge, and failure type do not exist.

- [ ] **Step 3: Implement resolver, conversion, adapters, and bridge**

Use exact resolver behavior:

```java
static View resolveNode(List<View> roots, String id) {
    // Accept only v:n:<windowIndex>:0(.<childIndex>)*.
    // Bounds-check every index and reject malformed or missing segments with stale_target.
}

static int resolveWindowIndex(List<View> roots, String id) {
    // Accept only v:w:<windowIndex> and validate the index.
}
```

`ViewPropertyMutator.apply(...)` must:

- Recompute `PropertyEditors.forView/forWindow` and require an exact adapter, target, and value type match with the command.
- Validate expected class and resource ID before parsing the value.
- Parse the full input string; reject trailing characters, integer overflow, NaN, Infinity, multi-character char values, and enum values outside `choices`.
- Map `VISIBLE/INVISIBLE/GONE`, `MATCH_PARENT/WRAP_CONTENT`, and hexadecimal colors before invocation.
- Invoke generic public setters through reflection only after the allowlist match.
- Preserve the other padding/margin sides in aggregate adapters.
- Call `setLayoutParams()` after LayoutParams changes.
- Apply supported Window fields then call `WindowManager.updateViewLayout(root, params)`.
- Call `requestLayout()` and `invalidate()` after a successful View mutation.
- Return the value read from a fresh capture as `appliedValue`, rather than echoing the request.

Define `MutationFailure` as a package-private nested exception carrying `code()` and a safe message. Define a package-private `WindowUpdater` functional interface so the production path calls `WindowManager.updateViewLayout()` while the Robolectric test can keep mutation of the real `LayoutParams` under test without requiring a platform Window session.

`ViewMutationBridge.mutate(...)` must post exactly one Runnable to `Looper.getMainLooper()` when called off-main, catch all setter exceptions into a stable `MutationResult`, capture a new snapshot after success, and write UTF-8 JSON to `outputPath`. Make the existing snapshot write helper package-private so both bridges use the same atomic write behavior.

- [ ] **Step 4: Run helper tests and verify GREEN**

Run:

```bash
./gradlew :agent-helper:testDebugUnitTest
```

Expected: all helper tests pass, including fresh-snapshot and failure-code assertions.

- [ ] **Step 5: Commit Task 3**

```bash
git add agent-helper/src/main/java/com/company/uiinspector/agent \
  agent-helper/src/test/java/com/company/uiinspector/agent
git commit -m "feat: mutate real views on the main thread"
```

---

### Task 4: Carry Mutation Commands Through ADB and JVMTI

**Files:**
- Modify: `agent-native/src/main/cpp/agent.cpp`
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/capture/AgentDeployer.java`
- Modify: `plugin/src/test/java/com/company/uiinspector/plugin/capture/AgentDeployerTest.java`

**Interfaces:**
- Consumes: Task 1 `MutationCommand`, `MutationResult`, `MutationJson`; Task 3 native-callable helper signature.
- Produces: `AgentDeployer.mutate(String serial, RunningProcess process, String abi, MutationCommand command)` returning `MutationResult`.

- [ ] **Step 1: Write a failing deployer mutation test**

Extend `RecordingAdb` to return encoded `MutationResult` when the command cats `mutation-result-1.json`. Add:

```java
@Test
void deploysCommandFileAttachesMutationModeReadsResultAndCleansUp() throws Exception {
    var command = mutationCommand("v:n:0:0", "alpha", "setter:setAlpha", FLOAT, "0.5");
    var result = deployer.mutate(
            "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"),
            "arm64-v8a", command);

    assertTrue(result.success());
    String recordedLocalCommand = adb.pushedCommandFile;
    assertTrue(adb.commands.stream().anyMatch(value -> value.equals(List.of(
            "-s", "device", "push", recordedLocalCommand,
            "/data/local/tmp/uiinspector-123-capture-1-command.json"))));
    assertEquals(List.of(
            "-s", "device", "shell", "cmd", "activity", "attach-agent", "com.example",
            "/data/user/0/com.example/code_cache/uiinspector/libuiinspector_agent_capture-1.so="
                    + "/data/user/0/com.example/code_cache/uiinspector/helper-capture-1.apk,"
                    + "/data/user/0/com.example/code_cache/uiinspector/command-capture-1.json,"
                    + "/data/user/0/com.example/code_cache/uiinspector/mutation-result-capture-1.json"),
            attachCommand(adb.commands));
    assertTrue(cleanupCommand(adb.commands).contains("command-capture-1.json"));
    assertTrue(cleanupCommand(adb.commands).contains("mutation-result-capture-1.json"));
}
```

The test must decode the actual local command file during the fake `push` call and assert its target ID, property, adapter, type, and value; it must not assert only that a mock file exists.

- [ ] **Step 2: Run deployer test and verify RED**

Run:

```bash
./gradlew :plugin:test --tests '*AgentDeployerTest'
```

Expected: compilation fails because `AgentDeployer.mutate(...)` does not exist.

- [ ] **Step 3: Implement desktop and native mutation modes**

In `AgentDeployer`:

- Write `MutationJson.encodeCommand(command)` to a unique local temp file.
- Push helper, Agent, and command to `/data/local/tmp` using argument arrays.
- Copy all three into the target package's `code_cache/uiinspector` with `run-as`.
- Attach with three comma-separated private paths: helper, command, result.
- Poll and decode `mutation-result-<id>.json` using the existing 15-second wait pattern.
- Delete the local command in `finally` and include temp/private command/result files in best-effort cleanup.
- Keep the existing two-option capture mode unchanged.

In `agent.cpp`, replace the pair parser with:

```cpp
struct AgentOptions {
    std::string helper_path;
    std::string command_path;
    std::string output_path;
};
```

Two components mean capture (`helper,output`); three components mean mutation (`helper,command,output`). Mutation mode loads `com.company.uiinspector.agent.ViewMutationBridge` and calls:

```cpp
"mutate",
"(Ljava/util/List;Ljava/util/List;Ljava/lang/String;Ljava/lang/String;)V"
```

Malformed option counts must log one clear error and return `JNI_ERR`. Keep C++17 `-Wall -Wextra -Werror` clean.

- [ ] **Step 4: Run deployer, helper, and native builds and verify GREEN**

Run:

```bash
./gradlew :plugin:test --tests '*AgentDeployerTest' \
  :agent-helper:testDebugUnitTest :agent-native:assembleRelease
```

Expected: tests and all four ABI native builds pass.

- [ ] **Step 5: Commit Task 4**

```bash
git add agent-native/src/main/cpp/agent.cpp \
  plugin/src/main/java/com/company/uiinspector/plugin/capture/AgentDeployer.java \
  plugin/src/test/java/com/company/uiinspector/plugin/capture/AgentDeployerTest.java
git commit -m "feat: transport view mutations through jvmti"
```

---

### Task 5: Add the Validated Mutation API

**Files:**
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/server/InspectorServer.java`
- Modify: `plugin/src/main/java/com/company/uiinspector/plugin/server/InspectorApi.java`
- Modify: `plugin/src/test/java/com/company/uiinspector/plugin/server/InspectorServerTest.java`
- Modify: `plugin/src/test/java/com/company/uiinspector/plugin/server/InspectorApiTest.java`

**Interfaces:**
- Consumes: current `InspectionSnapshot`, Task 1 metadata/wire types, Task 4 `AgentDeployer.mutate`.
- Produces: token-protected `POST /api/mutate` and `InspectorServer.Api.mutate(String body, Map<String,String> query)`.

- [ ] **Step 1: Write failing HTTP and API validation tests**

In `InspectorServerTest`, add a valid same-origin POST and assert GET/cross-origin requests never reach the API:

```java
URI mutate = server.baseUri().resolve("api/mutate?token=token");
var ok = client.send(HttpRequest.newBuilder(mutate)
        .header("Origin", server.baseUri().getScheme() + "://" + server.baseUri().getAuthority())
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build(), BodyHandlers.ofString());
assertEquals(200, ok.statusCode());
assertEquals(1, api.mutateCalls);

assertEquals(405, client.send(HttpRequest.newBuilder(mutate).GET().build(),
        BodyHandlers.ofString()).statusCode());
```

In `InspectorApiTest`, use a real encoded snapshot fixture and a real `AgentDeployer` backed by a command-aware `RecordingAdb`. The fake ADB must return literal process/debuggable output, decode the pushed command file during `push`, and return a literal encoded mutation result when the deployer reads it. Cover:

- semantic/global target -> `target_read_only`;
- missing editor -> `property_read_only`;
- pid/package/process mismatch -> `stale_target`;
- non-debuggable process -> `target_not_debuggable`;
- valid Real View property -> command fields are derived from the current snapshot/editor, and returned fresh snapshot replaces `current`.

The valid request body is:

```json
{
  "serial":"emulator-5554",
  "pid":123,
  "packageName":"com.example",
  "processName":"com.example",
  "targetId":"v:n:0:0",
  "expectedClassName":"android.widget.TextView",
  "expectedResourceId":"com.example:id/title",
  "propertyName":"enabled",
  "value":"false"
}
```

- [ ] **Step 2: Run server tests and verify RED**

Run:

```bash
./gradlew :plugin:test --tests '*InspectorServerTest' --tests '*InspectorApiTest'
```

Expected: compilation fails because the API interface and implementation have no mutation operation.

- [ ] **Step 3: Implement API validation and fresh-snapshot merge**

Add `POST /api/mutate` beside `/api/capture`; reuse existing POST same-origin enforcement and return `405` for any other method.

`InspectorApi.mutate(...)` must execute this order:

1. Parse the request and require serial, positive pid, exact package/process, target ID, property, and value.
2. Require `current` snapshot scope `TARGET_PROCESS` and matching package/process.
3. Resolve the current node/window, require `JVMTI`, and verify browser-provided expected identity equals the current snapshot.
4. Find the property in its groups and require non-null editor metadata.
5. Re-list processes, find the exact pid/package/process, and call `ProcessCatalog.isDebuggable` again.
6. Build `MutationCommand` exclusively from the current snapshot/editor plus the requested value; do not accept adapter/type from the browser.
7. Call `AgentDeployer.mutate`, merge its refreshed real snapshot with a fresh screenshot and process list, update `current`, and return a JSON result containing the merged snapshot.

Return stable JSON errors without Java stack traces. Keep capture and mutation methods synchronized so a mutation cannot race a different capture into `current`.

- [ ] **Step 4: Run server tests and verify GREEN**

Run:

```bash
./gradlew :plugin:test --tests '*InspectorServerTest' --tests '*InspectorApiTest'
```

Expected: all server/API tests pass, including authorization and stale/read-only rejection.

- [ ] **Step 5: Commit Task 5**

```bash
git add plugin/src/main/java/com/company/uiinspector/plugin/server \
  plugin/src/test/java/com/company/uiinspector/plugin/server
git commit -m "feat: expose validated view mutation api"
```

---

### Task 6: Render Typed Editors and Restore Values in the Browser

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/styles.css`
- Modify: `plugin/src/test/js/inspector-core.test.mjs`
- Modify: `plugin/src/test/java/com/company/uiinspector/plugin/web/WebAssetContractTest.java`

**Interfaces:**
- Consumes: `PropertyValue.editor/readOnlyReason` and Task 5 `/api/mutate` result.
- Produces: inline typed editors, per-session original-value restore map, and automatic snapshot refresh preserving selection.

- [ ] **Step 1: Write failing pure Web behavior tests**

Export `propertyEditorModel`, `mutationKey`, and `rememberOriginalValue` from `inspector-core.js`, then add:

```javascript
test('editor model maps protocol types to safe controls', () => {
  assert.deepEqual(propertyEditorModel({
    value: 'true', editor: { valueType: 'BOOLEAN', choices: [] }
  }), { control: 'checkbox', value: true, choices: [] });
  assert.deepEqual(propertyEditorModel({
    value: 'CENTER_CROP', editor: { valueType: 'ENUM', choices: ['CENTER', 'CENTER_CROP'] }
  }), { control: 'select', value: 'CENTER_CROP', choices: ['CENTER', 'CENTER_CROP'] });
  assert.equal(propertyEditorModel({ value: 'Drawable', editor: null }), null);
});

test('restore history keeps the first value per process node and property', () => {
  const history = new Map();
  const key = mutationKey('123', 'v:n:0:0', 'alpha');
  rememberOriginalValue(history, key, '1.0');
  rememberOriginalValue(history, key, '0.5');
  assert.equal(history.get(key), '1.0');
});
```

Extend `WebAssetContractTest` to require `api('api/mutate'`, `.property-editor`, and no inline script/eval usage.

- [ ] **Step 2: Run Web tests and verify RED**

Run:

```bash
npm run test:web
./gradlew :plugin:test --tests '*WebAssetContractTest'
```

Expected: named exports and mutation UI contract are missing.

- [ ] **Step 3: Implement typed editor rendering and restore**

Add state fields:

```javascript
editingProperty: null,
mutationBusy: false,
originalValues: new Map()
```

For each property row:

- Show the existing text/source display by default.
- If `property.editor` exists and selected source is JVMTI, show an `编辑` button.
- Render checkbox for `BOOLEAN`, select for `ENUM` or non-empty `choices`, and text input for the remaining types.
- Render a color swatch next to COLOR text input but submit the exact text value.
- Submit only on `应用`; `取消` restores the displayed current value without a request.
- On the first successful edit, store the pre-edit value using `mutationKey(pid,targetId,propertyName)`.
- Show `恢复` when history contains a different original value and call the same mutation endpoint with it.
- Disable the active editor while waiting, retain user input on failure, show the returned message, and never optimistically change the snapshot.

Use a mutation request derived from the current selectors and target:

```javascript
await api('api/mutate', {
  method: 'POST',
  body: JSON.stringify({
    serial: elements['device-select'].value,
    pid: Number(elements['process-select'].value),
    packageName: processOption.dataset.package,
    processName: processOption.dataset.process,
    targetId: target.id,
    expectedClassName: target.className ?? null,
    expectedResourceId: target.resourceId ?? null,
    propertyName: name,
    value
  })
});
```

On success, replace the snapshot without calling `chooseDefaultSelection`; retain `selectedWindowId`, `selectedNodeId`, display, 2D/3D mode, and depth when those IDs still exist. Clear restore history on manual capture, device/process/scope change, or missing target identity.

CSS must keep the existing two-column property table readable at 330px and add visible focus, busy, error, action, checkbox, select, text, and color-swatch states. Respect the existing reduced-motion rule.

- [ ] **Step 4: Run Web and plugin UI tests and verify GREEN**

Run:

```bash
npm run test:web
./gradlew :plugin:test --tests '*WebAssetContractTest'
```

Expected: Node tests and asset contract pass with no demo/mock data.

- [ ] **Step 5: Commit Task 6**

```bash
git add plugin/src/main/resources/web \
  plugin/src/test/js/inspector-core.test.mjs \
  plugin/src/test/java/com/company/uiinspector/plugin/web/WebAssetContractTest.java
git commit -m "feat: edit and restore view properties in browser"
```

---

### Task 7: Full Verification, Android Studio Installation, and Real Runtime Editing

**Files:**
- Modify only if verification exposes a defect in Task 1-6 files; add a failing regression test before each fix.
- Build artifact: `plugin/build/distributions/plugin-0.1.0.zip`
- Installed artifact: Android Studio user plugin directory for the active 2026.1 installation.

**Interfaces:**
- Consumes: complete implementation from Tasks 1-6 and the existing sample app fixtures.
- Produces: verified plugin ZIP, installed plugin, live target/global behavior evidence, and final commits.

- [ ] **Step 1: Run the complete automated verification suite**

Run:

```bash
./gradlew test assemble :plugin:buildPlugin :plugin:verifyPlugin
npm run test:web
git diff --check
```

Expected: Gradle `BUILD SUCCESSFUL`, Plugin Verifier `Compatible`, all Web tests pass, and diff check has no output.

- [ ] **Step 2: Install the exact verified plugin build**

Extract `plugin-0.1.0.zip`, copy it to the current Android Studio 2026.1 user plugin directory, compare SHA-256 of the built and installed `plugin-0.1.0.jar`, and restart Android Studio normally. Verify `idea.log` reports `Loaded custom plugins: Android UI Inspector (0.1.0)` after the restart.

- [ ] **Step 3: Prepare the sample and capture a Real View snapshot**

Use the current emulator and existing fixture launch:

```bash
adb -s emulator-5554 install -r -t sample-target/build/outputs/apk/debug/sample-target-debug.apk
adb -s emulator-5554 shell am start -n \
  com.company.uiinspector.sample/.MainActivity --ez showAllFixtures true
```

Open `Tools > Open UI Inspector`, select `com.company.uiinspector.sample`, use target scope, and capture. Require Real View/JVMTI source and the Activity, PopupWindow, and Direct WindowManager View roots.

- [ ] **Step 4: Verify actual setter and adapter edits**

Through the browser UI, edit and assert by fresh capture/device observation:

1. `TextView.text`: fixture text changes to `Runtime edited title`.
2. `enabled`: true -> false.
3. `alpha`: current -> `0.35`.
4. `visibility`: `VISIBLE` -> `INVISIBLE` -> restore.
5. `ImageView.imageScaleType`: current -> `FIT_CENTER`.
6. `paddingLeft`, `marginTop`, and `layoutParamWidth`: values change in both layout and refreshed properties.
7. Direct WindowManager root `x` or width: window moves/resizes and refreshed Window property changes.
8. Press `恢复` for every edited value and confirm the original values return.

Record that complex `background`, semantic/global nodes, and a non-debuggable process show no editor. Confirm a stale target is rejected after changing the View tree or recapturing into a different identity.

- [ ] **Step 5: Verify global capture remains read-only and functional**

Enable/confirm the Collector AccessibilityService, switch to global scope, capture, and require at least one app Window plus `com.android.systemui` when exposed by the emulator. Confirm zero property editors are rendered and target mutation history is cleared.

- [ ] **Step 6: Verify session-only behavior**

Apply one text or alpha edit, restart the sample process, recapture, and confirm the source-defined value returns. Verify the sample APK hash/source tree did not change during mutation.

- [ ] **Step 7: Inspect logs and security boundaries**

Require:

- unauthenticated `/api/mutate` returns `401`;
- cross-origin POST returns `403`;
- `GET /api/mutate` returns `405`;
- the server still binds only `127.0.0.1`;
- Android Studio and logcat contain no uncaught UI Inspector exception;
- logs contain no node text, command JSON, or full snapshot payload.

- [ ] **Step 8: Commit only test-driven integration fixes and report status**

If no integration defect required code changes, do not create an empty commit. Otherwise stage only the regression tests and their fixes, excluding `gradle.properties`, then commit:

```bash
git commit -m "fix: stabilize runtime view property editing"
```

Finish with `git status --short --branch`; the only allowed unrelated worktree entry is the pre-existing `gradle.properties` modification.

---

## Plan Self-Review Checklist

- Spec coverage: protocol metadata, supported types, generic setter discovery, adapters, stale identity checks, one-shot Agent transport, main-thread execution, refresh, restore, read-only boundaries, security, Windows-neutral host code, and real emulator validation are each assigned to a task.
- Mutation type consistency: every layer uses `MutationCommand`, `MutationResult`, `PropertyEditor`, `EditorTarget`, and `EditorValueType` with the signatures defined in Task 1.
- API consistency: the browser sends only target identity, property, and value; `InspectorApi` derives adapter/type from the current snapshot before creating the Agent command.
- No persistent Agent, arbitrary reflection entrypoint, complex object construction, global mutation, or source/APK modification is introduced.
