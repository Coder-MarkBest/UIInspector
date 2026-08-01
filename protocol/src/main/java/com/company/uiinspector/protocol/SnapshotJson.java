package com.company.uiinspector.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class SnapshotJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SnapshotJson() {}

    public static String encode(InspectionSnapshot snapshot) {
        if (snapshot.schemaVersion() != InspectionSnapshot.CURRENT_SCHEMA_VERSION) {
            throw unsupported(snapshot.schemaVersion());
        }
        return GSON.toJson(snapshot);
    }

    public static InspectionSnapshot decode(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        int version = object.has("schemaVersion") ? object.get("schemaVersion").getAsInt() : 0;
        if (version != InspectionSnapshot.CURRENT_SCHEMA_VERSION) {
            throw unsupported(version);
        }
        return GSON.fromJson(object, InspectionSnapshot.class);
    }

    private static IllegalArgumentException unsupported(int version) {
        return new IllegalArgumentException("Unsupported snapshot schema version: " + version);
    }
}
