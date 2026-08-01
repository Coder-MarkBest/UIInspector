package com.company.uiinspector.protocol;

import java.util.List;

public record UiNode(
        String id,
        CaptureSource source,
        String windowId,
        String parentId,
        List<String> childIds,
        int depth,
        String className,
        String resourceId,
        String text,
        String contentDescription,
        Rect bounds,
        Rect visibleBounds,
        Integer drawingOrder,
        List<PropertyGroup> propertyGroups) {}
