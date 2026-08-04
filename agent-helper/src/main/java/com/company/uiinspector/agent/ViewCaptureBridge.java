package com.company.uiinspector.agent;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.CaptureSource;
import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.PropertyGroup;
import com.company.uiinspector.protocol.PropertyValue;
import com.company.uiinspector.protocol.SnapshotJson;
import com.company.uiinspector.protocol.UiNode;
import com.company.uiinspector.protocol.WindowSnapshot;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ViewCaptureBridge {
    private static final String TAG = "UIInspectorAgent";
    private static final List<String> GROUPS = List.of(
            "identity", "windowHierarchy", "geometryLayout", "renderingTransform",
            "content", "interactionState", "accessibility", "captureDiagnostics");
    private static final int MAX_NODES = 20_000;
    private static final int MAX_DEPTH = 300;

    private ViewCaptureBridge() {}

    public static void capture(
            List<View> roots,
            List<WindowManager.LayoutParams> windowParams,
            String outputPath) {
        Log.i(TAG, "ViewCaptureBridge.capture called: roots=" + roots.size()
                + " output=" + outputPath + " thread=" + Thread.currentThread().getName());
        Runnable capture = () -> {
            Log.i(TAG, "captureNow starting on thread=" + Thread.currentThread().getName());
            long startMs = System.currentTimeMillis();
            String packageName = roots.isEmpty() ? null : roots.get(0).getContext().getPackageName();
            InspectionSnapshot snapshot = captureNow(
                    roots, windowParams, "", packageName, Application.getProcessName());
            Log.i(TAG, "captureNow finished: windows=" + snapshot.windows().size()
                    + " nodes=" + snapshot.nodes().size()
                    + " elapsedMs=" + (System.currentTimeMillis() - startMs));
            Thread writer = new Thread(() -> {
                write(snapshot, outputPath);
                Log.i(TAG, "Snapshot written to " + outputPath);
            }, "uiinspector-agent-writer");
            writer.setDaemon(true);
            writer.start();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.i(TAG, "Running capture directly on main thread");
            capture.run();
        } else {
            Log.i(TAG, "Posting capture to main thread handler");
            new Handler(Looper.getMainLooper()).post(capture);
        }
    }

    static InspectionSnapshot captureNow(
            List<View> roots,
            List<WindowManager.LayoutParams> windowParams,
            String deviceSerial,
            String packageName,
            String processName) {
        Map<String, UiNode> nodes = new LinkedHashMap<>();
        List<WindowSnapshot> windows = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Counter counter = new Counter();
        for (int index = 0; index < roots.size(); index++) {
            View root = roots.get(index);
            WindowManager.LayoutParams params = index < windowParams.size() ? windowParams.get(index) : null;
            String windowId = "v:w:" + index;
            String rootId = "v:n:" + index + ":0";
            collectView(root, windowId, rootId, null, 0, 0, nodes, diagnostics, counter);
            windows.add(new WindowSnapshot(
                    windowId,
                    CaptureSource.JVMTI,
                    packageName,
                    processName,
                    displayId(root),
                    params == null ? "APPLICATION" : windowType(params.type),
                    index,
                    null,
                    List.of(),
                    bounds(root),
                    root.hasWindowFocus(),
                    root.hasWindowFocus(),
                    params == null || params.getTitle() == null ? root.getClass().getSimpleName() : params.getTitle().toString(),
                    rootId,
                    group(windowProperties(windowId, params, root), "JVMTI",
                            property -> PropertyEditors.forWindow(root, params, property))));
        }
        if (counter.truncated) {
            diagnostics.add(new Diagnostic("WARN", "node_limit", "View capture reached its node or depth limit."));
        }
        return new InspectionSnapshot(
                1, System.currentTimeMillis(), deviceSerial, CaptureScope.TARGET_PROCESS,
                packageName, processName, List.of(), windows, nodes, diagnostics, null);
    }

    private static void collectView(
            View view,
            String windowId,
            String id,
            String parentId,
            int depth,
            int drawingOrder,
            Map<String, UiNode> output,
            List<Diagnostic> diagnostics,
            Counter counter) {
        if (counter.count++ >= MAX_NODES || depth > MAX_DEPTH) {
            counter.truncated = true;
            return;
        }
        List<String> childIds = new ArrayList<>();
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                String childId = id + "." + index;
                childIds.add(childId);
                try {
                    collectView(group.getChildAt(index), windowId, childId, id, depth + 1, index,
                            output, diagnostics, counter);
                } catch (RuntimeException error) {
                    diagnostics.add(new Diagnostic(
                            "WARN", "view_property_read", view.getClass().getName() + ": " + error.getClass().getSimpleName()));
                }
            }
        }
        Map<String, String> values = viewProperties(view, windowId, id, parentId, childIds.size(), depth, drawingOrder);
        output.put(id, new UiNode(
                id, CaptureSource.JVMTI, windowId, parentId, childIds, depth,
                view.getClass().getName(), resourceId(view), text(view), description(view),
                bounds(view), visibleBounds(view), drawingOrder,
                group(values, "JVMTI", property -> PropertyEditors.forView(view, property))));
    }

    private static Map<String, String> viewProperties(
            View view, String windowId, String nodeId, String parentId, int childCount, int depth, int drawingOrder) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("class", view.getClass().getName());
        put(values, "resourceId", resourceId(view));
        values.put("package", view.getContext().getPackageName());
        values.put("windowId", windowId);
        values.put("nodeId", nodeId);
        put(values, "parentId", parentId);
        values.put("childCount", Integer.toString(childCount));
        values.put("depth", Integer.toString(depth));
        values.put("drawingOrder", Integer.toString(drawingOrder));

        values.put("boundsInScreen", format(bounds(view)));
        values.put("visibleBoundsInScreen", format(visibleBounds(view)));
        values.put("x", floatValue(view.getX()));
        values.put("y", floatValue(view.getY()));
        values.put("layoutWidth", Integer.toString(view.getWidth()));
        values.put("layoutHeight", Integer.toString(view.getHeight()));
        values.put("measuredWidth", Integer.toString(view.getMeasuredWidth()));
        values.put("measuredHeight", Integer.toString(view.getMeasuredHeight()));
        values.put("paddingLeft", Integer.toString(view.getPaddingLeft()));
        values.put("paddingTop", Integer.toString(view.getPaddingTop()));
        values.put("paddingRight", Integer.toString(view.getPaddingRight()));
        values.put("paddingBottom", Integer.toString(view.getPaddingBottom()));
        values.put("minimumWidth", Integer.toString(view.getMinimumWidth()));
        values.put("minimumHeight", Integer.toString(view.getMinimumHeight()));
        addLayoutParams(values, view.getLayoutParams());

        values.put("visibility", visibility(view.getVisibility()));
        values.put("shown", Boolean.toString(view.isShown()));
        values.put("alpha", floatValue(view.getAlpha()));
        values.put("elevation", floatValue(view.getElevation()));
        values.put("z", floatValue(view.getZ()));
        values.put("translationX", floatValue(view.getTranslationX()));
        values.put("translationY", floatValue(view.getTranslationY()));
        values.put("translationZ", floatValue(view.getTranslationZ()));
        values.put("rotation", floatValue(view.getRotation()));
        values.put("rotationX", floatValue(view.getRotationX()));
        values.put("rotationY", floatValue(view.getRotationY()));
        values.put("scaleX", floatValue(view.getScaleX()));
        values.put("scaleY", floatValue(view.getScaleY()));
        values.put("pivotX", floatValue(view.getPivotX()));
        values.put("pivotY", floatValue(view.getPivotY()));
        values.put("clipToOutline", Boolean.toString(view.getClipToOutline()));
        values.put("layerType", Integer.toString(view.getLayerType()));
        put(values, "background", className(view.getBackground()));
        put(values, "foreground", className(view.getForeground()));
        put(values, "clipBounds", rect(view.getClipBounds()));

        put(values, "text", text(view));
        put(values, "contentDescription", description(view));
        if (view instanceof TextView textView) {
            put(values, "hint", chars(textView.getHint()));
            values.put("textSizePx", floatValue(textView.getTextSize()));
            values.put("textColor", String.format("#%08X", textView.getCurrentTextColor()));
            values.put("gravity", Integer.toString(textView.getGravity()));
            values.put("maxLines", Integer.toString(textView.getMaxLines()));
        }
        if (view instanceof ImageView imageView) {
            Drawable drawable = imageView.getDrawable();
            put(values, "drawableClass", className(drawable));
            if (drawable != null) {
                values.put("drawableIntrinsicWidth", Integer.toString(drawable.getIntrinsicWidth()));
                values.put("drawableIntrinsicHeight", Integer.toString(drawable.getIntrinsicHeight()));
            }
            put(values, "imageScaleType", imageView.getScaleType() == null ? null : imageView.getScaleType().name());
            if (drawable instanceof BitmapDrawable bitmapDrawable) {
                Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap != null) {
                    values.put("bitmapWidth", Integer.toString(bitmap.getWidth()));
                    values.put("bitmapHeight", Integer.toString(bitmap.getHeight()));
                    values.put("bitmapByteCount", Integer.toString(bitmap.getByteCount()));
                }
            }
        }

        values.put("enabled", Boolean.toString(view.isEnabled()));
        values.put("clickable", Boolean.toString(view.isClickable()));
        values.put("longClickable", Boolean.toString(view.isLongClickable()));
        values.put("focusable", Boolean.toString(view.isFocusable()));
        values.put("focused", Boolean.toString(view.isFocused()));
        values.put("selected", Boolean.toString(view.isSelected()));
        values.put("pressed", Boolean.toString(view.isPressed()));
        values.put("activated", Boolean.toString(view.isActivated()));
        values.put("contextClickable", Boolean.toString(view.isContextClickable()));
        values.put("hasOnClickListeners", Boolean.toString(view.hasOnClickListeners()));
        if (view instanceof Checkable checkable) {
            values.put("checkable", "true");
            values.put("checked", Boolean.toString(checkable.isChecked()));
        }

        values.put("importantForAccessibility", Integer.toString(view.getImportantForAccessibility()));
        values.put("importantForAccessibilityResolved", Boolean.toString(view.isImportantForAccessibility()));
        put(values, "accessibilityClass", chars(view.getAccessibilityClassName()));
        values.put("accessibilityLiveRegion", Integer.toString(view.getAccessibilityLiveRegion()));
        put(values, "accessibilityPaneTitle", chars(view.getAccessibilityPaneTitle()));
        if (Build.VERSION.SDK_INT >= 30) {
            put(values, "accessibilityStateDescription", chars(view.getStateDescription()));
        }
        values.put("screenReaderFocusable", Boolean.toString(view.isScreenReaderFocusable()));
        values.put("backend", "JVMTI");
        values.put("fidelity", "realView");
        return values;
    }

    private static Map<String, String> windowProperties(String id, WindowManager.LayoutParams params, View root) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("windowId", id);
        values.put("displayId", Integer.toString(displayId(root)));
        values.put("backend", "JVMTI");
        values.put("fidelity", "realView");
        if (params != null) {
            values.put("windowType", windowType(params.type));
            values.put("flags", String.format("0x%08X", params.flags));
            values.put("gravity", Integer.toString(params.gravity));
            values.put("x", Integer.toString(params.x));
            values.put("y", Integer.toString(params.y));
            values.put("layoutWidth", Integer.toString(params.width));
            values.put("layoutHeight", Integer.toString(params.height));
            values.put("format", Integer.toString(params.format));
            values.put("windowAlpha", floatValue(params.alpha));
            values.put("dimAmount", floatValue(params.dimAmount));
            values.put("softInputMode", Integer.toString(params.softInputMode));
        }
        return values;
    }

    static String currentViewProperty(View view, String propertyName) {
        int childCount = view instanceof ViewGroup group ? group.getChildCount() : 0;
        return viewProperties(view, "", "", null, childCount, 0, 0).get(propertyName);
    }

    static String currentWindowProperty(
            View root,
            WindowManager.LayoutParams params,
            String propertyName) {
        return windowProperties("", params, root).get(propertyName);
    }

    private static void addLayoutParams(Map<String, String> values, ViewGroup.LayoutParams params) {
        if (params == null) return;
        values.put("layoutParamsClass", params.getClass().getName());
        values.put("layoutParamWidth", Integer.toString(params.width));
        values.put("layoutParamHeight", Integer.toString(params.height));
        if (params instanceof ViewGroup.MarginLayoutParams margins) {
            values.put("marginLeft", Integer.toString(margins.leftMargin));
            values.put("marginTop", Integer.toString(margins.topMargin));
            values.put("marginRight", Integer.toString(margins.rightMargin));
            values.put("marginBottom", Integer.toString(margins.bottomMargin));
            values.put("marginStart", Integer.toString(margins.getMarginStart()));
            values.put("marginEnd", Integer.toString(margins.getMarginEnd()));
        }
    }

    private static List<PropertyGroup> group(
            Map<String, String> values,
            String source,
            Function<String, PropertyEditors.Description> editorLookup) {
        List<Map<String, PropertyValue>> maps = new ArrayList<>();
        for (int i = 0; i < GROUPS.size(); i++) maps.add(new LinkedHashMap<>());
        values.forEach((name, value) -> {
            if (value != null) {
                PropertyEditors.Description description = editorLookup.apply(name);
                maps.get(groupIndex(name)).put(name, new PropertyValue(
                        value, source, description.editor(), description.readOnlyReason()));
            }
        });
        List<PropertyGroup> result = new ArrayList<>();
        for (int i = 0; i < GROUPS.size(); i++) result.add(new PropertyGroup(GROUPS.get(i), maps.get(i)));
        return result;
    }

    private static int groupIndex(String name) {
        String key = name.toLowerCase();
        if (key.contains("backend") || key.contains("fidelity") || key.contains("warning")) return 7;
        if (key.contains("window") || key.contains("parent") || key.contains("child") || key.contains("depth")
                || key.contains("drawing") || key.contains("layer") || key.contains("display")) return 1;
        if (key.equals("text") || key.contains("textsize") || key.contains("textcolor")
                || key.contains("hint") || key.contains("description") || key.contains("image")
                || key.contains("drawable") || key.contains("bitmap")) return 4;
        if (key.contains("bound") || key.equals("x") || key.equals("y") || key.contains("width")
                || key.contains("height") || key.contains("padding") || key.contains("margin")
                || key.contains("layout") || key.contains("gravity") || key.contains("size")) return 2;
        if (key.contains("alpha") || key.contains("visibility") || key.contains("shown") || key.contains("elevation")
                || key.equals("z") || key.contains("translation") || key.contains("rotation") || key.contains("scale")
                || key.contains("pivot") || key.contains("clip") || key.contains("background")
                || key.contains("foreground") || key.contains("rend") || key.contains("format") || key.contains("dim")) return 3;
        if (key.contains("accessibility") || key.contains("screenreader") || key.contains("statedescription")
                || key.contains("action") || key.contains("role") || key.contains("live")) return 6;
        if (key.contains("click") || key.contains("focus") || key.contains("select") || key.contains("press")
                || key.contains("check") || key.contains("scroll") || key.contains("enabled") || key.contains("activated")) return 5;
        return 0;
    }

    private static com.company.uiinspector.protocol.Rect bounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new com.company.uiinspector.protocol.Rect(
                location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static com.company.uiinspector.protocol.Rect visibleBounds(View view) {
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return bounds(view);
        return new com.company.uiinspector.protocol.Rect(visible.left, visible.top, visible.right, visible.bottom);
    }

    static String resourceId(View view) {
        if (view.getId() == View.NO_ID) return null;
        try {
            return view.getResources().getResourceName(view.getId());
        } catch (RuntimeException ignored) {
            return Integer.toString(view.getId());
        }
    }

    private static String text(View view) { return view instanceof TextView value ? chars(value.getText()) : null; }
    private static String description(View view) { return chars(view.getContentDescription()); }
    private static String chars(CharSequence value) { return value == null ? null : value.toString(); }
    private static String className(Object value) { return value == null ? null : value.getClass().getName(); }
    private static String rect(Rect value) { return value == null ? null : "[" + value.left + "," + value.top + "][" + value.right + "," + value.bottom + "]"; }
    private static String format(com.company.uiinspector.protocol.Rect value) { return "[" + value.left() + "," + value.top() + "][" + value.right() + "," + value.bottom() + "]"; }
    private static String floatValue(float value) { return Float.toString(value); }
    private static int displayId(View view) { return view.getDisplay() == null ? 0 : view.getDisplay().getDisplayId(); }
    private static String visibility(int value) { return value == View.VISIBLE ? "VISIBLE" : value == View.INVISIBLE ? "INVISIBLE" : "GONE"; }
    private static String windowType(int type) { return "TYPE_" + type; }
    private static void put(Map<String, String> values, String key, String value) { if (value != null) values.put(key, value); }

    private static void write(InspectionSnapshot snapshot, String outputPath) {
        writeJson(SnapshotJson.encode(snapshot), outputPath);
    }

    static void writeJson(String json, String outputPath) {
        File target = new File(outputPath);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(target)) {
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The desktop side times out and reports enhanced-capture unavailability.
        }
    }

    private static final class Counter {
        int count;
        boolean truncated;
    }
}
