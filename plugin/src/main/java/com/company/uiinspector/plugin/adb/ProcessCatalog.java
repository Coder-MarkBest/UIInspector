package com.company.uiinspector.plugin.adb;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public final class ProcessCatalog {
    private final AdbClient adb;

    public ProcessCatalog(AdbClient adb) { this.adb = adb; }

    public List<RunningProcess> list(String serial) throws IOException, InterruptedException {
        var result = adb.run(List.of("-s", serial, "shell", "ps", "-A"), Duration.ofSeconds(15))
                .requireSuccess("Listing processes");
        return AdbParsers.processes(result.stdout());
    }

    public boolean isDebuggable(String serial, String packageName) throws IOException, InterruptedException {
        var result = adb.run(List.of(
                        "-s", serial, "shell", "dumpsys", "package", packageName),
                Duration.ofSeconds(15)).requireSuccess("Reading package flags");
        return AdbParsers.debuggable(result.stdout());
    }
}
