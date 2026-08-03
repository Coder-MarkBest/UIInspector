package com.company.uiinspector.plugin.capture;

import java.io.IOException;

final class AdbJsonOutput {
    private static final String PAYLOAD_START = "{\"schemaVersion\":";

    private AdbJsonOutput() {}

    static String payload(String output, String operation) throws IOException {
        int start = output == null ? -1 : output.indexOf(PAYLOAD_START);
        if (start < 0) {
            throw new IOException(operation + " did not return a UI Inspector JSON payload.");
        }
        return output.substring(start).stripTrailing();
    }
}
