package com.company.uiinspector.plugin.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebAssetContractTest {
    @Test
    void shipsApprovedHorizontalInspectorWithoutDemoData() throws IOException {
        String html = resource("web/index.html");
        String css = resource("web/styles.css");
        String app = resource("web/app.js");

        assertTrue(html.contains("id=\"tree\""));
        assertTrue(html.contains("id=\"stage\""));
        assertTrue(html.contains("id=\"properties\""));
        assertTrue(html.contains("Window layers"));
        assertTrue(html.contains("View depth"));
        assertTrue(html.contains("id=\"rules-toggle\" type=\"checkbox\""));
        assertTrue(css.contains("grid-template-columns: 274px minmax(420px, 1fr) 330px"));
        assertTrue(css.contains("prefers-reduced-motion"));
        assertTrue(app.contains("api('api/capture'"));
        assertTrue(app.contains("api('api/mutate'"));
        assertTrue(css.contains(".property-editor"));
        assertFalse(html.contains("<script>"));
        assertFalse(app.contains("eval("));
        assertFalse(app.toLowerCase().contains("mock"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = WebAssetContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing test resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
