package com.company.uiinspector.collector;

import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.CaptureSource;
import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.PropertyGroup;
import com.company.uiinspector.protocol.PropertyValue;
import com.company.uiinspector.protocol.Rect;
import com.company.uiinspector.protocol.UiNode;
import com.company.uiinspector.protocol.WindowSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AccessibilitySnapshotCollector {
    private static final List<String> GROUP_ORDER = List.of(
            "identity", "windowHierarchy", "geometryLayout", "renderingTransform",
            "content", "interactionState", "accessibility", "captureDiagnostics");

    public InspectionSnapshot collect(List<? extends WindowData> input, Request request) {
        List<WindowData> included = input.stream()
                .filter(window -> request.scope() == CaptureScope.GLOBAL
                        || request.packageName() == null
                        || request.packageName().equals(window.packageName()))
                .sorted(Comparator.comparingInt(WindowData::layer).thenComparingInt(WindowData::id))
                .map(window -> (WindowData) window)
                .toList();
        Set<Integer> includedIds = new LinkedHashSet<>();
        included.forEach(window -> includedIds.add(window.id()));

        Map<String, UiNode> nodes = new LinkedHashMap<>();
        List<WindowSnapshot> windows = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Counter counter = new Counter(request.maxNodes());

        for (WindowData window : included) {
            String windowId = windowId(window.id());
            String rootNodeId = null;
            if (window.root() != null && counter.hasRoom()) {
                rootNodeId = nodeId(window.id(), "0");
                collectNode(window.root(), window.id(), "0", null, 0, request, counter, nodes);
            }
            Integer parent = window.parentId();
            String parentId = parent != null && includedIds.contains(parent) ? windowId(parent) : null;
            List<String> childIds = window.childIds().stream()
                    .filter(includedIds::contains)
                    .map(AccessibilitySnapshotCollector::windowId)
                    .toList();
            Map<String, String> windowProperties = new LinkedHashMap<>();
            windowProperties.put("windowId", windowId);
            windowProperties.put("windowType", window.type());
            windowProperties.put("layer", Integer.toString(window.layer()));
            windowProperties.put("displayId", Integer.toString(window.displayId()));
            windowProperties.put("backend", "Accessibility");
            windows.add(new WindowSnapshot(
                    windowId, CaptureSource.SEMANTIC, window.packageName(), null,
                    window.displayId(), window.type(), window.layer(), parentId, childIds,
                    window.bounds(), window.active(), window.focused(), window.title(), rootNodeId,
                    group(windowProperties, "ACCESSIBILITY")));
        }

        if (counter.truncated) {
            diagnostics.add(new Diagnostic(
                    "WARN", "node_limit", "Accessibility node capture reached the configured limit."));
        }
        return new InspectionSnapshot(
                InspectionSnapshot.CURRENT_SCHEMA_VERSION,
                System.currentTimeMillis(),
                request.deviceSerial(),
                request.scope(),
                request.packageName(),
                null,
                List.of(),
                windows,
                nodes,
                diagnostics,
                null);
    }

    private static void collectNode(
            NodeData node,
            int window,
            String path,
            String parentId,
            int depth,
            Request request,
            Counter counter,
            Map<String, UiNode> output) {
        if (!counter.take()) {
            return;
        }
        String id = nodeId(window, path);
        List<String> childIds = new ArrayList<>();
        if (depth < request.maxDepth()) {
            for (int i = 0; i < node.children().size(); i++) {
                if (!counter.hasRoom()) {
                    counter.truncated = true;
                    break;
                }
                String childPath = path + "." + i;
                childIds.add(nodeId(window, childPath));
                collectNode(node.children().get(i), window, childPath, id, depth + 1, request, counter, output);
            }
        } else if (!node.children().isEmpty()) {
            counter.truncated = true;
        }

        Map<String, String> properties = new LinkedHashMap<>(node.properties());
        put(properties, "class", node.className());
        put(properties, "resourceId", node.resourceId());
        put(properties, "package", node.packageName());
        put(properties, "windowId", windowId(window));
        put(properties, "nodeId", id);
        put(properties, "parentId", parentId);
        properties.put("childCount", Integer.toString(childIds.size()));
        properties.put("depth", Integer.toString(depth));
        properties.put("boundsInScreen", format(node.bounds()));
        put(properties, "visibleBoundsInScreen", format(node.visibleBounds()));
        put(properties, "text", node.text());
        put(properties, "contentDescription", node.contentDescription());
        put(properties, "drawingOrder", node.drawingOrder() == null ? null : node.drawingOrder().toString());
        properties.putIfAbsent("backend", "Accessibility");
        properties.putIfAbsent("fidelity", "semantic");

        output.put(id, new UiNode(
                id, CaptureSource.SEMANTIC, windowId(window), parentId, childIds, depth,
                node.className(), node.resourceId(), node.text(), node.contentDescription(),
                node.bounds(), node.visibleBounds(), node.drawingOrder(),
                group(properties, "ACCESSIBILITY")));
    }

    private static List<PropertyGroup> group(Map<String, String> properties, String source) {
        Map<String, LinkedHashMap<String, PropertyValue>> grouped = new LinkedHashMap<>();
        GROUP_ORDER.forEach(name -> grouped.put(name, new LinkedHashMap<>()));
        properties.forEach((name, value) -> {
            if (value != null) {
                grouped.get(groupFor(name)).put(name, new PropertyValue(value, source));
            }
        });
        return GROUP_ORDER.stream()
                .map(name -> new PropertyGroup(name, grouped.get(name)))
                .toList();
    }

    private static String groupFor(String name) {
        String key = name.toLowerCase();
        if (key.contains("backend") || key.contains("fidelity") || key.contains("warning") || key.contains("captured")) {
            return "captureDiagnostics";
        }
        if (key.contains("window") || key.contains("parent") || key.contains("child")
                || key.contains("depth") || key.contains("drawing") || key.contains("layer") || key.contains("display")) {
            return "windowHierarchy";
        }
        if (key.contains("bound") || key.equals("x") || key.equals("y") || key.contains("width")
                || key.contains("height") || key.contains("padding") || key.contains("margin")
                || key.contains("layout") || key.contains("gravity") || key.contains("size")) {
            return "geometryLayout";
        }
        if (key.contains("alpha") || key.contains("visibility") || key.contains("shown")
                || key.contains("elevation") || key.contains("translation") || key.contains("rotation")
                || key.contains("scale") || key.contains("pivot") || key.contains("clip")
                || key.contains("background") || key.contains("foreground") || key.contains("rend")) {
            return "renderingTransform";
        }
        if (key.contains("text") || key.contains("hint") || key.contains("description")
                || key.contains("image") || key.contains("drawable")) {
            return "content";
        }
        if (key.contains("click") || key.contains("focus") || key.contains("select")
                || key.contains("press") || key.contains("check") || key.contains("scroll")
                || key.contains("enabled") || key.contains("activated") || key.contains("editable")) {
            return "interactionState";
        }
        if (key.contains("accessibility") || key.contains("action") || key.contains("role")
                || key.contains("live") || key.contains("state")) {
            return "accessibility";
        }
        return "identity";
    }

    private static String windowId(int id) {
        return "a:w:" + id;
    }

    private static String nodeId(int window, String path) {
        return "a:n:" + window + ":" + path;
    }

    private static String format(Rect rect) {
        return rect == null ? null : "[" + rect.left() + "," + rect.top() + "][" + rect.right() + "," + rect.bottom() + "]";
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.putIfAbsent(key, value);
        }
    }

    public record Request(CaptureScope scope, String packageName, String deviceSerial, int maxNodes, int maxDepth) {
        public Request {
            if (maxNodes < 1 || maxDepth < 0) {
                throw new IllegalArgumentException("Capture limits must be positive.");
            }
        }
    }

    public interface WindowData {
        int id();
        int layer();
        int displayId();
        Integer parentId();
        List<Integer> childIds();
        String type();
        String title();
        String packageName();
        Rect bounds();
        boolean active();
        boolean focused();
        NodeData root();
    }

    public interface NodeData {
        String packageName();
        String className();
        String resourceId();
        String text();
        String contentDescription();
        Rect bounds();
        Rect visibleBounds();
        Integer drawingOrder();
        Map<String, String> properties();
        List<? extends NodeData> children();
    }

    private static final class Counter {
        private final int maximum;
        private int count;
        private boolean truncated;

        private Counter(int maximum) {
            this.maximum = maximum;
        }

        private boolean hasRoom() {
            return count < maximum;
        }

        private boolean take() {
            if (!hasRoom()) {
                truncated = true;
                return false;
            }
            count++;
            return true;
        }
    }
}
