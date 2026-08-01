package com.company.uiinspector.plugin.adb;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public interface AdbClient {
    CommandResult run(List<String> arguments, Duration timeout) throws IOException, InterruptedException;
}
