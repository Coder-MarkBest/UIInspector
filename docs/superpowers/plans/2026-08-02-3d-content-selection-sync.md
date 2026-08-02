# 3D Content and Selection Synchronization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render existing screenshot pixels on 3D Window/View planes and make tree, property, and canvas selection behave as one synchronized interaction.

**Architecture:** Keep geometry and state logic in `inspector-core.js` as pure, testable functions. `app.js` applies screenshot slices to existing planes and routes every View selection through one state patch. The 3D pointer controller delays drag activation until movement exceeds five pixels, preserving normal click selection.

**Tech Stack:** Browser-native ES modules, DOM/CSS 3D transforms, Node.js test runner, Gradle IntelliJ Platform Plugin 2.x, Android Studio 2026.1.

## Global Constraints

- Reuse only the screenshot and hierarchy already present in the current snapshot.
- Do not add collector APIs, capture passes, per-View bitmap capture, dependencies, or target-application integration.
- Preserve the depth filter, rotation, zoom, anomaly markers, accessibility audit, and property editing behavior.
- Screenshot-unavailable and invalid-bounds cases must retain the structural plane and label fallback.
- A new node selection resets property scroll; same-node editing or expansion preserves it.
- Five CSS pixels is the exact drag activation threshold.

---

### Task 1: Screenshot slices for 3D planes

**Files:**
- Modify: `plugin/src/test/js/inspector-core.test.mjs`
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/styles.css`

**Interfaces:**
- Consumes: `bounds`, containing `contentBounds`, and `snapshot.screenshot.dataUrl`.
- Produces: `screenshotSlice(bounds, contentBounds, dataUrl): null | { dataUrl, sizeX, sizeY, positionX, positionY }`.

- [ ] **Step 1: Write failing screenshot-slice tests**

Add `screenshotSlice` to the test import and add:

```js
test('3D screenshot slices map a View back to the containing screenshot', () => {
  assert.deepEqual(
    screenshotSlice(rect(200, 100, 400, 300), rect(0, 0, 800, 400), 'data:image/png;base64,abc'),
    {
      dataUrl: 'data:image/png;base64,abc',
      sizeX: 400,
      sizeY: 200,
      positionX: 100 / 3,
      positionY: 50
    }
  );
  assert.deepEqual(
    screenshotSlice(rect(0, 0, 800, 400), rect(0, 0, 800, 400), 'data:image/png;base64,abc'),
    { dataUrl: 'data:image/png;base64,abc', sizeX: 100, sizeY: 100, positionX: 0, positionY: 0 }
  );
});

