package com.company.uiinspector.agent;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;
import com.company.uiinspector.protocol.EditorTarget;
import com.company.uiinspector.protocol.EditorValueType;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.PropertyEditor;
import com.company.uiinspector.protocol.PropertyValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

final class ViewPropertyMutator {
    private final WindowUpdater windowUpdater;

    ViewPropertyMutator() {
        this((root, params) -> {
            WindowManager manager = (WindowManager) root.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) throw new IllegalStateException("WindowManager is unavailable");
            manager.updateViewLayout(root, params);
        });
    }

    ViewPropertyMutator(WindowUpdater windowUpdater) {
        this.windowUpdater = windowUpdater;
    }

    Outcome apply(
            List<View> roots,
            List<WindowManager.LayoutParams> windowParams,
            MutationCommand command) {
        if (command == null) throw failure("invalid_command", "Mutation command is missing");
        if (command.schemaVersion() != MutationCommand.CURRENT_SCHEMA_VERSION) {
            throw failure("invalid_command", "Unsupported mutation schema version");
        }

        Target target = resolve(roots, windowParams, command);
        validateIdentity(target.view(), command);
        PropertyEditors.Description description = target.windowIndex() == null
                ? PropertyEditors.forView(target.view(), command.propertyName())
                : PropertyEditors.forWindow(target.view(), target.windowParams(), command.propertyName());
        PropertyEditor editor = description.editor();
        if (editor == null) {
            String reason = description.readOnlyReason() == null
                    ? "Property is not editable" : description.readOnlyReason();
            throw failure("property_read_only", reason);
        }
        if (!Objects.equals(editor.adapter(), command.adapter())
                || editor.target() != command.target()
                || editor.valueType() != command.valueType()) {
            throw failure("capability_mismatch", "Edit capability no longer matches the current target");
        }

        InspectionSnapshot before = capture(roots, windowParams);
        String previousValue = property(before, command.targetId(), command.propertyName());
        if (previousValue == null) throw failure("property_not_found", "Property is no longer available");

        try {
            Object value = parse(command.value(), editor.valueType(), editor.choices());
            if (editor.target() == EditorTarget.WINDOW_PARAMS) {
                applyWindow(target.view(), target.windowParams(), editor.adapter(), value);
            } else {
                applyView(target.view(), editor, value);
                target.view().requestLayout();
                target.view().invalidate();
            }
        } catch (MutationFailure error) {
            throw error;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : error;
            throw failure("apply_failed", cause.getClass().getSimpleName() + ": " + safeMessage(cause));
        }

        InspectionSnapshot after = capture(roots, windowParams);
        String appliedValue = property(after, command.targetId(), command.propertyName());
        return new Outcome("ok", previousValue, appliedValue, after);
    }

    private Target resolve(
            List<View> roots,
            List<WindowManager.LayoutParams> params,
            MutationCommand command) {
        if (command.targetId() != null && command.targetId().startsWith("v:n:")) {
            return new Target(ViewTargetResolver.resolveNode(roots, command.targetId()), null, null);
        }
        int windowIndex = ViewTargetResolver.resolveWindowIndex(roots, command.targetId());
        if (windowIndex >= params.size() || params.get(windowIndex) == null) {
            throw failure("stale_target", "Window parameters are no longer available");
        }
        return new Target(roots.get(windowIndex), params.get(windowIndex), windowIndex);
    }

    private static void validateIdentity(View view, MutationCommand command) {
        if (!Objects.equals(view.getClass().getName(), command.expectedClassName())
                || !Objects.equals(ViewCaptureBridge.resourceId(view), command.expectedResourceId())) {
            throw failure("stale_target", "The selected View identity has changed");
        }
    }

    private void applyWindow(
            View root,
            WindowManager.LayoutParams params,
            String adapter,
            Object value) {
        switch (adapter) {
            case "adapter:windowX" -> params.x = (Integer) value;
            case "adapter:windowY" -> params.y = (Integer) value;
            case "adapter:windowLayoutWidth" -> params.width = (Integer) value;
            case "adapter:windowLayoutHeight" -> params.height = (Integer) value;
            case "adapter:windowGravity" -> params.gravity = (Integer) value;
            case "adapter:windowAlpha" -> params.alpha = (Float) value;
            case "adapter:windowDimAmount" -> params.dimAmount = (Float) value;
            case "adapter:windowSoftInputMode" -> params.softInputMode = (Integer) value;
            default -> throw failure("capability_mismatch", "Unknown Window adapter");
        }
        windowUpdater.update(root, params);
    }

    private static void applyView(View view, PropertyEditor editor, Object value)
            throws ReflectiveOperationException {
        String adapter = editor.adapter();
        if (adapter.startsWith("setter:")) {
            Method setter = findSetter(view.getClass(), adapter, editor.valueType());
            setter.invoke(view, convertForParameter(value, setter.getParameterTypes()[0]));
            return;
        }
        switch (adapter) {
            case "adapter:paddingLeft" -> view.setPadding(
                    (Integer) value, view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
            case "adapter:paddingTop" -> view.setPadding(
                    view.getPaddingLeft(), (Integer) value, view.getPaddingRight(), view.getPaddingBottom());
            case "adapter:paddingRight" -> view.setPadding(
                    view.getPaddingLeft(), view.getPaddingTop(), (Integer) value, view.getPaddingBottom());
            case "adapter:paddingBottom" -> view.setPadding(
                    view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), (Integer) value);
            case "adapter:layoutParamWidth" -> updateLayout(view, params -> params.width = (Integer) value);
            case "adapter:layoutParamHeight" -> updateLayout(view, params -> params.height = (Integer) value);
            case "adapter:marginLeft" -> updateMargins(view, params -> params.leftMargin = (Integer) value);
            case "adapter:marginTop" -> updateMargins(view, params -> params.topMargin = (Integer) value);
            case "adapter:marginRight" -> updateMargins(view, params -> params.rightMargin = (Integer) value);
            case "adapter:marginBottom" -> updateMargins(view, params -> params.bottomMargin = (Integer) value);
            case "adapter:marginStart" -> updateMargins(view, params -> params.setMarginStart((Integer) value));
            case "adapter:marginEnd" -> updateMargins(view, params -> params.setMarginEnd((Integer) value));
            case "adapter:textSizePx" -> ((TextView) view).setTextSize(TypedValue.COMPLEX_UNIT_PX, (Float) value);
            case "adapter:textColor" -> ((TextView) view).setTextColor((Integer) value);
            case "adapter:imageScaleType" -> ((ImageView) view).setScaleType(
                    ImageView.ScaleType.valueOf((String) value));
            case "adapter:checked" -> ((Checkable) view).setChecked((Boolean) value);
            default -> throw failure("capability_mismatch", "Unknown View adapter");
        }
    }

    private static Method findSetter(Class<?> type, String adapter, EditorValueType valueType) {
        String[] parts = adapter.split(":", 3);
        String parameterName = parts.length == 3 ? parts[2] : null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(parts[1]) || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameterName != null && !parameter.getName().equals(parameterName)) continue;
            if (parameterName == null && typeOf(parameter) != valueType) continue;
            return method;
        }
        throw failure("capability_mismatch", "Setter is no longer available");
    }

    private static Object parse(String raw, EditorValueType type, List<String> choices) {
        if (raw == null) throw failure("invalid_value", "Value is missing");
        try {
            return switch (type) {
                case BOOLEAN -> parseBoolean(raw);
                case BYTE -> Byte.valueOf(raw);
                case SHORT -> Short.valueOf(raw);
                case INTEGER -> parseInteger(raw);
                case LONG -> Long.valueOf(raw);
                case FLOAT -> finite(Float.valueOf(raw));
                case DOUBLE -> finite(Double.valueOf(raw));
                case CHARACTER -> raw.length() == 1 ? raw.charAt(0) : invalid("Expected one character");
                case STRING -> raw;
                case ENUM -> choices.contains(raw) ? raw : invalid("Unknown enum value");
                case COLOR -> parseColor(raw);
            };
        } catch (NumberFormatException error) {
            throw failure("invalid_value", "Value is outside the supported range or malformed");
        }
    }

    private static Boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        return invalid("Expected true or false");
    }

    private static Integer parseInteger(String raw) {
        return switch (raw) {
            case "VISIBLE" -> View.VISIBLE;
            case "INVISIBLE" -> View.INVISIBLE;
            case "GONE" -> View.GONE;
            case "MATCH_PARENT" -> ViewGroup.LayoutParams.MATCH_PARENT;
            case "WRAP_CONTENT" -> ViewGroup.LayoutParams.WRAP_CONTENT;
            default -> Integer.valueOf(raw);
        };
    }

    private static Float finite(Float value) {
        if (!Float.isFinite(value)) return invalid("Expected a finite number");
        return value;
    }

    private static Double finite(Double value) {
        if (!Double.isFinite(value)) return invalid("Expected a finite number");
        return value;
    }

    private static Integer parseColor(String raw) {
        if (!raw.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            throw failure("invalid_value", "Expected #RRGGBB or #AARRGGBB");
        }
        String normalized = raw.length() == 7 ? "#FF" + raw.substring(1) : raw;
        try {
            return Color.parseColor(normalized);
        } catch (IllegalArgumentException error) {
            throw failure("invalid_value", "Invalid color value");
        }
    }

    private static Object convertForParameter(Object value, Class<?> parameter) {
        if (parameter.isEnum()) return Enum.valueOf(parameter.asSubclass(Enum.class), (String) value);
        if (parameter == CharSequence.class && value instanceof String) return value;
        return value;
    }

    private static EditorValueType typeOf(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return EditorValueType.BOOLEAN;
        if (type == byte.class || type == Byte.class) return EditorValueType.BYTE;
        if (type == short.class || type == Short.class) return EditorValueType.SHORT;
        if (type == int.class || type == Integer.class) return EditorValueType.INTEGER;
        if (type == long.class || type == Long.class) return EditorValueType.LONG;
        if (type == float.class || type == Float.class) return EditorValueType.FLOAT;
        if (type == double.class || type == Double.class) return EditorValueType.DOUBLE;
        if (type == char.class || type == Character.class) return EditorValueType.CHARACTER;
        if (type == String.class || type == CharSequence.class) return EditorValueType.STRING;
        if (type.isEnum()) return EditorValueType.ENUM;
        return null;
    }

    private static void updateLayout(View view, LayoutUpdate update) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) throw failure("stale_target", "LayoutParams are no longer available");
        update.apply(params);
        view.setLayoutParams(params);
    }

    private static void updateMargins(View view, MarginUpdate update) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) {
            throw failure("stale_target", "MarginLayoutParams are no longer available");
        }
        update.apply(params);
        view.setLayoutParams(params);
    }

    private static InspectionSnapshot capture(
            List<View> roots,
            List<WindowManager.LayoutParams> params) {
        String packageName = roots.isEmpty() ? null : roots.get(0).getContext().getPackageName();
        return ViewCaptureBridge.captureNow(
                roots, params, "", packageName, android.app.Application.getProcessName());
    }

    private static String property(InspectionSnapshot snapshot, String targetId, String propertyName) {
        if (targetId.startsWith("v:n:")) {
            var node = snapshot.nodes().get(targetId);
            if (node == null) return null;
            for (var group : node.propertyGroups()) {
                PropertyValue value = group.properties().get(propertyName);
                if (value != null) return value.value();
            }
            return null;
        }
        for (var window : snapshot.windows()) {
            if (!window.id().equals(targetId)) continue;
            for (var group : window.propertyGroups()) {
                PropertyValue value = group.properties().get(propertyName);
                if (value != null) return value.value();
            }
        }
        return null;
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? "Mutation failed" : error.getMessage();
    }

    private static <T> T invalid(String message) {
        throw failure("invalid_value", message);
    }

    private static MutationFailure failure(String code, String message) {
        return new MutationFailure(code, message);
    }

    record Outcome(
            String code,
            String previousValue,
            String appliedValue,
            InspectionSnapshot snapshot) {}

    static final class MutationFailure extends RuntimeException {
        private final String code;

        MutationFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    @FunctionalInterface
    interface WindowUpdater {
        void update(View root, WindowManager.LayoutParams params);
    }

    private record Target(View view, WindowManager.LayoutParams windowParams, Integer windowIndex) {}
    @FunctionalInterface private interface LayoutUpdate { void apply(ViewGroup.LayoutParams params); }
    @FunctionalInterface private interface MarginUpdate { void apply(ViewGroup.MarginLayoutParams params); }
}
