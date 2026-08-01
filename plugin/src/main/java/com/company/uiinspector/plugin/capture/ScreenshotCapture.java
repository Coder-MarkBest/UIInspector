package com.company.uiinspector.plugin.capture;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.protocol.Screenshot;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public final class ScreenshotCapture {
    private final AdbClient adb;

    public ScreenshotCapture(AdbClient adb) { this.adb = adb; }

    public Screenshot capture(String serial) throws IOException, InterruptedException {
        var result = adb.run(
                List.of("-s", serial, "exec-out", "screencap", "-p"), Duration.ofSeconds(20));
        if (result.exitCode() != 0 || result.stdoutBytes().length == 0) {
            String reason = result.stderr() == null || result.stderr().isBlank()
                    ? "Screenshot unavailable or protected by the target Window." : result.stderr().strip();
            return new Screenshot("image/png", null, reason);
        }
        return new Screenshot(
                "image/png", "data:image/png;base64," + Base64.getEncoder().encodeToString(result.stdoutBytes()), null);
    }
}
