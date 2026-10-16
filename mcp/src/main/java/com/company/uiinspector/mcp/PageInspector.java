package com.company.uiinspector.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One-shot page inspection: resolve deep link -> adb am start -> wait -> capture -> extract items. */
public final class PageInspector {

    public interface AdbRunner {
        String run(List<String> args) throws Exception;
    }

    private static final class SystemAdbRunner implements AdbRunner {
        private final String adb;
        SystemAdbRunner(String adb) { this.adb = adb == null || adb.isBlank() ? "adb" : adb; }
        @Override public String run(List<String> args) throws Exception {
            List<String> cmd = new ArrayList<>(args.size() + 1);
            cmd.add(adb);
            cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) { in.transferTo(out); }
            int code = p.waitFor();
            if (code != 0) throw new java.io.IOException("adb " + args.get(0) + " failed: " + out.toString(StandardCharsets.UTF_8));
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private final InspectorClient client;
    private final AdbRunner adb;

    public PageInspector(InspectorClient client) { this(client, new SystemAdbRunner(System.getenv("UI_INSPECTOR_ADB"))); }
    PageInspector(InspectorClient client, AdbRunner adb) { this.client = client; this.adb = adb == null ? new SystemAdbRunner(null) : adb; }

    /** Run an adb command (args = full adb args, e.g. -s <serial> shell ...) and return combined stdout+stderr. */
    public String runAdb(java.util.List<String> args) throws Exception {
        return adb.run(args);
    }

    public static String resolveDeepLink(String target) {
        if (target == null || target.isBlank()) return null;
        String t = target.trim();
        if (t.contains("://")) return t;
        String k = t.toLowerCase(Locale.ROOT);
        switch (k) {
            case "设置", "设置页", "setting", "settings", "elfsetting", "elfsettingfragment",
                 "com.changan.aiassist.setting.view.elfsettingfragment":
                return "aiassist://com.changan.aiassist?target=com.changan.aiassist.setting.view.ElfSettingFragment";
            case "首页", "主页面", "home", "main", "center":
                return "aiassist://com.changan.aiassist?target=com.changan.aiassist.center.view.ElfCenterFragment";
            case "学校", "school", "elfschoolhomepage":
                return "aiassist://com.changan.aiassist?target=com.changan.aiassist.school.view.ElfSchoolHomePageFragment";
            case "人脸", "face", "faceid":
                return "aiassist://com.changan.aiassist?target=face.register.page.start";
            case "场景", "scene":
                return "aiassist://com.changan.aiassist?target=scene.customization.start";
            default:
                return null;
        }
    }

    public static JsonArray knownTargets() {
        JsonArray arr = new JsonArray();
        arr.add(entry("设置页", "aiassist://com.changan.aiassist?target=com.changan.aiassist.setting.view.ElfSettingFragment"));
        arr.add(entry("首页", "aiassist://com.changan.aiassist?target=com.changan.aiassist.center.view.ElfCenterFragment"));
        arr.add(entry("学校", "aiassist://com.changan.aiassist?target=com.changan.aiassist.school.view.ElfSchoolHomePageFragment"));
        arr.add(entry("人脸", "aiassist://com.changan.aiassist?target=face.register.page.start"));
        arr.add(entry("场景", "aiassist://com.changan.aiassist?target=scene.customization.start"));
        return arr;
    }

    private static JsonObject entry(String label, String deepLink) {
        JsonObject o = new JsonObject();
        o.addProperty("label", label);
        o.addProperty("deepLink", deepLink);
        return o;
    }

    public String startAndWait(String serial, String deepLink, String packageName, int waitMs) throws Exception {
        adb.run(List.of("-s", serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", deepLink));
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500L);
            String dump = adb.run(List.of("-s", serial, "shell", "dumpsys", "activity", "activities"));
            last = dump;
            if (packageName != null && !packageName.isBlank() && dump.contains(packageName)) return "foreground";
        }
        return "timeout";
    }

