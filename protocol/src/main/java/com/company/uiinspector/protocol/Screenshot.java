package com.company.uiinspector.protocol;

public record Screenshot(
        String mimeType,
        String dataUrl,
        String unavailableReason,
        int screenWidth,
        int screenHeight,
        int imageWidth,
        int imageHeight) {
    public Screenshot(String mimeType, String dataUrl, String unavailableReason) {
        this(mimeType, dataUrl, unavailableReason, 0, 0, 0, 0);
    }
}
