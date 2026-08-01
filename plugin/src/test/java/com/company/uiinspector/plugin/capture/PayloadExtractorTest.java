package com.company.uiinspector.plugin.capture;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PayloadExtractorTest {
    @Test
    void packagedPluginContainsCollectorHelperAndAllNativeAbis() throws Exception {
        try (PayloadExtractor payloads = PayloadExtractor.fromResources(getClass().getClassLoader())) {
            assertTrue(Files.size(payloads.collectorApk()) > 0);
            assertTrue(Files.size(payloads.agentPayloads().helperApk()) > 0);
            for (String abi : new String[] {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}) {
                var agent = payloads.agentPayloads().agentFor(abi);
                assertNotNull(agent, abi);
                assertTrue(Files.size(agent) > 0, abi);
            }
        }
    }
}
