package com.company.uiinspector.collector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.SnapshotJson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class UiInspectorAccessibilityService extends AccessibilityService {
    private static volatile UiInspectorAccessibilityService instance;

    static UiInspectorAccessibilityService current() {
        return instance;
    }

    private android.view.WindowManager overlayWm;
    private android.view.View highlightOverlay;
    private android.view.WindowManager.LayoutParams overlayParams;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
        overlayWm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
    }

    @Override
    public void onDestroy() {
        clearHighlight();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    void showHighlight(int left, int top, int right, int bottom) {
        android.os.Handler main = new android.os.Handler(getMainLooper());
        main.post(() -> applyHighlight(left, top, right, bottom));
    }

    private void applyHighlight(int left, int top, int right, int bottom) {
        try {
            clearHighlightNow();
            if (overlayWm == null) return;
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            android.view.View view = new android.view.View(this);
            int borderPx = Math.max(2, Math.round(3 * getResources().getDisplayMetrics().density));
            android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
            border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            border.setStroke(borderPx, 0xFF00BFFF);
            border.setColor(0x00000000);
            view.setBackground(border);

            int type;
            if (Build.VERSION.SDK_INT >= 30) {
                type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            } else {
                // 旧设备回退到 TYPE_APPLICATION_OVERLAY
                type = 2038; // TYPE_APPLICATION_OVERLAY
            }
            android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
                    width, height,
                    left, top,
                    type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            overlayWm.addView(view, params);
            highlightOverlay = view;
            overlayParams = params;
        } catch (RuntimeException ignored) {
            // TYPE_ACCESSIBILITY_OVERLAY 在某些定制 ROM 上不可用；
            // 高亮失败不影响核心采集功能。
        }
    }

    void clearHighlight() {
        android.os.Handler main = new android.os.Handler(getMainLooper());
        main.post(this::clearHighlightNow);
    }

    private void clearHighlightNow() {
        if (highlightOverlay != null && overlayWm != null) {
            try { overlayWm.removeView(highlightOverlay); } catch (RuntimeException ignored) {}
            highlightOverlay = null;
            overlayParams = null;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    String captureJson(CaptureScope scope, String packageName, int maxNodes, int maxDepth) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return captureOnServiceThread(scope, packageName, maxNodes, maxDepth);
        }
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result.set(captureOnServiceThread(scope, packageName, maxNodes, maxDepth));
            } catch (RuntimeException error) {
                failure.set(error);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for accessibility capture.");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Accessibility capture interrupted.", error);
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private String captureOnServiceThread(CaptureScope scope, String packageName, int maxNodes, int maxDepth) {
        List<AndroidAccessibilityAdapters.WindowAdapter> adapters = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) {
            SparseArray<List<AccessibilityWindowInfo>> displays = getWindowsOnAllDisplays();
            for (int index = 0; index < displays.size(); index++) {
                int displayId = displays.keyAt(index);
                for (AccessibilityWindowInfo window : displays.valueAt(index)) {
                    adapters.add(new AndroidAccessibilityAdapters.WindowAdapter(window, displayId));
                }
            }
        } else {
            for (AccessibilityWindowInfo window : getWindows()) {
                adapters.add(new AndroidAccessibilityAdapters.WindowAdapter(window, 0));
            }
        }
        var request = new AccessibilitySnapshotCollector.Request(
                scope, packageName, Build.SERIAL, maxNodes, maxDepth);
        return SnapshotJson.encode(new AccessibilitySnapshotCollector().collect(adapters, request));
    }
}
