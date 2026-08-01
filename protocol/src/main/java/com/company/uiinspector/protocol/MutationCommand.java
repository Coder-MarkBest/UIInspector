package com.company.uiinspector.protocol;

public record MutationCommand(
        int schemaVersion,
        String targetId,
        String expectedClassName,
        String expectedResourceId,
        String propertyName,
        String adapter,
        EditorTarget target,
        EditorValueType valueType,
        String value) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
