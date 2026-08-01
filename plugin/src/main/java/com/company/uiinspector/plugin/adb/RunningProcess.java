package com.company.uiinspector.plugin.adb;

public record RunningProcess(String user, int pid, String packageName, String processName) {}
