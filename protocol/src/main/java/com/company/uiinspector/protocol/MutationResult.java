package com.company.uiinspector.protocol;

public record MutationResult(
        int schemaVersion,
        boolean success,
        String code,
        String message,
        String previousValue,
        String appliedValue,
        InspectionSnapshot snapshot) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