    public String captureProcessSnapshot(String serial, String packageName, String processName) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("serial", serial);
        body.addProperty("scope", "process");
        if (packageName != null && !packageName.isBlank()) body.addProperty("packageName", packageName);
        if (processName != null && !processName.isBlank()) body.addProperty("processName", processName);
        String json = client.capture(body.toString());
        if (isEmpty(json)) {
            Thread.sleep(1500L);
            json = client.capture(body.toString());
        }
        return json;
    }

    public static JsonObject extractItems(String snapshotJson) {
        JsonObject snapshot = JsonParser.parseString(snapshotJson).getAsJsonObject();
        JsonObject nodes = snapshot.getAsJsonObject("nodes");
        JsonArray windows = snapshot.has("windows") ? snapshot.getAsJsonArray("windows") : new JsonArray();
        JsonArray items = new JsonArray();
        int index = 0;
        if (nodes != null) {
            for (java.util.Map.Entry<String, JsonElement> e : nodes.entrySet()) {
                JsonObject n = e.getValue().getAsJsonObject();
                String text = str(n, "text");
                String cd = str(n, "contentDescription");
                String cls = str(n, "className");
                String rid = str(n, "resourceId");
                if (visible(n) && (notBlank(text) || notBlank(cd) || interactive(n))) {
                    JsonObject item = new JsonObject();
                    item.addProperty("index", index++);
                    item.addProperty("window", str(n, "windowId"));
                    item.addProperty("className", cls);
                    item.addProperty("resourceId", rid);
                    item.addProperty("text", text);
                    item.addProperty("contentDescription", cd);
                    addBounds(item, n);
                    items.add(item);
                }
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("pageWindows", windows.size());
        out.addProperty("pageNodes", nodes == null ? 0 : nodes.size());
        out.addProperty("itemsCount", items.size());
        out.add("items", items);
        return out;
    }
    private static boolean interactive(JsonObject n) {
        return propTrue(n, "clickable") || propTrue(n, "longClickable")
                || propTrue(n, "hasOnClickListeners") || propTrue(n, "checkable");
    }

    private static boolean propTrue(JsonObject node, String name) {
        JsonElement groups = node.get("propertyGroups");
        if (groups == null || !groups.isJsonArray()) return false;
        for (JsonElement ge : groups.getAsJsonArray()) {
            JsonObject g = ge.getAsJsonObject();
            JsonElement props = g.get("properties");
            if (props == null || !props.isJsonObject()) continue;
            JsonElement v = props.getAsJsonObject().get(name);
            if (v != null && v.isJsonObject() && v.getAsJsonObject().has("value")) {
                return "true".equalsIgnoreCase(v.getAsJsonObject().get("value").getAsString());
            }
        }
        return false;
    }

    private static boolean visible(JsonObject n) {
        JsonElement b = n.get("visibleBounds");
        if (b == null || !b.isJsonObject()) return false;
        JsonObject r = b.getAsJsonObject();
        return intOr(r, "right", 0) > intOr(r, "left", 0) && intOr(r, "bottom", 0) > intOr(r, "top", 0);
    }

    private static void addBounds(JsonObject item, JsonObject n) {
        JsonElement b = n.get("bounds");
        if (b == null || !b.isJsonObject()) return;
        JsonObject r = b.getAsJsonObject();
        int left = intOr(r, "left", 0), top = intOr(r, "top", 0);
        int width = Math.max(0, intOr(r, "right", 0) - left);
        int height = Math.max(0, intOr(r, "bottom", 0) - top);
        item.addProperty("bounds", left + "," + top + "," + width + "x" + height);
    }

    private static boolean isEmpty(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return root.has("windows") && root.getAsJsonArray("windows").isEmpty()
                    && root.has("nodes") && root.getAsJsonObject("nodes").entrySet().isEmpty();
        } catch (RuntimeException e) { return false; }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsInt();
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }
}

