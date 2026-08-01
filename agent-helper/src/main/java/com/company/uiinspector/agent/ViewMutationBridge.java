package com.company.uiinspector.agent;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.MutationJson;
import com.company.uiinspector.protocol.MutationResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class ViewMutationBridge {
    private ViewMutationBridge() {}

    public static void mutate(
            List<View> roots,
            List<WindowManager.LayoutParams> windowParams,
            String commandPath,
            String outputPath) {
        Runnable mutation = () -> execute(roots, windowParams, commandPath, outputPath);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mutation.run();
        } else {
            new Handler(Looper.getMainLooper()).post(mutation);
        }
    }

    private static void execute(
            List<View> roots,
            List<WindowManager.LayoutParams> windowParams,
            String commandPath,
            String outputPath) {
        MutationResult result;
        try {
            String json = new String(Files.readAllBytes(new File(commandPath).toPath()), StandardCharsets.UTF_8);
            MutationCommand command = MutationJson.decodeCommand(json);
            var outcome = new ViewPropertyMutator().apply(roots, windowParams, command);
            result = new MutationResult(
                    MutationResult.CURRENT_SCHEMA_VERSION,
                    true,
                    outcome.code(),
                    "Applied",
                    outcome.previousValue(),
                    outcome.appliedValue(),
                    outcome.snapshot());
        } catch (ViewPropertyMutator.MutationFailure error) {
            result = failure(error.code(), error.getMessage(), roots, windowParams);
        } catch (RuntimeException | IOException error) {
            result = failure("mutation_failed", safeMessage(error), roots, windowParams);
        }
        ViewCaptureBridge.writeJson(MutationJson.encodeResult(result), outputPath);
    }

    private static MutationResult failure(
            String code,
            String message,
            List<View> roots,
            List<WindowManager.LayoutParams> params) {
        InspectionSnapshot snapshot = null;
        try {
            String packageName = roots.isEmpty() ? null : roots.get(0).getContext().getPackageName();
            snapshot = ViewCaptureBridge.captureNow(
                    roots, params, "", packageName, Application.getProcessName());
        } catch (RuntimeException ignored) {
            // The stable failure still reaches the desktop if refresh capture also fails.
        }
        return new MutationResult(
                MutationResult.CURRENT_SCHEMA_VERSION,
                false,
                code,
                message,
                null,
                null,
                snapshot);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
