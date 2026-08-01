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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
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
            if (!latch.await(15, TimeUnit.SECONDS)) {
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
