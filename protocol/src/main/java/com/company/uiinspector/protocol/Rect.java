package com.company.uiinspector.protocol;

public record Rect(int left, int top, int right, int bottom) {
    public int width() {
        return Math.max(0, right - left);
    }

    public int height() {
        return Math.max(0, bottom - top);
    }
}
