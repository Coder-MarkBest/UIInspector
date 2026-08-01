package com.company.uiinspector.plugin.adb;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public final class DeviceCatalog {
    private final AdbClient adb;

    public DeviceCatalog(AdbClient adb) { this.adb = adb; }

    public List<AdbDevice> list() throws IOException, InterruptedException {
        return AdbParsers.devices(adb.run(List.of("devices", "-l"), Duration.ofSeconds(10))
                .requireSuccess("Listing devices").stdout());
    }
}
