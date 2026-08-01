package com.company.uiinspector.agent;

import android.view.View;
import android.view.ViewGroup;
import java.util.List;

final class ViewTargetResolver {
    private ViewTargetResolver() {}

    static View resolveNode(List<View> roots, String id) {
        if (id == null || !id.startsWith("v:n:")) throw stale("Malformed View target ID");
        String[] parts = id.substring(4).split(":", -1);
        if (parts.length != 2) throw stale("Malformed View target ID");
        int windowIndex = index(parts[0], roots.size());
        String[] path = parts[1].split("\\.", -1);
        if (path.length == 0 || !"0".equals(path[0])) throw stale("Malformed View path");
        View current = roots.get(windowIndex);
        for (int i = 1; i < path.length; i++) {
            if (!(current instanceof ViewGroup group)) throw stale("View path no longer exists");
            int childIndex = index(path[i], group.getChildCount());
            current = group.getChildAt(childIndex);
            if (current == null) throw stale("View path no longer exists");
        }
        return current;
    }

    static int resolveWindowIndex(List<View> roots, String id) {
        if (id == null || !id.startsWith("v:w:")) throw stale("Malformed Window target ID");
        String value = id.substring(4);
        if (value.isEmpty() || value.indexOf(':') >= 0 || value.indexOf('.') >= 0) {
            throw stale("Malformed Window target ID");
        }
        return index(value, roots.size());
    }

    private static int index(String value, int limit) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw stale("Malformed target index");
        }
        try {
            int index = Integer.parseInt(value);
            if (index < 0 || index >= limit) throw stale("Target index is out of range");
            return index;
        } catch (NumberFormatException error) {
            throw stale("Malformed target index");
        }
    }

    private static ViewPropertyMutator.MutationFailure stale(String message) {
        return new ViewPropertyMutator.MutationFailure("stale_target", message);
    }
}