test('3D screenshot slices reject missing pixels and invalid geometry', () => {
  assert.equal(screenshotSlice(rect(0, 0, 10, 10), rect(0, 0, 20, 20), ''), null);
  assert.equal(screenshotSlice(rect(0, 0, 0, 10), rect(0, 0, 20, 20), 'data:image/png;base64,abc'), null);
  assert.equal(screenshotSlice(rect(0, 0, 10, 10), rect(0, 0, 0, 20), 'data:image/png;base64,abc'), null);
});
```

- [ ] **Step 2: Run the Web tests and verify RED**

Run:

```bash
npm run test:web
```

Expected: module import failure because `screenshotSlice` is not exported.

- [ ] **Step 3: Implement the pure screenshot geometry**

Add to `inspector-core.js`:

```js
export function screenshotSlice(bounds, contentBounds, dataUrl) {
  if (typeof dataUrl !== 'string' || !dataUrl) return null;
  const item = positiveRect(bounds);
  const content = positiveRect(contentBounds);
  if (!item || !content) return null;
  const itemWidth = item.right - item.left;
  const itemHeight = item.bottom - item.top;
  const contentWidth = content.right - content.left;
  const contentHeight = content.bottom - content.top;
  return {
    dataUrl,
    sizeX: contentWidth / itemWidth * 100,
    sizeY: contentHeight / itemHeight * 100,
    positionX: contentWidth === itemWidth ? 0 : (item.left - content.left) / (contentWidth - itemWidth) * 100,
    positionY: contentHeight === itemHeight ? 0 : (item.top - content.top) / (contentHeight - itemHeight) * 100
  };
}
```

- [ ] **Step 4: Apply screenshot slices to Window and View planes**

Import `screenshotSlice` in `app.js`. In `render3d(items)`, after resolving `itemBounds`, calculate:

```js
const slice = screenshotSlice(itemBounds, bounds, state.snapshot.screenshot?.dataUrl);
if (slice) {
  plane.classList.add('has-screenshot');
  plane.style.backgroundImage = `url("${slice.dataUrl}")`;
  plane.style.backgroundSize = `${slice.sizeX}% ${slice.sizeY}%`;
  plane.style.backgroundPosition = `${slice.positionX}% ${slice.positionY}%`;
  plane.style.backgroundRepeat = 'no-repeat';
}
```

Keep Window View boxes transparent above the Window texture. In View Depth, each plane receives its own clipped screenshot slice.

Change 3D plane hover/selected CSS to use `background-color` instead of the `background` shorthand so interaction styles cannot erase the inline image. Add a subtle translucent wash through `background-blend-mode: soft-light` only on `.has-screenshot`.

- [ ] **Step 5: Run tests and commit Task 1**

Run `npm run test:web`; expect all tests to pass.

```bash
git add plugin/src/test/js/inspector-core.test.mjs plugin/src/main/resources/web/inspector-core.js plugin/src/main/resources/web/app.js plugin/src/main/resources/web/styles.css
git commit -m "feat: render screenshot content on 3d planes"
```

---

### Task 2: One View-selection transaction

**Files:**
- Modify: `plugin/src/test/js/inspector-core.test.mjs`
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`

**Interfaces:**
- Consumes: current snapshot, current UI state, and a candidate node ID.
- Produces: `selectNode(snapshot, state, nodeId): null | { selectedNodeId, selectedWindowId, expanded, editingProperty }`.

- [ ] **Step 1: Replace the trivial selection test with state-transaction tests**

```js
test('View selection updates node, Window, expansion, and incompatible editing together', () => {
  const input = snapshot();
  const current = {
    selectedNodeId: 'root',
    selectedWindowId: 'activity',
    expanded: new Set(['activity']),
    editingProperty: { id: 'root\u0000alpha' }
  };
  const result = selectNode(input, current, 'dialog-root');
  assert.equal(result.selectedNodeId, 'dialog-root');
  assert.equal(result.selectedWindowId, 'dialog');
  assert.deepEqual([...result.expanded], ['activity', 'dialog']);
  assert.equal(result.editingProperty, null);
  assert.equal(selectNode(input, current, 'missing'), null);
});

test('reselecting the same View preserves its active property editor', () => {
  const input = snapshot();
  const editor = { id: 'child\u0000alpha' };
  assert.equal(selectNode(input, {
    selectedNodeId: 'child', selectedWindowId: 'activity', expanded: new Set(), editingProperty: editor
  }, 'child').editingProperty, editor);
});
```

- [ ] **Step 2: Run the Web tests and verify RED**

Run `npm run test:web`.

Expected: the existing two-argument `selectNode(state, id)` cannot return the required synchronized patch.

- [ ] **Step 3: Implement the synchronized state patch**

Replace `selectNode` in `inspector-core.js` with:

```js
export function selectNode(snapshot, state, nodeId) {
  const node = snapshot?.nodes?.[nodeId];
  if (!node) return null;
  const expanded = new Set(state?.expanded ?? []);
  expanded.add(node.windowId);
  return {
    selectedNodeId: node.id,
    selectedWindowId: node.windowId,
    expanded,
    editingProperty: state?.selectedNodeId === node.id ? state.editingProperty ?? null : null
  };
}
```

- [ ] **Step 4: Route every View entry point through `selectNodeId`**

Update `selectNodeId` in `app.js`:

