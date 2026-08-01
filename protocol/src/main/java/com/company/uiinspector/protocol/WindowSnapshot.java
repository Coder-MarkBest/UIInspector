package com.company.uiinspector.protocol;

import java.util.List;

public record WindowSnapshot(
        String id,
        CaptureSource source,
        String packageName,
        String processName,
        int displayId,
        String type,
        int layer,
        String parentWindowId,
        List<String> childWindowIds,
        Rect bounds,
        boolean active,
        boolean focused,
        String title,
        String rootNodeId,
        List<PropertyGroup> propertyGroups) {}
