package com.company.uiinspector.plugin.capture;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.protocol.Screenshot;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public final class ScreenshotCapture {
    // PNG magic bytes: \x89 P N G \r \n \x1a \n
    private static final byte[] PNG_MAGIC = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    private final AdbClient adb;

    public ScreenshotCapture(AdbClient adb) { this.adb = adb; }

    public Screenshot capture(String serial) throws IOException, InterruptedException {
        var result = adb.run(
                List.of("-s", serial, "exec-out", "screencap", "-p"), Duration.ofSeconds(20));
        byte[] png = extractPng(result.stdoutBytes());
        if (result.exitCode() != 0 || png.length == 0) {
            String reason = result.stderr() == null || result.stderr().isBlank()
                    ? "Screenshot unavailable or protected by the target Window." : result.stderr().strip();
            return new Screenshot("image/png", null, reason);
        }
        return new Screenshot(
                "image/png", "data:image/png;base64," + Base64.getEncoder().encodeToString(png), null);
    }

    /**
     * Strips any text (e.g. ADB diagnostics or screencap warnings about
     * multiple displays) that may appear before the PNG magic bytes.
     */
    static byte[] extractPng(byte[] raw) {
        if (raw == null || raw.length < PNG_MAGIC.length) return new byte[0];
        for (int i = 0; i <= raw.length - PNG_MAGIC.length; i++) {
            boolean match = true;
            for (int j = 0; j < PNG_MAGIC.length; j++) {
                if (raw[i + j] != PNG_MAGIC[j]) { match = false; break; }
            }
            if (match) {
                if (i == 0) return raw;
                byte[] trimmed = new byte[raw.length - i];
                System.arraycopy(raw, i, trimmed, 0, trimmed.length);
                return trimmed;
            }
        }
        return raw;  // No PNG magic found; return as-is (will fail size/length check).
    }
}
