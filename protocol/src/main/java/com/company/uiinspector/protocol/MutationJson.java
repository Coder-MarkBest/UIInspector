package com.company.uiinspector.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class MutationJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MutationJson() {}

    public static String encodeCommand(MutationCommand command) {
        requireVersion(command.schemaVersion());
        return GSON.toJson(command);
    }

    public static MutationCommand decodeCommand(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        requireVersion(readVersion(object));
        return GSON.fromJson(object, MutationCommand.class);
    }

    public static String encodeResult(MutationResult result) {
        requireVersion(result.schemaVersion());
        return GSON.toJson(result);
    }

    public static MutationResult decodeResult(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        requireVersion(readVersion(object));
        return GSON.fromJson(object, MutationResult.class);
    }

    private static int readVersion(JsonObject object) {
        return object.has("schemaVersion") ? object.get("schemaVersion").getAsInt() : 0;
    }

    private static void requireVersion(int version) {
        if (version != MutationCommand.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported mutation schema version: " + version);
        }
    }
}
