package com.company.uiinspector.plugin.adb;

public record AdbDevice(String serial, String state, String model, String product, String device) {
    public boolean ready() {
        return "device".equals(state);
    }
}
