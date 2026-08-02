# Preview Selection and 3D Drag Direction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 2D device preview select the deterministic deepest/topmost View with synchronized tree and properties, and make horizontal 3D dragging rotate in the pointer's perceived direction.

**Architecture:** Keep browser event handlers thin and move coordinate conversion, View hit testing, viewport reveal decisions, and drag rotation into pure functions in `inspector-core.js`. The existing `selectNodeId()` remains the single state transition for tree, preview, and property-panel synchronization.

**Tech Stack:** Browser-native ES modules, DOM events, Node.js built-in test runner, Gradle IntelliJ Platform plugin build.

## Global Constraints

- Browser interaction only; do not change Android collection, snapshot protocol, property editing, anomaly rules, or the three-column layout.
- A 2D miss preserves the current selection.
- Candidate priority is Window layer descending, View depth descending, `drawingOrder` descending, hit area ascending, then node ID ascending.
- Use positive `visibleBounds`; otherwise fall back to positive `bounds`.
- Horizontal sensitivity remains `0.25` degrees per pixel and vertical rotation remains clamped to 18–78 degrees.
- Preserve the user's tree search text and existing 3D wheel zoom behavior.
- Preserve the user's unrelated `gradle.properties` modification and never stage it.

---

### Task 1: Deterministic 2D preview hit testing

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/test/js/inspector-core.test.mjs`

**Interfaces:**
- Consumes: snapshot `windows`, snapshot `nodes`, selected `displayId`, screen-frame rectangle, device union bounds, and browser click coordinates.
- Produces: `previewPoint(frameRect, deviceBounds, clientX, clientY): { x, y } | null` and `pickNodeAt(snapshot, displayId, x, y): UiNode | null`.

- [ ] **Step 1: Add failing preview-coordinate and hit-priority tests**

Extend the imports in `inspector-core.test.mjs` with `pickNodeAt` and `previewPoint`, add `visibleBounds` and `drawingOrder` support to the fixture helper, then add:

```js
test('preview coordinates map rendered pixels back to device coordinates', () => {
  assert.deepEqual(
    previewPoint(rect(100, 50, 500, 250), rect(0, 0, 800, 400), 300, 150),
    { x: 400, y: 200 }
  );
  assert.equal(previewPoint(rect(100, 50, 100, 250), rect(0, 0, 800, 400), 100, 100), null);
});

test('2D hit testing selects the deepest node in the highest Window', () => {
  const input = snapshot();
  input.nodes.child.bounds = rect(10, 10, 300, 300);
  input.nodes.child.visibleBounds = rect(10, 10, 200, 200);
  input.nodes.child.drawingOrder = 1;
  input.nodes['dialog-root'].bounds = rect(20, 20, 180, 140);
  input.nodes['dialog-root'].visibleBounds = rect(20, 20, 180, 140);
  input.nodes['dialog-root'].drawingOrder = 0;

  assert.equal(pickNodeAt(input, 0, 50, 50)?.id, 'dialog-root');
  assert.equal(pickNodeAt(input, 0, 190, 190)?.id, 'child');
  assert.equal(pickNodeAt(input, 0, 250, 250)?.id, 'root');
  assert.equal(pickNodeAt(input, 0, 900, 700), null);
});

test('2D hit testing resolves same-Window ties deterministically', () => {
  const input = snapshot();
  input.windows = [input.windows.find(value => value.id === 'activity')];
  input.nodes.root.bounds = rect(0, 0, 400, 400);
  input.nodes.root.visibleBounds = input.nodes.root.bounds;
  input.nodes.root.drawingOrder = 0;
  input.nodes.child.bounds = rect(0, 0, 200, 200);
  input.nodes.child.visibleBounds = input.nodes.child.bounds;
  input.nodes.child.drawingOrder = 2;
  input.nodes.sibling = node('sibling', 'activity', 'root', [], 1, rect(0, 0, 160, 160), 3);

  assert.equal(pickNodeAt(input, 0, 100, 100)?.id, 'sibling');
  input.nodes.sibling.drawingOrder = 2;
  assert.equal(pickNodeAt(input, 0, 100, 100)?.id, 'sibling');
});
```

Update the fixture helper exactly to:

```js
function node(id, windowId, parentId, childIds, depth, bounds, drawingOrder = 0) {
  return {
    id, windowId, parentId, childIds, depth, bounds,
    visibleBounds: bounds, drawingOrder,
    className: 'android.view.View', propertyGroups: []
  };
}
```

- [ ] **Step 2: Run the Web tests and verify RED**

Run: `npm run test:web`

Expected: FAIL because `pickNodeAt` and `previewPoint` are not exported by `inspector-core.js`.

- [ ] **Step 3: Implement the minimal pure hit-test functions**

Add to `inspector-core.js`:

```js
export function previewPoint(frameRect, deviceBounds, clientX, clientY) {
  const frameWidth = Number(frameRect?.right) - Number(frameRect?.left);
  const frameHeight = Number(frameRect?.bottom) - Number(frameRect?.top);
  const deviceWidth = Number(deviceBounds?.right) - Number(deviceBounds?.left);
  const deviceHeight = Number(deviceBounds?.bottom) - Number(deviceBounds?.top);
  if (![frameWidth, frameHeight, deviceWidth, deviceHeight, clientX, clientY].every(Number.isFinite)
      || frameWidth <= 0 || frameHeight <= 0 || deviceWidth <= 0 || deviceHeight <= 0) return null;
  return {
    x: Number(deviceBounds.left) + ((clientX - Number(frameRect.left)) / frameWidth) * deviceWidth,
    y: Number(deviceBounds.top) + ((clientY - Number(frameRect.top)) / frameHeight) * deviceHeight
  };
}

