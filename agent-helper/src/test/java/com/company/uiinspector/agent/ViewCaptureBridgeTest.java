package com.company.uiinspector.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.company.uiinspector.protocol.CaptureSource;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ViewCaptureBridgeTest {
    @Test
    public void capturesSeparateWindowRootsAndGroupedRealViewProperties() {
        Application context = RuntimeEnvironment.getApplication();
        FrameLayout activityRoot = new FrameLayout(context);
        activityRoot.setId(android.R.id.content);
        activityRoot.setPadding(8, 9, 10, 11);
        TextView title = new TextView(context);
        title.setText("Vehicle status");
        title.setContentDescription("Current vehicle status");
        title.setAlpha(0.75f);
        title.setClickable(true);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(200, 60);
        titleParams.leftMargin = 12;
        titleParams.topMargin = 18;
        activityRoot.addView(title, titleParams);
        activityRoot.layout(0, 0, 600, 400);
        title.layout(12, 18, 212, 78);

        FrameLayout overlayRoot = new FrameLayout(context);
        ImageView image = new ImageView(context);
        image.setImageDrawable(new BitmapDrawable(
                context.getResources(), Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        overlayRoot.addView(image, new FrameLayout.LayoutParams(80, 80));
        overlayRoot.layout(0, 0, 160, 160);
        image.layout(0, 0, 80, 80);

        WindowManager.LayoutParams activityWindow = new WindowManager.LayoutParams(600, 400);
        activityWindow.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        activityWindow.setTitle("MainActivity");
        WindowManager.LayoutParams overlayWindow = new WindowManager.LayoutParams(160, 160);
        overlayWindow.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        overlayWindow.setTitle("DirectAddView");

        var snapshot = ViewCaptureBridge.captureNow(
                List.of(activityRoot, overlayRoot),
                List.of(activityWindow, overlayWindow),
                "device", "com.example", "com.example");

        assertEquals(2, snapshot.windows().size());
        assertEquals("DirectAddView", snapshot.windows().get(1).title());
        assertEquals(CaptureSource.JVMTI, snapshot.windows().get(0).source());
        var textNode = snapshot.nodes().values().stream()
                .filter(node -> "Vehicle status".equals(node.text()))
                .findFirst().orElseThrow();
        assertEquals(8, textNode.propertyGroups().size());
        assertEquals("12", textNode.propertyGroups().get(2).properties().get("marginLeft").value());
        assertEquals("0.75", textNode.propertyGroups().get(3).properties().get("alpha").value());
        assertEquals("true", textNode.propertyGroups().get(5).properties().get("clickable").value());
        var imageNode = snapshot.nodes().values().stream()
                .filter(node -> node.className().equals(ImageView.class.getName()))
                .findFirst().orElseThrow();
        assertEquals("1024", imageNode.propertyGroups().get(4).properties().get("drawableIntrinsicWidth").value());
        assertNotNull(imageNode.propertyGroups().get(4).properties().get("bitmapByteCount"));
        assertTrue(snapshot.diagnostics().isEmpty());
    }
}
