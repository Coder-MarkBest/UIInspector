package com.company.uiinspector.plugin.capture;

import java.io.IOException;

final class AdbJsonOutput {
    private static final String SCHEMA_KEY = "\"schemaVersion\":";

    private AdbJsonOutput() {}

    static String payload(String output, String operation) throws IOException {
        if (output == null) {
            throw new IOException(operation + " did not return a UI Inspector JSON payload.");
        }
        int bracePos = output.indexOf('{');
        if (bracePos < 0 || !output.contains(SCHEMA_KEY)) {
            throw new IOException(operation + " did not return a UI Inspector JSON payload.");
        }
        return output.substring(bracePos).stripTrailing();
    }
}