```js
function selectNodeId(id, revealTree = false) {
  const selection = selectNode(state.snapshot, state, id);
  if (!selection) return;
  state = { ...state, ...selection };
  render();
  if (revealTree) revealTreeNode(id);
}
```

Keep tree rows, 2D hit testing, Window View boxes, View Depth planes, anomaly results, and accessibility results calling this function. Update `selectWindow`: when View Depth is active and the Window has a root node, call `selectNodeId(window.rootNodeId)` and return; otherwise clear `selectedNodeId` and incompatible editing before rendering.

Add `plane.dataset.nodeId = item.id` for View Depth planes so browser checks can compare the selected canvas node with the tree and property target.

- [ ] **Step 5: Run tests and commit Task 2**

Run `npm run test:web`; expect all tests to pass.

```bash
git add plugin/src/test/js/inspector-core.test.mjs plugin/src/main/resources/web/inspector-core.js plugin/src/main/resources/web/app.js
git commit -m "fix: synchronize View selection across inspector panels"
```

---

### Task 3: Reliable 3D click selection and visible highlight

**Files:**
- Modify: `plugin/src/test/js/inspector-core.test.mjs`
- Modify: `plugin/src/main/resources/web/inspector-core.js`
- Modify: `plugin/src/main/resources/web/app.js`
- Modify: `plugin/src/main/resources/web/styles.css`

**Interfaces:**
- Consumes: pointer origin/current coordinates and the fixed threshold.
- Produces: `isDragGesture(startX, startY, currentX, currentY, threshold = 5): boolean`.

- [ ] **Step 1: Write failing gesture-threshold tests**

```js
test('3D pointer movement becomes a drag only after five CSS pixels', () => {
  assert.equal(isDragGesture(10, 10, 13, 14), false);
  assert.equal(isDragGesture(10, 10, 16, 10), true);
  assert.equal(isDragGesture(10, 10, 10, 4), true);
});
```

Add `isDragGesture` to the test import.

- [ ] **Step 2: Run the Web tests and verify RED**

Run `npm run test:web`.

Expected: module import failure because `isDragGesture` is not exported.

- [ ] **Step 3: Implement the threshold helper**

```js
export function isDragGesture(startX, startY, currentX, currentY, threshold = 5) {
  return Math.hypot(Number(currentX) - Number(startX), Number(currentY) - Number(startY)) > threshold;
}
```

- [ ] **Step 4: Delay pointer capture until the gesture is a drag**

Replace the eager capture logic in `bindSceneControls` with the following state transitions; keep the existing wheel handler after them:

```js
function bindSceneControls() {
  let drag = null;
  const stage = elements['three-d-stage'];
  const clearDrag = () => {
    drag = null;
    stage.classList.remove('dragging');
  };
  const suppressGeneratedClick = () => {
    const suppress = event => {
      event.preventDefault();
      event.stopImmediatePropagation();
    };
    stage.addEventListener('click', suppress, { capture: true, once: true });
    setTimeout(() => stage.removeEventListener('click', suppress, true), 0);
  };

  stage.addEventListener('pointerdown', event => {
    drag = {
      x: event.clientX,
      y: event.clientY,
      rx: state.rotationX,
      rz: state.rotationZ,
      pointerId: event.pointerId,
      active: false
    };
  });
  stage.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (!drag.active) {
      if (!isDragGesture(drag.x, drag.y, event.clientX, event.clientY)) return;
      drag.active = true;
      stage.setPointerCapture(event.pointerId);
      stage.classList.add('dragging');
    }
    const rotation = dragRotation(drag.rx, drag.rz, event.clientX - drag.x, event.clientY - drag.y);
    state.rotationX = rotation.rotationX;
    state.rotationZ = rotation.rotationZ;
    updateSceneTransform();
  });
  stage.addEventListener('pointerup', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (drag.active) suppressGeneratedClick();
    clearDrag();
  });
  stage.addEventListener('pointercancel', clearDrag);
  stage.addEventListener('lostpointercapture', clearDrag);
  stage.addEventListener('wheel', event => {
    event.preventDefault();
    state.scale = Math.max(.45, Math.min(1.45, state.scale - Math.sign(event.deltaY) * .06));
    updateSceneTransform();
  }, { passive: false });
}
```

