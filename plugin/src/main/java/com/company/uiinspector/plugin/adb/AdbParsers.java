package com.company.uiinspector.plugin.adb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class AdbParsers {
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    private AdbParsers() {}

    public static List<AdbDevice> devices(String output) {
        List<AdbDevice> devices = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("List of devices")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            Map<String, String> attributes = new LinkedHashMap<>();
            for (int index = 2; index < parts.length; index++) {
                int separator = parts[index].indexOf(':');
                if (separator > 0) {
                    attributes.put(parts[index].substring(0, separator), parts[index].substring(separator + 1));
                }
            }
            devices.add(new AdbDevice(
                    parts[0], parts[1], attributes.get("model"),
                    attributes.get("product"), attributes.get("device")));
        }
        return devices;
    }

    public static List<RunningProcess> processes(String output) {
        List<RunningProcess> processes = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("USER ")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 3) continue;
            int pid;
            try {
                pid = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            String processName = parts[parts.length - 1];
            String base = processName.contains(":")
                    ? processName.substring(0, processName.indexOf(':')) : processName;
            String packageName = PACKAGE_NAME.matcher(base).matches() ? base : processName;
            processes.add(new RunningProcess(parts[0], pid, packageName, processName));
        }
        return processes;
    }

    public static boolean debuggable(String dumpsysPackage) {
        return Pattern.compile("(?:^|[\\s\\[])DEBUGGABLE(?:$|[\\s\\]])")
                .matcher(dumpsysPackage)
                .find();
    }
}
