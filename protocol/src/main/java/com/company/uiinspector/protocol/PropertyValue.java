package com.company.uiinspector.protocol;

public record PropertyValue(
        String value,
        String source,
        PropertyEditor editor,
        String readOnlyReason) {
    public PropertyValue(String value, String source) {
        this(value, source, null, null);
    }
}
