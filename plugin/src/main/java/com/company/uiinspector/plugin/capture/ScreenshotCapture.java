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
        int[] imageSize = pngSize(png);
        int[] screenSize = screenSize(serial);
        if (result.exitCode() != 0 || png.length == 0) {
            String reason = result.stderr() == null || result.stderr().isBlank()
                    ? "Screenshot unavailable or protected by the target Window." : result.stderr().strip();
            if (screenSize == null) return new Screenshot("image/png", null, reason);
            return new Screenshot("image/png", null, reason,
                    screenSize[0], screenSize[1], 0, 0);
        }
        int imageWidth = imageSize == null ? 0 : imageSize[0];
        int imageHeight = imageSize == null ? 0 : imageSize[1];
        if (screenSize == null) {
            // 拿不到逻辑屏尺寸时退化为截图尺寸，1:1。
            screenSize = new int[] { imageWidth, imageHeight };
        }
        return new Screenshot(
                "image/png", "data:image/png;base64," + Base64.getEncoder().encodeToString(png), null,
                screenSize[0], screenSize[1], imageWidth, imageHeight);
    }

    /**
     * Reads the image dimensions from the PNG IHDR chunk (big-endian 32-bit).
     * Returns null when the bytes do not look like a PNG or are truncated.
     */
    static int[] pngSize(byte[] raw) {
        if (raw == null || raw.length < 24 || !matchesMagic(raw)) return null;
        int width = ((raw[16] & 0xff) << 24) | ((raw[17] & 0xff) << 16)
                | ((raw[18] & 0xff) << 8) | (raw[19] & 0xff);
        int height = ((raw[20] & 0xff) << 24) | ((raw[21] & 0xff) << 16)
                | ((raw[22] & 0xff) << 8) | (raw[23] & 0xff);
        if (width <= 0 || height <= 0 || width > 20000 || height > 20000) return null;
        return new int[] { width, height };
    }

    private static boolean matchesMagic(byte[] raw) {
        if (raw.length < PNG_MAGIC.length) return false;
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (raw[i] != PNG_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * Queries the device logical display size via `wm size`. Returns {width,
     * height} or null. Accessibility bounds are reported in this logical
     * coordinate space, which can differ from the physical screencap pixels
     * when display scaling/override is active.
     */
    private int[] screenSize(String serial) throws IOException, InterruptedException {
        var result = adb.run(List.of("-s", serial, "shell", "wm", "size"),
                Duration.ofSeconds(10));
        if (result.exitCode() != 0) return null;
        return parseWmSize(result.stdout());
    }

    static int[] parseWmSize(String stdout) {
        if (stdout == null) return null;
        // 优先使用 Override size（实际生效的逻辑尺寸），否则用 Physical size。
        int override = stdout.indexOf("Override size:");
        int physical = stdout.indexOf("Physical size:");
        int start = override >= 0 ? override : physical;
        if (start < 0) return null;
        int[] parsed = parseSize(stdout.substring(start + 13, Math.min(stdout.length(), start + 60)));
        if (parsed != null) return parsed;
        if (override >= 0 && physical >= 0) return parseSize(stdout.substring(physical + 14,
                Math.min(stdout.length(), physical + 60)));
        return null;
    }

    private static int[] parseSize(String chunk) {
        var matcher = java.util.regex.Pattern.compile("(\\d+)\\s*x\\s*(\\d+)")
                .matcher(chunk);
        if (!matcher.find()) return null;
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width <= 0 || height <= 0 || width > 20000 || height > 20000) return null;
        return new int[] { width, height };
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
