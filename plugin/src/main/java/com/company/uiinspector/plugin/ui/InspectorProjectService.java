package com.company.uiinspector.plugin.ui;

import com.company.uiinspector.plugin.adb.AdbLocator;
import com.company.uiinspector.plugin.adb.CollectorManager;
import com.company.uiinspector.plugin.adb.DeviceCatalog;
import com.company.uiinspector.plugin.adb.ProcessAdbClient;
import com.company.uiinspector.plugin.capture.AgentDeployer;
import com.company.uiinspector.plugin.capture.PayloadExtractor;
import com.company.uiinspector.plugin.server.InspectorApi;
import com.company.uiinspector.plugin.server.InspectorServer;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class InspectorProjectService implements Disposable {
    private final Project project;
    private InspectorServer server;
    private PayloadExtractor payloads;
    private ProcessAdbClient adb;
    private CollectorManager collector;

    public InspectorProjectService(Project project) {
        this.project = project;
    }

    public synchronized URI ensureStarted() throws IOException {
        if (server != null) return server.browserUri();
        Path adbPath = locateAdb();
        adb = new ProcessAdbClient(adbPath);
        payloads = PayloadExtractor.fromResources(getClass().getClassLoader());
        collector = new CollectorManager(adb, payloads.collectorApk());
        var deployer = new AgentDeployer(adb, payloads.agentPayloads(), () -> UUID.randomUUID().toString());
        List<String> projectPackages = project.getBasePath() == null ? List.of()
                : ProjectApplicationIdDetector.detectAll(Path.of(project.getBasePath()));
        var api = new InspectorApi(adb, collector, deployer, projectPackages);
        server = new InspectorServer(token(), api, this::webResource);
        server.start();
        return server.browserUri();
    }

    public void openBrowser() throws IOException {
        URI uri = ensureStarted();
        ApplicationManager.getApplication().invokeLater(() -> BrowserUtil.browse(uri));
    }

    public void openCollectorSettings() throws Exception {
        ensureStarted();
        var device = new DeviceCatalog(adb).list().stream().filter(value -> value.ready()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No authorized Android device is connected."));
        collector.install(device.serial());
        collector.openAccessibilitySettings(device.serial());
    }

    private Path locateAdb() throws IOException {
        Path projectSdk = projectSdk();
        List<Path> pathEntries = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) pathEntries.add(Path.of(entry));
            }
        }
        return AdbLocator.locate(projectSdk, System.getenv(), pathEntries)
                .orElseThrow(() -> new IOException(
                        "adb was not found. Configure an Android SDK in the project or ANDROID_HOME."));
    }

    private Path projectSdk() {
        if (project.getBasePath() == null) return null;
        Path propertiesFile = Path.of(project.getBasePath(), "local.properties");
        if (!Files.isRegularFile(propertiesFile)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
            String sdk = properties.getProperty("sdk.dir");
            return sdk == null || sdk.isBlank() ? null : Path.of(sdk);
        } catch (IOException ignored) {
            return null;
        }
    }

    private byte[] webResource(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("web/" + path)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private static String token() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public synchronized void dispose() {
        if (server != null) server.close();
        if (payloads != null) payloads.close();
        server = null;
        payloads = null;
        adb = null;
        collector = null;
    }
}
