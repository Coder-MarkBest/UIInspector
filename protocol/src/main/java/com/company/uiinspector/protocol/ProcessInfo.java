package com.company.uiinspector.protocol;

public record ProcessInfo(
        int pid,
        String user,
        String packageName,
        String processName,
        boolean debuggable) {}
