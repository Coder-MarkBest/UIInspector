# 2D Preview Selection and 3D Drag Direction Design

## Goal

Make the rendered device preview a reliable View picker and make horizontal dragging in the 3D stage move the scene in the same perceived direction as the pointer. Selecting a View from the preview must update the existing tree, property panel, and highlight through one shared selection path.

## Scope

This change is limited to browser interaction. It does not change Android collection, the snapshot protocol, property editing, anomaly rules, or the visual layout.

## 2D coordinate hit testing

The 2D screen frame owns the click interaction. Individual node overlays remain visual highlights but no longer compete for pointer events.

The click position is converted from the rendered screen-frame coordinate system back into the snapshot's screen coordinate system using the same union bounds and scale used by `render2d()`.

Candidates must:

- belong to the current display;
- contain the device coordinate in `visibleBounds` when it has a positive area, otherwise in `bounds`;
- have a positive hit area.

Candidate priority is deterministic:

1. higher Window layer;
2. deeper View depth;
3. higher `drawingOrder`, with missing values treated as the lowest value;
4. smaller hit area;
5. stable node ID ordering as the final tie-breaker.

If no View contains the coordinate, the current selection remains unchanged.

## Selection synchronization

Every successful preview hit calls the existing `selectNodeId()` selection path. That path remains responsible for setting both `selectedNodeId` and `selectedWindowId`, then rendering all three columns.

For preview-originated selection, the selected Window is added to the expanded set before rendering. After render, the matching tree row is scrolled into view only when it is outside the tree viewport. Existing property rendering and 2D selected-box styling then update from the same state.

Tree rows receive a stable node identifier in the DOM so the selected row can be located without matching display text. This identifier is internal browser metadata and does not change the snapshot schema.

## 3D drag direction

Pointer movement is converted to scene rotation by a pure calculation:

- vertical: preserve the current behavior and clamp `rotationX` to 18–78 degrees;
- horizontal: subtract horizontal pointer delta from the initial `rotationZ` instead of adding it;
- sensitivity remains `0.25` degrees per pixel;
- wheel zoom behavior is unchanged.

The pointer handler only applies the calculation and updates the existing scene transform.

## Error and edge handling

- Missing snapshots, empty displays, invalid bounds, and non-finite coordinates produce no hit.
- A click after a drag is not repurposed as View selection because only the 2D screen frame owns coordinate picking.
- Secure or unavailable screenshots remain selectable through captured node bounds because hit testing does not depend on image pixels.
- Search filtering is not cleared. If the selected row is filtered out, the View and properties still update without changing the user's search.

## Test strategy

Add pure browser-core tests before production changes:

- overlapping parent and child selects the deeper child;
- a node in a higher Window layer wins across Windows;
- `visibleBounds` limits the clickable region;
- drawing order and area produce deterministic ties;
- a miss returns no target;
- rightward pointer movement decreases `rotationZ` while vertical clamping is preserved.

Then run the existing Web test suite, full Gradle tests, plugin verification, and a browser interaction pass against the real captured sample snapshot. The browser pass must click a known View in 2D and verify the selected tree row, property title, and preview highlight agree; it must also drag horizontally in 3D and verify the scene transform changes in the pointer's perceived direction.
