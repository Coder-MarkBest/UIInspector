# 3D Content and Selection Synchronization Design

## Goal

Make Window Layer and View Depth useful for inspection by showing the captured UI pixels on every 3D plane and by guaranteeing that every selection entry point updates the property panel, structure tree, and canvas highlight together.

## Scope

- Reuse the screenshot and hierarchy already present in the current snapshot.
- Do not add a collector API, a new capture pass, per-View bitmap capture, or target-application integration.
- Support Window Layer, View Depth, 2D preview, and structure-tree selection.
- Preserve the current depth filter, rotation, zoom, anomaly markers, and property editing behavior.
- When a screenshot is unavailable, keep the existing structural plane with class/Window labels and selection styling.

## 3D Content Rendering

The full snapshot screenshot is the only pixel source. Each Window or View plane receives a CSS background slice calculated from:

- the plane's screen bounds;
- the display or selected Window bounds used by the 3D stage;
- the full screenshot data URL.

The background uses the same coordinate space as the existing plane geometry. `background-size` scales the complete screenshot across the containing bounds, while `background-position` shifts the relevant rectangle into the plane. The image does not repeat.

In Window Layer mode, each Window plane shows its screenshot region once. Existing View boxes remain transparent hit and outline regions above that texture, so the content is not duplicated for every child.

In View Depth mode, each View plane shows the screenshot region clipped to that View's bounds. Overlapping depth planes therefore show the actual captured content at their respective hierarchy levels.

Invalid, empty, or zero-sized bounds do not receive a texture. Secure screenshots and other unavailable screenshot states keep the current semantic fallback.

## Unified Selection Transaction

All selection entry points call one node-selection transaction:

- structure-tree View row;
- 2D coordinate picking;
- Window Layer View box;
- View Depth plane;
- anomaly result;
- accessibility-audit result.

The transaction validates the node ID, expands and selects its Window, updates the selected node, clears any incompatible editing state, renders the tree, stage, properties, findings, accessibility audit, diagnostics, and status, then reveals the tree row when requested.

Window-only selection remains a separate transaction because it has no View property target. Switching to View Depth from a Window-only selection chooses that Window's root View through the same node-selection transaction.

For a newly selected node, the property panel resets to the top. Re-rendering the same node for property editing or group expansion preserves its scroll position.

## 3D Picking and Rotation

A pointer press records its origin but does not immediately enter drag mode or capture the pointer. Movement must exceed 5 CSS pixels before rotation starts. Once the threshold is crossed, the stage captures the pointer, shows the grabbing cursor, and suppresses the click that would otherwise be generated at drag end.

A press and release below the threshold remains a normal click and selects the exact interactive View box or View Depth plane receiving the click. Empty-stage dragging continues to rotate the scene.

Depth filtering remains the supported way to expose a plane that is completely covered by a higher plane. No synthetic hit-cycling or reconstructed UI is added.

## Selection Visuals

The selected View must be unmistakable in both 3D modes:

- a two-pixel orange outline;
- a translucent orange surface tint that preserves screenshot visibility;
- an outer glow drawn above adjacent structural outlines;
- the existing class label remains readable;
- keyboard focus uses the same visible outline family.

Window Layer keeps the Window plane in its true layer position. Selecting a child highlights only the child View box; it does not mark the Window itself as the selected target.

## Failure Handling

- Missing screenshot: render structural planes and labels without a background image.
- Invalid bounds: skip the texture for that plane while retaining valid geometry behavior.
- Unknown node ID: ignore the selection request without clearing the current selection.
- Pointer cancellation or lost capture: clear drag state and do not synthesize a selection.

## Verification

Automated tests cover:

- screenshot background geometry for a full plane and a cropped View;
- absent screenshot and invalid bounds fallbacks;
- one selection reducer result shared by every View entry point;
- new-target property scroll reset and same-target preservation;
- the 5-pixel click/drag threshold and drag-click suppression;
- Window Layer and View Depth selected-state derivation.

Browser verification uses the current sample snapshot and checks both 3D modes:

- planes visibly contain screenshot pixels;
- clicking a component updates the selected property title, selected tree row, and selected canvas element to the same node ID;
- clicking a structure-tree row produces the same three-way synchronization;
- clicking without movement selects, while dragging rotates without changing selection.

The final build must pass Web tests, Android/JVM tests, plugin packaging, and the IntelliJ Plugin Verifier before installation into Android Studio.