export function pickNodeAt(snapshot, displayId, x, y) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
  const windows = new Map((snapshot?.windows ?? [])
    .filter(window => window.displayId === displayId)
    .map(window => [window.id, window]));
  return Object.values(snapshot?.nodes ?? {})
    .map(node => ({ node, window: windows.get(node.windowId), hit: positiveRect(node.visibleBounds) ?? positiveRect(node.bounds) }))
    .filter(value => value.window && value.hit && containsPoint(value.hit, x, y))
    .sort(compareHitCandidates)
    .at(0)?.node ?? null;
}

function compareHitCandidates(left, right) {
  return right.window.layer - left.window.layer
    || right.node.depth - left.node.depth
    || Number(right.node.drawingOrder ?? Number.MIN_SAFE_INTEGER) - Number(left.node.drawingOrder ?? Number.MIN_SAFE_INTEGER)
    || rectArea(left.hit) - rectArea(right.hit)
    || left.node.id.localeCompare(right.node.id);
}

function positiveRect(value) {
  if (!value) return null;
  const numbers = [value.left, value.top, value.right, value.bottom].map(Number);
  if (!numbers.every(Number.isFinite) || numbers[2] <= numbers[0] || numbers[3] <= numbers[1]) return null;
  return { left: numbers[0], top: numbers[1], right: numbers[2], bottom: numbers[3] };
}

function containsPoint(bounds, x, y) {
  return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
}

function rectArea(bounds) {
  return (bounds.right - bounds.left) * (bounds.bottom - bounds.top);
}
```

- [ ] **Step 4: Run the Web tests and verify GREEN**

Run: `npm run test:web`

Expected: all tests pass, including the three new 2D hit-test cases.

- [ ] **Step 5: Commit Task 1**

```bash
git add plugin/src/main/resources/web/inspector-core.js plugin/src/test/js/inspector-core.test.mjs
git commit -m "feat: select views from preview coordinates"
```

---

### Task 2: Wire preview selection to tree and properties

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/test/js/inspector-core.test.mjs`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/styles.css`

**Interfaces:**
- Consumes: Task 1 `previewPoint()` and `pickNodeAt()`; existing `selectNodeId(id)` state transition.
- Produces: `outsideViewport(itemRect, containerRect): boolean`, one screen-frame click handler, stable `data-node-id` tree rows, and preview-originated tree reveal.

- [ ] **Step 1: Add the failing viewport-reveal decision test**

Import `outsideViewport` and add:

```js
test('tree reveal is requested only when the selected row is outside its viewport', () => {
  const viewport = rect(0, 100, 300, 500);
  assert.equal(outsideViewport(rect(0, 120, 300, 160), viewport), false);
  assert.equal(outsideViewport(rect(0, 80, 300, 120), viewport), true);
  assert.equal(outsideViewport(rect(0, 480, 300, 520), viewport), true);
});
```

- [ ] **Step 2: Run the Web tests and verify RED**

Run: `npm run test:web`

Expected: FAIL because `outsideViewport` is not exported.

- [ ] **Step 3: Implement and verify the pure viewport helper**

Add to `inspector-core.js`:

```js
export function outsideViewport(item, container) {
  if (!item || !container) return false;
  return item.top < container.top || item.bottom > container.bottom
    || item.left < container.left || item.right > container.right;
}
```

Run: `npm run test:web`

Expected: all tests pass.

- [ ] **Step 4: Wire the 2D screen-frame click through the shared selection path**

In `app.js`, import `outsideViewport`, `pickNodeAt`, and `previewPoint`. In `bind()` add:

```js
elements['screen-frame'].addEventListener('click', event => {
  if (!state.snapshot || state.stage !== '2d') return;
  const windows = buildWindowLayers(state.snapshot, state.displayId);
  const bounds = unionBounds(windows.map(window => window.bounds));
  const point = previewPoint(elements['screen-frame'].getBoundingClientRect(), bounds, event.clientX, event.clientY);
  const node = point && pickNodeAt(state.snapshot, state.displayId, point.x, point.y);
  if (node) selectNodeId(node.id, true);
});
```

In `appendNode()` attach a stable identifier:

```js
row.dataset.nodeId = id;
```

Replace `selectNodeId` with:

```js
function selectNodeId(id, revealTree = false) {
  const node = state.snapshot.nodes[id];
  if (!node) return;
  state.expanded.add(node.windowId);
  state = { ...state, ...selectNode(state, id), selectedWindowId: node.windowId };
  render();
  if (revealTree) revealTreeNode(id);
}

