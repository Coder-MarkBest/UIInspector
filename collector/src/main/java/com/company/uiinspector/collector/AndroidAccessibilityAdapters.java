package com.company.uiinspector.collector;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AndroidAccessibilityAdapters {
    private AndroidAccessibilityAdapters() {}

    static final class WindowAdapter implements AccessibilitySnapshotCollector.WindowData {
        private final AccessibilityWindowInfo window;
        private final int displayId;
        private NodeAdapter root;

        WindowAdapter(AccessibilityWindowInfo window, int displayId) {
            this.window = window;
            this.displayId = displayId;
        }

        @Override public int id() { return window.getId(); }
        @Override public int layer() { return window.getLayer(); }
        @Override public int displayId() { return displayId; }
        @Override public Integer parentId() {
            AccessibilityWindowInfo parent = window.getParent();
            return parent == null ? null : parent.getId();
        }
        @Override public List<Integer> childIds() {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < window.getChildCount(); i++) {
                AccessibilityWindowInfo child = window.getChild(i);
                if (child != null) ids.add(child.getId());
            }
            return ids;
        }
        @Override public String type() { return windowType(window.getType()); }
        @Override public String title() { return text(window.getTitle()); }
        @Override public String packageName() {
            NodeAdapter value = (NodeAdapter) root();
            return value == null ? null : value.packageName();
        }
        @Override public com.company.uiinspector.protocol.Rect bounds() {
            Rect rect = new Rect();
            window.getBoundsInScreen(rect);
            return value(rect);
        }
        @Override public boolean active() { return window.isActive(); }
        @Override public boolean focused() { return window.isFocused(); }
        @Override public AccessibilitySnapshotCollector.NodeData root() {
            if (root == null) {
                AccessibilityNodeInfo info = window.getRoot();
                root = info == null ? null : new NodeAdapter(info);
            }
            return root;
        }
    }

    static final class NodeAdapter implements AccessibilitySnapshotCollector.NodeData {
        private final AccessibilityNodeInfo node;

        NodeAdapter(AccessibilityNodeInfo node) {
            this.node = node;
        }

        @Override public String packageName() { return AndroidAccessibilityAdapters.text(node.getPackageName()); }
        @Override public String className() { return AndroidAccessibilityAdapters.text(node.getClassName()); }
        @Override public String resourceId() { return node.getViewIdResourceName(); }
        @Override public String text() { return AndroidAccessibilityAdapters.text(node.getText()); }
        @Override public String contentDescription() { return AndroidAccessibilityAdapters.text(node.getContentDescription()); }
        @Override public com.company.uiinspector.protocol.Rect bounds() {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            return value(rect);
        }
        @Override public com.company.uiinspector.protocol.Rect visibleBounds() { return bounds(); }
        @Override public Integer drawingOrder() { return node.getDrawingOrder(); }
        @Override public boolean importantForAccessibility() { return node.isImportantForAccessibility(); }
        @Override public Map<String, String> properties() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("enabled", Boolean.toString(node.isEnabled()));
            values.put("clickable", Boolean.toString(node.isClickable()));
            values.put("longClickable", Boolean.toString(node.isLongClickable()));
            values.put("focusable", Boolean.toString(node.isFocusable()));
            values.put("focused", Boolean.toString(node.isFocused()));
            values.put("accessibilityFocused", Boolean.toString(node.isAccessibilityFocused()));
            values.put("selected", Boolean.toString(node.isSelected()));
            values.put("checkable", Boolean.toString(node.isCheckable()));
            values.put("checked", Boolean.toString(node.isChecked()));
            values.put("scrollable", Boolean.toString(node.isScrollable()));
            values.put("editable", Boolean.toString(node.isEditable()));
            values.put("visibleToUser", Boolean.toString(node.isVisibleToUser()));
            values.put("importantForAccessibility", Boolean.toString(node.isImportantForAccessibility()));
            values.put("actions", node.getActionList().toString());
            values.put("hint", AndroidAccessibilityAdapters.text(node.getHintText()));
            values.put("paneTitle", AndroidAccessibilityAdapters.text(node.getPaneTitle()));
            values.put("error", AndroidAccessibilityAdapters.text(node.getError()));
            values.put("maxTextLength", Integer.toString(node.getMaxTextLength()));
            values.put("inputType", Integer.toString(node.getInputType()));
            if (Build.VERSION.SDK_INT >= 30) {
                values.put("stateDescription", AndroidAccessibilityAdapters.text(node.getStateDescription()));
            }
            values.values().removeIf(value -> value == null);
            return values;
        }
        @Override public List<NodeAdapter> children() {
            List<NodeAdapter> children = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) children.add(new NodeAdapter(child));
            }
            return children;
        }
    }

    private static String windowType(int type) {
        return switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER";
            default -> "TYPE_" + type;
        };
    }

    private static String text(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private static com.company.uiinspector.protocol.Rect value(Rect rect) {
        return new com.company.uiinspector.protocol.Rect(rect.left, rect.top, rect.right, rect.bottom);
    }
}
