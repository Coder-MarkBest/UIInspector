package com.company.uiinspector.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight index over a UIInspector snapshot JSON so MCP tools can query
 * nodes, locate nodes from a natural-language description, and audit
 * accessibility without depending on the live Android Studio plugin classes.
 */
public final class SnapshotIndex {

    public record Rect(int left, int top, int right, int bottom) {
        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        public String compact() { return left + "," + top + "," + (right - left) + "x" + (bottom - top); }
    }

    public record Node(
            String id,
            String windowId,
            String parentId,
            int depth,
            String className,
            String resourceId,
            String text,
            String contentDescription,
            Rect bounds,
            Rect visibleBounds,
            JsonObject raw) {
        boolean visible() {
            return visibleBounds != null && visibleBounds.width() > 0 && visibleBounds.height() > 0;
        }
        boolean hasContentDescription() {
            return contentDescription != null && !contentDescription.isBlank();
        }
        boolean hasText() {
            return text != null && !text.isBlank();
        }
        boolean isContainer() {
            String cls = className == null ? "" : className.toLowerCase(Locale.ROOT);
            return cls.contains("layout") || cls.contains("viewgroup") || cls.contains("scroll");
        }
    }

    public record Window(String id, String title, String packageName, String processName) {}

    private final JsonObject snapshot;
    private final List<Window> windows;
    private final Map<String, Node> nodes;
    private final List<Node> orderedNodes;

    public SnapshotIndex(String snapshotJson) {
        this.snapshot = JsonParser.parseString(snapshotJson).getAsJsonObject();
        this.windows = new ArrayList<>();
        this.nodes = new HashMap<>();
        List<Node> ordered = new ArrayList<>();
        JsonArray windowArr = snapshot.getAsJsonArray("windows");
        if (windowArr != null) {
            for (JsonElement e : windowArr) {
                JsonObject w = e.getAsJsonObject();
                windows.add(new Window(
                        str(w, "id"), str(w, "title"), str(w, "packageName"), str(w, "processName")));
            }
        }
        JsonObject nodeObj = snapshot.getAsJsonObject("nodes");
        if (nodeObj != null) {
            for (Map.Entry<String, JsonElement> entry : nodeObj.entrySet()) {
                JsonObject n = entry.getValue().getAsJsonObject();
                Node node = toNode(entry.getKey(), n);
                nodes.put(node.id(), node);
                ordered.add(node);
            }
        }
        this.orderedNodes = ordered;
    }

    public static SnapshotIndex fromJson(String json) {
        return new SnapshotIndex(json);
    }

    public List<Window> windows() { return windows; }
    public int nodeCount() { return nodes.size(); }
    public List<Node> nodes() { return orderedNodes; }
    public Node node(String id) { return nodes.get(id); }

    public String windowTitle(String windowId) {
        for (Window w : windows) if (w.id().equals(windowId)) return w.title();
        return null;
    }

    public List<Node> query(
            String text,
            String className,
            String resourceId,
            boolean noContentDescription,
            boolean visibleOnly,
            String windowId,
            int limit) {
        List<Node> result = new ArrayList<>();
        String textQ = norm(text);
        String classQ = norm(className);
        String resQ = norm(resourceId);
        for (Node n : orderedNodes) {
            if (windowId != null && !windowId.isBlank() && !windowId.equals(n.windowId())) continue;
            if (visibleOnly && !n.visible()) continue;
            if (noContentDescription && (n.hasContentDescription() || n.hasText())) continue;
            if (textQ != null && !norm(n.text()).contains(textQ)) continue;
            if (classQ != null && !norm(n.className()).contains(classQ)) continue;
            if (resQ != null && !norm(n.resourceId()).contains(resQ)) continue;
            result.add(n);
            if (result.size() >= Math.max(1, limit)) break;
        }
        return result;
    }

