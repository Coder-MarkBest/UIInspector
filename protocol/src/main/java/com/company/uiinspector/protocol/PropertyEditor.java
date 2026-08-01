package com.company.uiinspector.protocol;

import java.util.List;

public record PropertyEditor(
        EditorTarget target,
        EditorValueType valueType,
        String adapter,
        List<String> choices) {
    public PropertyEditor {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
