package com.company.uiinspector.plugin.adb;

import java.io.IOException;

public record CommandResult(int exitCode, String stdout, String stderr, byte[] stdoutBytes) {
    public CommandResult requireSuccess(String operation) throws IOException {
        if (exitCode != 0) {
            String detail = stderr == null || stderr.isBlank() ? stdout : stderr;
            throw new IOException(operation + " failed (exit " + exitCode + "): " + detail.strip());
        }
        return this;
    }
}