function revealTreeNode(id) {
  const row = [...elements.tree.querySelectorAll('[data-node-id]')]
    .find(value => value.dataset.nodeId === id);
  if (row && outsideViewport(row.getBoundingClientRect(), elements.tree.getBoundingClientRect())) {
    row.scrollIntoView({ block: 'nearest' });
  }
}
```

Create each `node-box` with `el('div', ...)`, set `aria-hidden="true"`, and remove its `type` and click listener so the frame is the only hit-test owner.

- [ ] **Step 5: Make overlay boxes visual-only**

Change `styles.css` to:

```css
.screen-frame { cursor: crosshair; }
.node-box { position: absolute; pointer-events: none; border: 1px solid rgba(34, 104, 149, .55); background: rgba(34, 104, 149, .045); }
```

Remove the now-inapplicable `.node-box:hover` rule. Preserve `.node-box.selected` and `.node-box.finding`.

- [ ] **Step 6: Run Web tests and commit Task 2**

Run: `npm run test:web`

Expected: all tests pass.

```bash
git add plugin/src/main/resources/web/inspector-core.js plugin/src/test/js/inspector-core.test.mjs plugin/src/main/resources/web/app.js plugin/src/main/resources/web/styles.css
git commit -m "feat: synchronize preview view selection"
```

---

### Task 3: Correct 3D horizontal drag direction

**Files:**
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/test/js/inspector-core.test.mjs`
- Modify: `plugin/src/main/resources/web/app.js`

**Interfaces:**
- Consumes: initial `rotationX`, initial `rotationZ`, horizontal delta, and vertical delta.
- Produces: `dragRotation(rotationX, rotationZ, deltaX, deltaY): { rotationX, rotationZ }`.

- [ ] **Step 1: Add the failing drag-direction and clamp test**

Import `dragRotation` and add:

```js
test('3D drag follows horizontal pointer direction and preserves vertical clamps', () => {
  assert.deepEqual(dragRotation(58, -27, 40, 20), { rotationX: 53, rotationZ: -37 });
  assert.deepEqual(dragRotation(20, -27, 0, 100), { rotationX: 18, rotationZ: -27 });
  assert.deepEqual(dragRotation(75, -27, 0, -100), { rotationX: 78, rotationZ: -27 });
});
```

- [ ] **Step 2: Run the Web tests and verify RED**

Run: `npm run test:web`

Expected: FAIL because `dragRotation` is not exported.

- [ ] **Step 3: Implement the minimal rotation calculation**

Add to `inspector-core.js`:

```js
export function dragRotation(rotationX, rotationZ, deltaX, deltaY) {
  return {
    rotationX: Math.max(18, Math.min(78, rotationX - deltaY * .25)),
    rotationZ: rotationZ - deltaX * .25
  };
}
```

- [ ] **Step 4: Use the calculation in the pointer handler**

Import `dragRotation` in `app.js` and replace the `pointermove` calculations with:

```js
const rotation = dragRotation(
  drag.rx,
  drag.rz,
  event.clientX - drag.x,
  event.clientY - drag.y
);
state.rotationX = rotation.rotationX;
state.rotationZ = rotation.rotationZ;
updateSceneTransform();
```

- [ ] **Step 5: Run tests and commit Task 3**

Run: `npm run test:web`

Expected: all tests pass, including rightward drag changing `rotationZ` from `-27` to `-37`.

