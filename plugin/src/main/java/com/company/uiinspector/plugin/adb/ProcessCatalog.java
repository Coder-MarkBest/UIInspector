package com.company.uiinspector.plugin.adb;

import com.company.uiinspector.protocol.ProcessInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ProcessCatalog {
    private final AdbClient adb;

    public ProcessCatalog(AdbClient adb) { this.adb = adb; }

    public List<RunningProcess> list(String serial) throws IOException, InterruptedException {
        var result = adb.run(List.of("-s", serial, "shell", "ps", "-A"), Duration.ofSeconds(15))
                .requireSuccess("Listing processes");
        return AdbParsers.processes(result.stdout());
    }

    public List<ProcessInfo> listForUi(String serial, List<String> projectPackages)
            throws IOException, InterruptedException {
        Set<Integer> debuggablePids = AdbParsers.debuggablePids(adb.run(
                        List.of("-s", serial, "jdwp"), Duration.ofSeconds(15))
                .requireSuccess("Listing debuggable processes").stdout());
        Set<String> projects = projectPackages == null
                ? Set.of() : new HashSet<>(projectPackages);
        return list(serial).stream()
                .filter(AdbParsers::isApplicationProcess)
                .map(value -> new ProcessInfo(
                        value.pid(), value.user(), value.packageName(), value.processName(),
                        debuggablePids.contains(value.pid())))
                .sorted(Comparator
                        .comparingInt((ProcessInfo value) -> priority(value, projects))
                        .thenComparing(ProcessInfo::processName)
                        .thenComparingInt(ProcessInfo::pid))
                .toList();
    }

    public boolean isDebuggable(String serial, String packageName) throws IOException, InterruptedException {
        var result = adb.run(List.of(
                        "-s", serial, "shell", "dumpsys", "package", packageName),
                Duration.ofSeconds(15)).requireSuccess("Reading package flags");
        return AdbParsers.debuggable(result.stdout());
    }

    private static int priority(ProcessInfo process, Set<String> projectPackages) {
        if (projectPackages.contains(process.packageName())) {
            return process.packageName().equals(process.processName()) ? 0 : 1;
        }
        return process.debuggable() ? 2 : 3;
    }
}
