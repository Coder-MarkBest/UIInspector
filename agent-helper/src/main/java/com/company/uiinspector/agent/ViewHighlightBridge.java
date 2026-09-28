package com.company.uiinspector.agent;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.List;

public final class ViewHighlightBridge {
    private static final int BORDER_COLOR = 0xFF00BFFF;
    private static final int BORDER_WIDTH_DP = 3;

    private ViewHighlightBridge() {}

    public static void highlight(
            List<View> roots,
            List<WindowManager.LayoutParams> windowParams,
            String nodeId,
            String outputPath) {
        Runnable task = () -> {
            try {
                if (nodeId != null && !nodeId.isEmpty()) {
                    clearAllHighlights(roots);
                    View target = ViewTargetResolver.resolveNode(roots, nodeId);
                    if (target.getWidth() > 0 && target.getHeight() > 0) {
                        float density = target.getResources().getDisplayMetrics().density;
                        int borderPx = Math.max(2, Math.round(BORDER_WIDTH_DP * density));
                        HighlightDrawable drawable = new HighlightDrawable(BORDER_COLOR, borderPx);
                        drawable.setBounds(0, 0, target.getWidth(), target.getHeight());
                        target.getOverlay().add(drawable);
                        target.invalidate();
                    }
                } else {
                    clearAllHighlights(roots);
                }
                ViewCaptureBridge.writeJson("{\"ok\":true}", outputPath);
            } catch (RuntimeException error) {
                ViewCaptureBridge.writeJson(
                        "{\"ok\":false,\"error\":\"" + safeMessage(error) + "\"}", outputPath);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            new Handler(Looper.getMainLooper()).post(task);
        }
    }

    private static void clearAllHighlights(List<View> roots) {
        for (View root : roots) {
            clearRecursive(root);
        }
    }

    private static void clearRecursive(View view) {
        view.getOverlay().clear();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                clearRecursive(group.getChildAt(i));
            }
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static final class HighlightDrawable extends Drawable {
        private final Paint paint;
        private final int borderWidth;

        HighlightDrawable(int color, int borderWidth) {
            this.borderWidth = borderWidth;
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            paint.setStrokeWidth(borderWidth);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float half = borderWidth / 2f;
            canvas.drawRect(
                    half, half,
                    bounds.width() - half, bounds.height() - half,
                    paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
