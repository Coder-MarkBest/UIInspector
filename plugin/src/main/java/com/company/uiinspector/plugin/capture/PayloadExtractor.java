package com.company.uiinspector.plugin.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PayloadExtractor implements AutoCloseable {
    private final Path directory;
    private final Path collectorApk;
    private final AgentPayloads agentPayloads;

    private PayloadExtractor(Path directory, Path collectorApk, AgentPayloads agentPayloads) {
        this.directory = directory;
        this.collectorApk = collectorApk;
        this.agentPayloads = agentPayloads;
    }

    public static PayloadExtractor fromResources(ClassLoader loader) throws IOException {
        Path directory = Files.createTempDirectory("uiinspector-payloads-");
        Path collector = copy(loader, "payload/collector.apk", directory.resolve("collector.apk"));
        Path helper = copy(loader, "payload/agent-helper.apk", directory.resolve("agent-helper.apk"));
        Path aar = copy(loader, "payload/agent-native.aar", directory.resolve("agent-native.aar"));
        Map<String, Path> agents = extractAgents(aar, directory.resolve("agents"));
        return new PayloadExtractor(directory, collector, new AgentPayloads(helper, agents));
    }

    public Path collectorApk() { return collectorApk; }
    public AgentPayloads agentPayloads() { return agentPayloads; }

    private static Path copy(ClassLoader loader, String resource, Path target) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Plugin payload is missing: " + resource);
            Files.copy(stream, target);
            return target;
        }
    }

    private static Map<String, Path> extractAgents(Path aar, Path destination) throws IOException {
        Files.createDirectories(destination);
        Map<String, Path> agents = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(aar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String[] parts = entry.getName().split("/");
                if (parts.length == 3 && "jni".equals(parts[0]) && "libuiinspector_agent.so".equals(parts[2])) {
                    Path target = destination.resolve(parts[1] + "-libuiinspector_agent.so");
                    Files.copy(zip, target);
                    agents.put(parts[1], target);
                }
            }
        }
        if (agents.isEmpty()) throw new IOException("Native Agent AAR contains no ABI payloads.");
        return agents;
    }

    @Override
    public void close() {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
