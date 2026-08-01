package com.company.uiinspector.plugin.capture;

import java.nio.file.Path;
import java.util.Map;

public record AgentPayloads(Path helperApk, Map<String, Path> agentsByAbi) {
    public Path agentFor(String abi) {
        return agentsByAbi.get(abi);
    }
}
