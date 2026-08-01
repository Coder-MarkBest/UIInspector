package com.company.uiinspector.agent;

import static com.company.uiinspector.protocol.EditorTarget.LAYOUT_PARAMS;
import static com.company.uiinspector.protocol.EditorTarget.VIEW;
import static com.company.uiinspector.protocol.EditorTarget.WINDOW_PARAMS;

import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;
import com.company.uiinspector.protocol.EditorTarget;
import com.company.uiinspector.protocol.EditorValueType;
import com.company.uiinspector.protocol.PropertyEditor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class PropertyEditors {
    private PropertyEditors() {}

    record Description(PropertyEditor editor, String readOnlyReason) {}

    static Description forView(View view, String propertyName) {
        Description adapted = viewAdapter(view, propertyName);
        return adapted != null ? adapted : discoverSetter(view.getClass(), propertyName);
    }

    static Description forWindow(
            View root,
            WindowManager.LayoutParams params,
            String propertyName) {
        if (params == null) return none();
        return switch (propertyName) {
            case "x" -> window(EditorValueType.INTEGER, "adapter:windowX");
            case "y" -> window(EditorValueType.INTEGER, "adapter:windowY");
            case "layoutWidth" -> window(EditorValueType.INTEGER, "adapter:windowLayoutWidth",
                    List.of("MATCH_PARENT", "WRAP_CONTENT"));
            case "layoutHeight" -> window(EditorValueType.INTEGER, "adapter:windowLayoutHeight",
                    List.of("MATCH_PARENT", "WRAP_CONTENT"));
            case "gravity" -> window(EditorValueType.INTEGER, "adapter:windowGravity");
            case "windowAlpha" -> window(EditorValueType.FLOAT, "adapter:windowAlpha");
            case "dimAmount" -> window(EditorValueType.FLOAT, "adapter:windowDimAmount");
            case "softInputMode" -> window(EditorValueType.INTEGER, "adapter:windowSoftInputMode");
            default -> none();
        };
    }

    private static Description viewAdapter(View view, String propertyName) {
        return switch (propertyName) {
            case "paddingLeft", "paddingTop", "paddingRight", "paddingBottom" ->
                    view(EditorValueType.INTEGER, "adapter:" + propertyName);
            case "layoutParamWidth", "layoutParamHeight" -> view.getLayoutParams() == null
                    ? null
                    : editor(LAYOUT_PARAMS, EditorValueType.INTEGER, "adapter:" + propertyName,
                            List.of("MATCH_PARENT", "WRAP_CONTENT"));
            case "marginLeft", "marginTop", "marginRight", "marginBottom", "marginStart", "marginEnd" ->
                    view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                            ? editor(LAYOUT_PARAMS, EditorValueType.INTEGER, "adapter:" + propertyName)
                            : null;
            case "textSizePx" -> view instanceof TextView
                    ? view(EditorValueType.FLOAT, "adapter:textSizePx") : null;
            case "textColor" -> view instanceof TextView
                    ? view(EditorValueType.COLOR, "adapter:textColor") : null;
            case "imageScaleType" -> view instanceof ImageView
                    ? editor(VIEW, EditorValueType.ENUM, "adapter:imageScaleType",
                            enumNames(ImageView.ScaleType.class)) : null;
            case "checked" -> view instanceof Checkable
                    ? view(EditorValueType.BOOLEAN, "adapter:checked") : null;
            default -> null;
        };
    }

    private static Description discoverSetter(Class<?> viewClass, String propertyName) {
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        String setterName = "set" + suffix;
        List<Method> candidates = Arrays.stream(viewClass.getMethods())
                .filter(method -> method.getName().equals(setterName))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 1)
                .sorted(Comparator.comparing(method -> method.getParameterTypes()[0].getName()))
                .toList();
        if (candidates.isEmpty()) return none();

        Class<?> getterType = getterType(viewClass, suffix);
        Method selected = selectSetter(candidates, getterType, propertyName);
        Class<?> parameter = selected.getParameterTypes()[0];
        EditorValueType valueType = valueType(parameter);
        if (valueType == null) {
            return new Description(null, "Unsupported setter parameter: " + parameter.getName());
        }
        String adapter = "setter:" + selected.getName();
        if (candidates.size() > 1) adapter += ":" + parameter.getName();
        List<String> choices = parameter.isEnum() ? enumNames(parameter.asSubclass(Enum.class)) : List.of();
        return editor(VIEW, valueType, adapter, choices);
    }

    private static Method selectSetter(List<Method> candidates, Class<?> getterType, String propertyName) {
        if ("text".equals(propertyName)) {
            for (Method method : candidates) {
                if (method.getParameterTypes()[0] == CharSequence.class) return method;
            }
        }
        if (getterType != null) {
            for (Method method : candidates) {
                if (wrap(method.getParameterTypes()[0]).equals(wrap(getterType))) return method;
            }
        }
        for (Method method : candidates) {
            if (valueType(method.getParameterTypes()[0]) != null) return method;
        }
        return candidates.get(0);
    }

    private static Class<?> getterType(Class<?> viewClass, String suffix) {
        List<String> names = List.of("is" + suffix, "get" + suffix);
        for (String name : names) {
            try {
                Method getter = viewClass.getMethod(name);
                if (getter.getParameterCount() == 0) return getter.getReturnType();
            } catch (NoSuchMethodException ignored) {
                // Try the other conventional getter form.
            }
        }
        return null;
    }

    private static EditorValueType valueType(Class<?> type) {
        Class<?> boxed = wrap(type);
        if (boxed == Boolean.class) return EditorValueType.BOOLEAN;
        if (boxed == Byte.class) return EditorValueType.BYTE;
        if (boxed == Short.class) return EditorValueType.SHORT;
        if (boxed == Integer.class) return EditorValueType.INTEGER;
        if (boxed == Long.class) return EditorValueType.LONG;
        if (boxed == Float.class) return EditorValueType.FLOAT;
        if (boxed == Double.class) return EditorValueType.DOUBLE;
        if (boxed == Character.class) return EditorValueType.CHARACTER;
        if (type == String.class || type == CharSequence.class) return EditorValueType.STRING;
        if (type.isEnum()) return EditorValueType.ENUM;
        return null;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static List<String> enumNames(Class<? extends Enum> type) {
        List<String> result = new ArrayList<>();
        for (Enum value : type.getEnumConstants()) result.add(value.name());
        return List.copyOf(result);
    }

    private static Description view(EditorValueType valueType, String adapter) {
        return editor(VIEW, valueType, adapter);
    }

    private static Description window(EditorValueType valueType, String adapter) {
        return editor(WINDOW_PARAMS, valueType, adapter);
    }

    private static Description window(EditorValueType valueType, String adapter, List<String> choices) {
        return editor(WINDOW_PARAMS, valueType, adapter, choices);
    }

    private static Description editor(EditorTarget target, EditorValueType valueType, String adapter) {
        return editor(target, valueType, adapter, List.of());
    }

    private static Description editor(
            EditorTarget target,
            EditorValueType valueType,
            String adapter,
            List<String> choices) {
        return new Description(new PropertyEditor(target, valueType, adapter, choices), null);
    }

    private static Description none() {
        return new Description(null, null);
    }
}
