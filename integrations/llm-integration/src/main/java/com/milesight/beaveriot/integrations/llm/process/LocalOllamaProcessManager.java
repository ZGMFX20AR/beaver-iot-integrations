package com.milesight.beaveriot.integrations.llm.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts/stops the {@code hailo-ollama} binary as a child process of this application.
 * Only works when the container has the Hailo device passed through (see
 * beaver-iot-docker/build-docker/beaver-iot-npu.dockerfile) and the binary/library are
 * present at the paths below - both baked into that image.
 */
@Slf4j
@Component
public class LocalOllamaProcessManager {

    private static final String HAILO_OLLAMA_BIN = "/usr/bin/hailo-ollama";
    private static final String OLLAMA_HOST_VALUE = "0.0.0.0:11434";

    private final AtomicReference<Process> processRef = new AtomicReference<>();

    public synchronized void start() {
        Process existing = processRef.get();
        if (existing != null && existing.isAlive()) {
            log.info("hailo-ollama is already running (pid {})", existing.pid());
            return;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(HAILO_OLLAMA_BIN);
            builder.environment().put("OLLAMA_HOST", OLLAMA_HOST_VALUE);
            builder.redirectErrorStream(true);
            builder.inheritIO();
            Process process = builder.start();
            processRef.set(process);
            log.info("Started hailo-ollama (pid {})", process.pid());
        } catch (IOException e) {
            processRef.set(null);
            throw new IllegalStateException("Failed to start hailo-ollama: " + e.getMessage(), e);
        }
    }

    public synchronized void stop() {
        Process process = processRef.get();
        if (process == null || !process.isAlive()) {
            log.info("hailo-ollama is not running");
            processRef.set(null);
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                log.warn("hailo-ollama did not exit within 10s of SIGTERM, forcing kill");
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        processRef.set(null);
        log.info("Stopped hailo-ollama");
    }

    public boolean isRunning() {
        Process process = processRef.get();
        return process != null && process.isAlive();
    }

    /**
     * Starts hailo-ollama on application boot if its binary is present in this image
     * (i.e. the NPU-enabled container). No-ops silently on the standard image, where
     * the binary isn't bundled and starting it isn't expected to be possible.
     */
    public synchronized void startIfAvailable() {
        if (!Files.exists(Path.of(HAILO_OLLAMA_BIN))) {
            log.info("hailo-ollama binary not found at {}, skipping auto-start", HAILO_OLLAMA_BIN);
            return;
        }
        try {
            start();
        } catch (Exception e) {
            log.warn("Auto-start of hailo-ollama failed, it can still be started manually from the LLM Integration page", e);
        }
    }

}