    /** Natural-language/loose matching. Keywords are matched against every visible-ish node's text,
     *  contentDescription, className, resourceId and window title; results are ranked by hit count. */
    public List<LocateHit> locate(String description, int limit) {
        String desc = description == null ? "" : description.trim();
        if (desc.isEmpty()) return List.of();
        String normDesc = norm(desc);
        List<String> terms = tokenize(desc);
        List<LocateHit> hits = new ArrayList<>();
        for (Node n : orderedNodes) {
            if (!n.visible()) continue;
            int score = 0;
            Map<String, List<String>> matched = new HashMap<>();
            String text = norm(n.text());
            String descr = norm(n.contentDescription());
            String cls = norm(n.className());
            String res = norm(n.resourceId());
            String title = norm(windowTitle(n.windowId()));
            // Whole-phrase containment / shared-substring matching so Chinese phrases
            // like "提交订单按钮" can hit a node whose contentDescription is "提交订单".
            score += scorePhrase(normDesc, text, matched, "text");
            score += scorePhrase(normDesc, descr, matched, "contentDescription");
            score += scorePhrase(normDesc, title, matched, "window");
            score += scorePhrase(normDesc, cls, matched, "className");
            score += scorePhrase(normDesc, res, matched, "resourceId");
            // Word-level hits keep ranking intuitive for English/android tokens.
            for (String term : terms) {
                if (term.isEmpty()) continue;
                if (text.contains(term)) { score += 3; add(matched, "text", term); }
                if (descr.contains(term)) { score += 3; add(matched, "contentDescription", term); }
                if (title.contains(term)) { score += 2; add(matched, "window", term); }
                if (cls.contains(term)) { score += 1; add(matched, "className", term); }
                if (res.contains(term)) { score += 1; add(matched, "resourceId", term); }
            }
            if (score > 0) hits.add(new LocateHit(n, score, matched));
        }
        if (hits.isEmpty()) return List.of();
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(Math.max(0, limit), hits.size()));
    }

    private static int scorePhrase(String phrase, String field, Map<String, List<String>> matched, String label) {
        if (phrase.isEmpty() || field.isEmpty()) return 0;
        if (field.equals(phrase)) {
            add(matched, label, "=" + phrase);
            return 8;
        }
        if (field.contains(phrase)) {
            add(matched, label, phrase);
            return 6;
        }
        if (phrase.contains(field)) {
            add(matched, label, field);
            return 4;
        }
        int shared = longestShared(field, phrase);
        if (shared >= 2) {
            add(matched, label, "~" + shared + "字");
            return 1 + shared;
        }
        return 0;
    }

    private static int longestShared(String a, String b) {
        int best = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int len = 1; i + len <= a.length() && len <= b.length(); len++) {
                if (b.contains(a.substring(i, i + len))) best = Math.max(best, len);
            }
        }
        return best;
    }

    public List<Node> accessibilityIssues(int limit) {
        List<Node> result = new ArrayList<>();
        for (Node n : orderedNodes) {
            if (!n.visible()) continue;
            if (n.isContainer() || n.hasContentDescription() || n.hasText()) continue;
            result.add(n);
            if (result.size() >= Math.max(1, limit)) break;
        }
        return result;
    }

    private static List<String> tokenize(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("[\\s,，。;；:：&/|+()\\[\\]{}\"'…]+")) {
            if (!part.isBlank()) out.add(norm(part));
        }
        return out;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String source, String token) {
        return norm(source).contains(token);
    }

    private static void add(Map<String, List<String>> map, String key, String value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private static Node toNode(String id, JsonObject n) {
        return new Node(
                id, str(n, "windowId"), str(n, "parentId"), intOr(n, "depth", 0),
                str(n, "className"), str(n, "resourceId"), str(n, "text"),
                str(n, "contentDescription"), rect(n, "bounds"), rect(n, "visibleBounds"), n);
    }

    private static Rect rect(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonObject()) return null;
        JsonObject r = e.getAsJsonObject();
        return new Rect(intOr(r, "left", 0), intOr(r, "top", 0), intOr(r, "right", 0), intOr(r, "bottom", 0));
    }

    private static String str(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static int intOr(JsonObject obj, String key, int fallback) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsInt();
    }

    public record LocateHit(Node node, int score, Map<String, List<String>> matched) {
        public JsonObject describe() {
            JsonObject o = new JsonObject();
            o.addProperty("nodeId", node.id());
            o.addProperty("score", score);
            o.addProperty("className", node.className());
            o.addProperty("resourceId", node.resourceId());
            o.addProperty("text", node.text());
            o.addProperty("contentDescription", node.contentDescription());
            o.addProperty("window", node.windowId());
            JsonObject m = new JsonObject();
            matched.forEach((k, v) -> m.addProperty(k, String.join(",", v)));
            o.add("matched", m);
            if (node.bounds() != null) o.addProperty("bounds", node.bounds().compact());
            if (node.visibleBounds() != null) o.addProperty("visibleBounds", node.visibleBounds().compact());
            return o;
        }
    }

    public JsonObject describeNode(Node n) {
        JsonObject o = new JsonObject();
        o.addProperty("nodeId", n.id());
        o.addProperty("window", n.windowId());
        o.addProperty("className", n.className());
        o.addProperty("resourceId", n.resourceId());
        o.addProperty("text", n.text());
        o.addProperty("contentDescription", n.contentDescription());
        if (n.bounds() != null) o.addProperty("bounds", n.bounds().compact());
        if (n.visibleBounds() != null) o.addProperty("visibleBounds", n.visibleBounds().compact());
        o.addProperty("depth", n.depth());
        return o;
    }
}
