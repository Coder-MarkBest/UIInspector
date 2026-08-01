package com.company.uiinspector.plugin.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessAdbClient implements AdbClient {
    private final Path adb;

    public ProcessAdbClient(Path adb) {
        this.adb = adb;
    }

    @Override
    public CommandResult run(List<String> arguments, Duration timeout) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(adb.toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).start();
        ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "uiinspector-adb-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<byte[]> stdout = readers.submit(() -> read(process.getInputStream()));
        Future<byte[]> stderr = readers.submit(() -> read(process.getErrorStream()));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("adb command timed out after " + timeout.toSeconds() + " seconds.");
            }
            byte[] output = bytes(stdout, timeout);
            byte[] error = bytes(stderr, timeout);
            return new CommandResult(
                    process.exitValue(),
                    new String(output, StandardCharsets.UTF_8),
                    new String(error, StandardCharsets.UTF_8),
                    output);
        } finally {
            readers.shutdownNow();
        }
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            stream.transferTo(output);
            return output.toByteArray();
        }
    }

    private static byte[] bytes(Future<byte[]> future, Duration timeout) throws IOException, InterruptedException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw new IOException("Unable to read adb output.", error.getCause());
        } catch (TimeoutException error) {
            throw new IOException("Timed out reading adb output.", error);
        }
    }
}
