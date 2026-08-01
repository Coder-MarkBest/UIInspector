package com.company.uiinspector.agent;

import static com.company.uiinspector.protocol.EditorTarget.LAYOUT_PARAMS;
import static com.company.uiinspector.protocol.EditorTarget.VIEW;
import static com.company.uiinspector.protocol.EditorTarget.WINDOW_PARAMS;
import static com.company.uiinspector.protocol.EditorValueType.BOOLEAN;
import static com.company.uiinspector.protocol.EditorValueType.COLOR;
import static com.company.uiinspector.protocol.EditorValueType.ENUM;
import static com.company.uiinspector.protocol.EditorValueType.FLOAT;
import static com.company.uiinspector.protocol.EditorValueType.INTEGER;
import static com.company.uiinspector.protocol.EditorValueType.STRING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.company.uiinspector.protocol.EditorTarget;
import com.company.uiinspector.protocol.EditorValueType;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.MutationJson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ViewPropertyMutatorTest {
    private FrameLayout root;
    private TextView title;
    private ImageView image;
    private WindowManager.LayoutParams windowParams;
    private List<View> roots;
    private List<WindowManager.LayoutParams> windows;
    private ViewPropertyMutator mutator;
    private boolean windowUpdated;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        root = new FrameLayout(context);
        root.setId(android.R.id.content);
        LinearLayout container = new LinearLayout(context);
        root.addView(container, new FrameLayout.LayoutParams(500, 300));

        title = new TextView(context);
        title.setId(android.R.id.title);
        title.setText("Before");
        title.setBackground(new ColorDrawable(0xFF112233));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(120, 40);
        titleParams.topMargin = 4;
        container.addView(title, titleParams);

        image = new ImageView(context);
        image.setId(android.R.id.icon);
        container.addView(image, new LinearLayout.LayoutParams(40, 40));

        windowParams = new WindowManager.LayoutParams(500, 300);
        roots = List.of(root);
        windows = List.of(windowParams);
        mutator = new ViewPropertyMutator((view, params) -> windowUpdated = true);
    }

    @Test
    public void resolvesPathAndRejectsStaleIdentity() {
        assertSame(title, ViewTargetResolver.resolveNode(roots, "v:n:0:0.0.0"));

        var error = assertThrows(ViewPropertyMutator.MutationFailure.class, () -> mutator.apply(
                roots, windows, command("v:n:0:0.0.0", "android.widget.Button", resourceName(title),
                        "alpha", "setter:setAlpha", VIEW, FLOAT, "0.4")));

        assertEquals("stale_target", error.code());
    }

    @Test
    public void changesBooleanFloatStringEnumAndColor() {
        assertApplied(commandFor(title, "enabled", "setter:setEnabled", VIEW, BOOLEAN, "false"));
        assertFalse(title.isEnabled());
        assertApplied(commandFor(title, "alpha", "setter:setAlpha", VIEW, FLOAT, "0.4"));
        assertEquals(0.4f, title.getAlpha(), 0.001f);
        assertApplied(commandFor(title, "text", "setter:setText:java.lang.CharSequence", VIEW, STRING, "Changed"));
        assertEquals("Changed", title.getText().toString());
        assertApplied(commandFor(image, "imageScaleType", "adapter:imageScaleType", VIEW, ENUM, "FIT_CENTER"));
        assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
        assertApplied(commandFor(title, "textColor", "adapter:textColor", VIEW, COLOR, "#FF112233"));
        assertEquals(0xFF112233, title.getCurrentTextColor());
    }

    @Test
    public void appliesAggregateLayoutPropertiesAndRequestsLayout() {
        assertApplied(commandFor(title, "paddingLeft", "adapter:paddingLeft", VIEW, INTEGER, "24"));
        assertEquals(24, title.getPaddingLeft());
        assertApplied(commandFor(title, "marginTop", "adapter:marginTop", LAYOUT_PARAMS, INTEGER, "18"));
        assertEquals(18, ((ViewGroup.MarginLayoutParams) title.getLayoutParams()).topMargin);
        assertApplied(commandFor(title, "layoutParamWidth", "adapter:layoutParamWidth",
                LAYOUT_PARAMS, INTEGER, "WRAP_CONTENT"));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, title.getLayoutParams().width);
        assertTrue(title.isLayoutRequested());
    }

    @Test
    public void appliesSafeWindowPropertyThroughUpdater() {
        var result = mutator.apply(roots, windows, command(
                "v:w:0", root.getClass().getName(), resourceName(root), "x", "adapter:windowX",
                WINDOW_PARAMS, INTEGER, "32"));

        assertEquals(32, windowParams.x);
        assertTrue(windowUpdated);
        assertEquals("32", result.appliedValue());
    }

    @Test
    public void performsOnlyOneFullTreeCapturePerMutation() {
        int[] captures = {0};
        var countingMutator = new ViewPropertyMutator(
                (view, params) -> {},
                (capturedRoots, capturedWindows) -> {
                    captures[0]++;
                    return ViewCaptureBridge.captureNow(
                            capturedRoots, capturedWindows, "", "com.example", "com.example");
                });

        countingMutator.apply(roots, windows, commandFor(
                title, "alpha", "setter:setAlpha", VIEW, FLOAT, "0.6"));

        assertEquals(1, captures[0]);
    }

    @Test
    public void rejectsComplexUnknownOverflowAndNonFiniteValues() {
        assertFailure("property_read_only", commandFor(
                title, "background", "setter:setBackground", VIEW, STRING, "x"));
        assertFailure("invalid_value", commandFor(
                title, "alpha", "setter:setAlpha", VIEW, FLOAT, "NaN"));
        assertFailure("invalid_value", commandFor(
                title, "minimumWidth", "setter:setMinimumWidth", VIEW, INTEGER, "999999999999"));
        assertFailure("capability_mismatch", commandFor(
                title, "enabled", "setter:setClickable", VIEW, BOOLEAN, "false"));
    }

    @Test
    public void bridgePostsToMainThreadAndReturnsRefreshedSnapshot() throws Exception {
        File commandFile = File.createTempFile("uiinspector-command", ".json");
        File resultFile = File.createTempFile("uiinspector-result", ".json");
        assertTrue(resultFile.delete());
        Files.write(commandFile.toPath(), MutationJson.encodeCommand(commandFor(
                title, "alpha", "setter:setAlpha", VIEW, FLOAT, "0.25"))
                .getBytes(StandardCharsets.UTF_8));

        Thread worker = new Thread(() -> ViewMutationBridge.mutate(
                roots, windows, commandFile.getAbsolutePath(), resultFile.getAbsolutePath()));
        worker.start();
        worker.join();
        assertFalse(resultFile.exists());
        shadowOf(Looper.getMainLooper()).idle();

        var result = MutationJson.decodeResult(new String(
                Files.readAllBytes(resultFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(result.success());
        assertEquals("0.25", result.appliedValue());
        assertNotNull(result.snapshot());
        assertEquals("0.25", result.snapshot().nodes().get("v:n:0:0.0.0").propertyGroups().stream()
                .map(group -> group.properties().get("alpha"))
                .filter(value -> value != null)
                .findFirst().orElseThrow().value());
    }

    private void assertApplied(MutationCommand command) {
        assertEquals("ok", mutator.apply(roots, windows, command).code());
    }

    private void assertFailure(String code, MutationCommand command) {
        var error = assertThrows(ViewPropertyMutator.MutationFailure.class,
                () -> mutator.apply(roots, windows, command));
        assertEquals(code, error.code());
    }

    private MutationCommand commandFor(
            View view,
            String property,
            String adapter,
            EditorTarget target,
            EditorValueType type,
            String value) {
        String id = view == title ? "v:n:0:0.0.0" : "v:n:0:0.0.1";
        return command(id, view.getClass().getName(), resourceName(view),
                property, adapter, target, type, value);
    }

    private static MutationCommand command(
            String targetId,
            String expectedClass,
            String expectedResource,
            String property,
            String adapter,
            EditorTarget target,
            EditorValueType type,
            String value) {
        return new MutationCommand(1, targetId, expectedClass, expectedResource,
                property, adapter, target, type, value);
    }

    private static String resourceName(View view) {
        return view.getResources().getResourceName(view.getId());
    }
}
