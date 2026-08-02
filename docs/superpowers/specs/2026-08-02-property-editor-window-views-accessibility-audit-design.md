# Property Editor, Window View Planes, and Accessibility Audit Design

## Scope

This change fixes property editing inside lower property groups, renders each Window's Views on its 3D Window plane, and audits the current captured tree for accessibility participation and missing configuration. It does not add Compose support, business SDK integration, or new capture backends.

## Property editor stability

`renderProperties()` currently rebuilds every `<details>` element and reopens only the three default groups. Clicking an edit button therefore closes a manually opened lower group and changes the scroll geometry.

The browser state will keep explicit group open/closed values keyed by target id and group name. A new group uses the existing defaults; a user toggle overrides the default. Property rerenders capture and restore the property panel's `scrollTop`, so opening an editor does not move the selected row away from the user.

## 3D Window rendering

Window mode keeps one Z position per Android Window layer. Each Window plane contains a flat overlay of all nodes whose `windowId` matches that Window. Node rectangles use screen bounds normalized into the Window bounds. Nodes inside a Window share the Window's Z plane; View depth does not add 3D separation.

The Window surface remains selectable. Each View rectangle is separately selectable and stops the Window click from firing. Selecting a View updates the left tree, 3D highlight, and right property panel through the existing `selectNodeId()` flow. Existing anomaly highlights also appear on the View rectangle.

## Accessibility capture and audit

Both capture paths expose `importantForAccessibilityResolved`:

- JVMTI reads `View.isImportantForAccessibility()` while retaining the raw `importantForAccessibility` integer.
- Accessibility reads `AccessibilityNodeInfo.isImportantForAccessibility()` while retaining its existing semantic properties.

The browser audits visible nodes only. A node is classified as:

- `enabled`: resolved as important for accessibility.
- `disabled`: resolved as not important for accessibility.
- `issue`: an enabled, interactive node has no accessible name.

An accessible name is present when at least one of `contentDescription`, `text`, `hint`, `paneTitle`, or `accessibilityPaneTitle` is non-empty. Interactive means clickable, long-clickable, checkable, editable, screen-reader-focusable, or focusable. Missing-name findings explain which properties should be configured; they are heuristic diagnostics, not a claim that Android's full Accessibility Scanner has run.

Nodes with semantic boolean `importantForAccessibility` remain supported. For older snapshots without the resolved property, raw values `2` (`NO`) and `4` (`NO_HIDE_DESCENDANTS`) are disabled, `1` (`YES`) is enabled, and `AUTO` falls back to interaction/content evidence.

## Accessibility audit interaction

The right panel gains a compact, always-available “无障碍审计” section after a snapshot is loaded. Three filter buttons show counts for enabled, disabled, and missing-configuration nodes. The default filter is missing configuration when any exists, otherwise enabled. Each result is a button that selects and reveals the View in the existing tree and property panel.

The section follows the current inspector palette and typography. It adds no modal, route, export, or automatic mutation behavior.

## Testing

- Pure browser tests cover persistent group state, Window-to-View grouping/normalization, and accessibility classification.
- Android unit tests verify both capture paths emit the normalized accessibility property.
- Existing Web and Gradle suites must remain green.
- Browser verification reproduces lower-group editing, selects a View from a 3D Window plane, and navigates all three accessibility filters.

## Self-review

The design covers all three requested behaviors without adding unrelated features. “Enabled” refers to accessibility importance, not `View.isEnabled()`. Missing configuration is intentionally limited to accessible naming because the captured tree cannot prove every TalkBack gesture or announcement behavior without running a device-side accessibility test framework.
