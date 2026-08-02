package com.company.uiinspector.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.company.uiinspector.protocol.CaptureSource;
import com.company.uiinspector.protocol.EditorValueType;
import com.company.uiinspector.protocol.PropertyValue;
import com.company.uiinspector.protocol.UiNode;
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
        title.setBackground(new ColorDrawable(0xFF123456));
        title.setClickable(true);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setScreenReaderFocusable(true);
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
        assertEquals("false", textNode.propertyGroups().get(5).properties().get("contextClickable").value());
        assertFalse(textNode.propertyGroups().get(4).properties().containsKey("contextClickable"));
        assertEquals("true", textNode.propertyGroups().get(6).properties().get("screenReaderFocusable").value());
        assertEquals("true", property(textNode, "importantForAccessibilityResolved").value());
        var enabled = property(textNode, "enabled");
        assertEquals(EditorValueType.BOOLEAN, enabled.editor().valueType());
        assertEquals("setter:setEnabled", enabled.editor().adapter());
        var text = property(textNode, "text");
        assertEquals(EditorValueType.STRING, text.editor().valueType());
        assertEquals("setter:setText:java.lang.CharSequence", text.editor().adapter());
        assertEquals(EditorValueType.COLOR, property(textNode, "textColor").editor().valueType());
        assertEquals("adapter:textColor", property(textNode, "textColor").editor().adapter());
        assertEquals("adapter:marginLeft", property(textNode, "marginLeft").editor().adapter());
        assertNull(property(textNode, "background").editor());
        assertEquals("Unsupported setter parameter: android.graphics.drawable.Drawable",
                property(textNode, "background").readOnlyReason());
        assertNull(property(textNode, "focused").editor());
        var imageNode = snapshot.nodes().values().stream()
                .filter(node -> node.className().equals(ImageView.class.getName()))
                .findFirst().orElseThrow();
        assertEquals("1024", imageNode.propertyGroups().get(4).properties().get("drawableIntrinsicWidth").value());
        assertNotNull(imageNode.propertyGroups().get(4).properties().get("bitmapByteCount"));
        assertEquals(EditorValueType.ENUM, property(imageNode, "imageScaleType").editor().valueType());
        assertTrue(property(imageNode, "imageScaleType").editor().choices().contains("CENTER_CROP"));
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    private static PropertyValue property(UiNode node, String propertyName) {
        return node.propertyGroups().stream()
                .map(group -> group.properties().get(propertyName))
                .filter(value -> value != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing property: " + propertyName));
    }
}
