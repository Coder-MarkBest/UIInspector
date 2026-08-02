package com.company.uiinspector.plugin.adb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.uiinspector.protocol.ProcessInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessCatalogTest {
    @Test
    void filtersObviousNonUiProcessesAndPrioritizesProjectAndDebuggableProcesses() throws Exception {
        AdbClient adb = (arguments, timeout) -> {
            String output = arguments.contains("ps") ? """
                    USER PID PPID VSZ RSS WCHAN ADDR S NAME
                    root 1 0 1 1 0 0 S init
                    system 100 1 1 1 0 0 S system_server
                    system 101 1 1 1 0 0 S surfaceflinger
                    u0_i9000 102 1 1 1 0 0 S webview_zygote
                    root 103 1 1 1 0 0 S android.hardware.graphics.composer@2.1-service
                    u0_a12 410 1 1 1 0 0 S com.example.current:worker
                    u0_a12 411 1 1 1 0 0 S com.example.current
                    u0_a13 512 1 1 1 0 0 S com.example.debug
                    u0_a14 513 1 1 1 0 0 S com.example.release
                    """ : "410\n411\n512\n";
            return new CommandResult(0, output, "", output.getBytes());
        };

        List<ProcessInfo> processes = new ProcessCatalog(adb).listForUi(
                "emulator-5554", List.of("com.example.current"));

        assertEquals(
                List.of(
                        "com.example.current",
                        "com.example.current:worker",
                        "com.example.debug",
                        "com.example.release"),
                processes.stream().map(ProcessInfo::processName).toList());
        assertTrue(processes.get(0).debuggable());
        assertTrue(processes.get(1).debuggable());
        assertTrue(processes.get(2).debuggable());
        assertFalse(processes.get(3).debuggable());
    }
}