```bash
git add plugin/src/main/resources/web/inspector-core.js plugin/src/test/js/inspector-core.test.mjs plugin/src/main/resources/web/app.js
git commit -m "fix: align 3d drag with pointer direction"
```

---

### Task 4: Browser verification, plugin build, and installation

**Files:**
- Verify: `plugin/src/main/resources/web/app.js`
- Verify: `plugin/src/main/resources/web/inspector-core.js`
- Build: `plugin/build/distributions/plugin-0.1.0.zip`
- Install: `~/Library/Application Support/Google/AndroidStudio2026.1.3/plugins/android-ui-inspector`

**Interfaces:**
- Consumes: completed Tasks 1–3 and the existing emulator sample target.
- Produces: verified browser behavior and an Android Studio-compatible plugin archive installed on disk.

- [ ] **Step 1: Run the complete automated verification**

Run:

```bash
npm run test:web
./gradlew test assemble :plugin:buildPlugin :plugin:verifyPlugin
git diff --check
```

Expected: Web tests all pass, Gradle reports `BUILD SUCCESSFUL`, Plugin Verifier reports `Compatible`, and `git diff --check` prints nothing.

- [ ] **Step 2: Start the existing loopback-only production-stack browser harness**

Ensure the sample has only its Activity Window for an unambiguous title hit:

```bash
/Users/xianxiaoli/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am force-stop com.company.uiinspector.sample
/Users/xianxiaoli/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -n com.company.uiinspector.sample/.MainActivity
```

Compile and run the existing `/private/tmp/UIInspectorBrowserHarness.java` against the fresh production artifacts:

```bash
javac -cp plugin/build/libs/plugin-0.1.0.jar:protocol/build/libs/protocol-0.1.0.jar:/Users/xianxiaoli/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2/48b8230771e573b54ce6e867a9001e75977fe78e/gson-2.13.2.jar /private/tmp/UIInspectorBrowserHarness.java
java -cp /private/tmp:plugin/build/libs/plugin-0.1.0.jar:protocol/build/libs/protocol-0.1.0.jar:/Users/xianxiaoli/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2/48b8230771e573b54ce6e867a9001e75977fe78e/gson-2.13.2.jar UIInspectorBrowserHarness /Users/xianxiaoli/ClaudeProjects/UIInspector
```

Open the printed `127.0.0.1` URL in the in-app browser.

Expected: the device list contains `emulator-5554` and the process list contains `com.company.uiinspector.sample`.

- [ ] **Step 3: Verify 2D preview selection synchronization**

Capture `com.company.uiinspector.sample`, switch to 2D, and click the center of the known `TextView · title` bounds in the rendered preview.

Expected simultaneously:

- exactly one left tree row has `aria-selected="true"` and `data-node-id` matching the title node;
- the selected row is within the tree viewport;
- the right panel title is `TextView` and its `resourceId` is `com.company.uiinspector.sample:id/title`;
- exactly one `.node-box.selected` represents the same node.

Click an empty point outside all positive node bounds.

Expected: selection remains on the title node.

- [ ] **Step 4: Verify 3D horizontal drag direction**

Switch to 3D Window layers. Record the scene's `rotateZ`, drag the stage 80 CSS pixels to the right, and read the transform again.

Expected: `rotateZ` decreases by 20 degrees; vertical rotation and scale are unchanged for a horizontal-only drag.

- [ ] **Step 5: Check browser errors and preserve the final page**

Read browser error logs.

Expected: no page-level errors. Keep the verified inspector tab open as the deliverable.

- [ ] **Step 6: Install and checksum the final plugin**

Run:

```bash
INSTALL_TMP="$(mktemp -d /private/tmp/uiinspector-install.XXXXXX)"
ditto -x -k plugin/build/distributions/plugin-0.1.0.zip "$INSTALL_TMP"
ditto "$INSTALL_TMP/plugin" "/Users/xianxiaoli/Library/Application Support/Google/AndroidStudio2026.1.3/plugins/android-ui-inspector"
shasum -a 256 plugin/build/libs/plugin-0.1.0.jar "/Users/xianxiaoli/Library/Application Support/Google/AndroidStudio2026.1.3/plugins/android-ui-inspector/lib/plugin-0.1.0.jar"
```

Expected: both hashes are identical. Preserve the existing `gradle.properties` modification.

- [ ] **Step 7: Report final repository state**

Run:

```bash
git status --short --branch
git log -4 --oneline
```

Expected: only the pre-existing unstaged `gradle.properties` modification remains; the feature commits are present on `codex/android-studio-ui-inspector`.