A sub-threshold press never installs suppression, so its target click reaches the View handler. Pointer cancellation and lost capture clear interaction state without selecting a View.

- [ ] **Step 5: Strengthen selected and keyboard-focus visuals**

In `styles.css`, preserve screenshot visibility and use the existing orange design token:

```css
.layer-plane.selected,
.window-view-box.selected {
  outline: 2px solid #df5e35;
  outline-offset: 2px;
  background-color: rgba(223, 94, 53, .18);
  box-shadow: 0 0 0 4px rgba(223, 94, 53, .18), 0 10px 24px rgba(92, 44, 25, .24);
}

.window-view-box:focus-visible,
.layer-plane:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 3px;
}
```

Do not alter Window or View `translateZ` values; the highlight is visual only and does not falsify hierarchy depth.

- [ ] **Step 6: Run tests and commit Task 3**

Run `npm run test:web`; expect all tests to pass.

```bash
git add plugin/src/test/js/inspector-core.test.mjs plugin/src/main/resources/web/inspector-core.js plugin/src/main/resources/web/app.js plugin/src/main/resources/web/styles.css
git commit -m "fix: make 3d View selection reliable"
```

---

### Task 4: Browser, build, and installed-plugin verification

**Files:**
- Verify: `plugin/src/main/resources/web/app.js`
- Verify: `plugin/src/main/resources/web/styles.css`
- Output: `plugin/build/distributions/plugin-0.1.0.zip`
- Install: `~/Library/Application Support/Google/AndroidStudio2026.1.3/plugins/android-ui-inspector`

**Interfaces:**
- Consumes: the current localhost sample snapshot and Gradle build.
- Produces: verified browser behavior, compatible ZIP, and matching installed JAR.

- [ ] **Step 1: Run all Web tests and source checks**

```bash
npm run test:web
git diff --check
git status --short --branch
```

Expected: all tests pass, no whitespace errors, and only planned changes before the final commit.

- [ ] **Step 2: Verify Window Layer in the browser**

Reload the localhost inspector so it reads current assets. Select 3D and Window Layer. Confirm through DOM state and a screenshot:

- the Window plane has a non-empty `background-image`;
- child View boxes remain visible above the pixels;
- clicking the unique `ImageView` produces the same node ID in `.window-view-box.selected`, `.tree-row[aria-selected="true"]`, and the property `nodeId` row;
- `#property-panel` has `scrollTop === 0` after changing from another component.

- [ ] **Step 3: Verify View Depth and tree selection in the browser**

Switch to View Depth and confirm:

- each valid plane has a non-empty `background-image`;
- clicking `HorizontalScrollView` synchronizes the selected plane, tree row, and property title;
- clicking the tree row for `large_image` synchronizes the selected View Depth plane and property title;
- a click without movement changes selection;
- a drag exceeding five pixels changes scene transform without changing selection.

Check browser error logs and require zero errors.

- [ ] **Step 4: Run the full Gradle verification**

```bash
./gradlew test assemble :plugin:buildPlugin :plugin:verifyPlugin --console=plain
```

Expected: `BUILD SUCCESSFUL` and Plugin Verifier reports `Compatible` for Android Studio 2026.1.

- [ ] **Step 5: Install and compare the plugin artifact**

Extract `plugin/build/distributions/plugin-0.1.0.zip` into a fresh `/private/tmp` directory, then copy the extracted `plugin` directory to:

```text
~/Library/Application Support/Google/AndroidStudio2026.1.3/plugins/android-ui-inspector
```

Compare SHA-256 for the built and installed `plugin-0.1.0.jar`; they must be identical.

- [ ] **Step 6: Final status check**

```bash
git status --short --branch
git log --oneline -5
```

Expected: a clean `codex/accessibility-audit-window-views` worktree containing the three implementation commits.
