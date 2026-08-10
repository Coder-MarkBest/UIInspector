package com.company.uiinspector.collector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.SnapshotJson;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class SnapshotProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceShellCaller();
        String path = uri.getPath();
        if (path != null && path.contains("highlight")) {
            if (!"r".equals(mode)) throw new FileNotFoundException("Highlight is read-only.");
            handleHighlight(uri);
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException error) {
                throw new FileNotFoundException(error.getMessage());
            }
            new Thread(() -> writeOk(pipe[1]), "uiinspector-highlight").start();
            return pipe[0];
        }
        if (!"r".equals(mode)) throw new FileNotFoundException("Snapshot provider is read-only.");
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        new Thread(() -> writeSnapshot(pipe[1], uri), "uiinspector-snapshot").start();
        return pipe[0];
    }

    private void handleHighlight(Uri uri) {
        String bounds = uri.getQueryParameter("bounds");
        if (bounds == null || bounds.isEmpty()) {
            UiInspectorAccessibilityService service = UiInspectorAccessibilityService.current();
            if (service != null) service.clearHighlight();
            return;
        }
        String[] parts = bounds.split(",");
        if (parts.length != 4) return;
        try {
            int left = Integer.parseInt(parts[0]);
            int top = Integer.parseInt(parts[1]);
            int right = Integer.parseInt(parts[2]);
            int bottom = Integer.parseInt(parts[3]);
            UiInspectorAccessibilityService service = UiInspectorAccessibilityService.current();
            if (service != null) service.showHighlight(left, top, right, bottom);
        } catch (NumberFormatException ignored) {
            // Invalid bounds; skip.
        }
    }

    private static void writeOk(ParcelFileDescriptor output) {
        try (ParcelFileDescriptor descriptor = output;
             FileOutputStream stream = new FileOutputStream(descriptor.getFileDescriptor())) {
            stream.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private void writeSnapshot(ParcelFileDescriptor output, Uri uri) {
        try (ParcelFileDescriptor descriptor = output;
             FileOutputStream stream = new FileOutputStream(descriptor.getFileDescriptor())) {
            stream.write(snapshot(uri).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The adb reader may disconnect; no device-side state needs recovery.
        }
    }

    private String snapshot(Uri uri) {
        UiInspectorAccessibilityService service = UiInspectorAccessibilityService.current();
        if (service == null) {
            var empty = new InspectionSnapshot(
                    1, System.currentTimeMillis(), android.os.Build.SERIAL, CaptureScope.GLOBAL,
                    null, null, List.of(), List.of(), Map.of(),
                    List.of(new Diagnostic(
                            "ERROR", "accessibility_required",
                            "Enable UI Inspector Collector in Android Accessibility settings.")),
                    null);
            return SnapshotJson.encode(empty);
        }
        CaptureScope scope = "target".equals(uri.getQueryParameter("scope"))
                ? CaptureScope.TARGET_PROCESS : CaptureScope.GLOBAL;
        String packageName = uri.getQueryParameter("package");
        int maxNodes = number(uri.getQueryParameter("maxNodes"), 10_000, 1, 50_000);
        int maxDepth = number(uri.getQueryParameter("maxDepth"), 200, 0, 500);
        return service.captureJson(scope, packageName, maxNodes, maxDepth);
    }

    private void enforceShellCaller() {
        int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.myUid()) {
            throw new SecurityException("Only adb shell may read UI Inspector snapshots.");
        }
    }

    private static int number(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
}
