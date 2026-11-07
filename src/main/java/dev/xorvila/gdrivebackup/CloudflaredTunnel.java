package dev.xorvila.gdrivebackup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Starts an optional named Cloudflare Tunnel without ever logging its token. */
final class CloudflaredTunnel {
    private static volatile Process process;

    private CloudflaredTunnel() {}

    static synchronized void startIfConfigured(BackupConfig config) throws Exception {
        if (!config.autoStartCloudflared) return; // An externally managed tunnel may already be running.
        if (process != null && process.isAlive()) return;
        if (config.cloudflaredToken == null || config.cloudflaredToken.isBlank()) {
            throw new IllegalStateException("Cloudflare Tunnel token is empty. Set cloudflaredToken in config/gdrivebackup.json or use the dashboard, or set autoStartCloudflared=false when managing the tunnel externally.");
        }

        ProcessBuilder builder = new ProcessBuilder(
            config.cloudflaredExecutable, "tunnel", "run", "--token", config.cloudflaredToken);
        builder.redirectErrorStream(true);
        Process started = builder.start();
        process = started;

        Thread output = new Thread(() -> relayOutput(started, config.cloudflaredToken), "gdrive-cloudflared-output");
        output.setDaemon(true);
        output.start();

        // Catch immediate failures such as a missing executable or rejected tunnel token.
        if (started.waitFor(900, TimeUnit.MILLISECONDS)) {
            process = null;
            throw new IllegalStateException("cloudflared exited immediately with code " + started.exitValue() + ". Check cloudflaredExecutable, the rotated tunnel token and the tunnel's hostname route.");
        }
        GDriveBackupMod.LOGGER.info("Cloudflare Tunnel process started for the configured dashboard/OAuth hostname.");
        AuditDatabase.log("INFO", "system", "CLOUDFLARED_STARTED", "Cloudflare Tunnel connector process started.");
    }

    private static void relayOutput(Process started, String secret) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safe = secret == null || secret.isEmpty() ? line : line.replace(secret, "[REDACTED]");
                GDriveBackupMod.LOGGER.info("cloudflared: {}", safe);
                String upper = safe.toUpperCase(java.util.Locale.ROOT);
                String level = (upper.contains("ERR") || upper.contains("FAIL") || upper.contains("ERROR")) ? "ERROR" : (upper.contains("WARN") ? "WARN" : "INFO");
                AuditDatabase.log(level, "Cloudflare Tunnel", "CLOUDFLARED_OUTPUT", safe);
            }
        } catch (Exception e) {
            GDriveBackupMod.LOGGER.debug("Cloudflared output reader ended: {}", e.getMessage());
        }
    }

    static synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    static synchronized void stopManagedProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) return;
        AuditDatabase.log("INFO", "system", "CLOUDFLARED_STOPPING", "Stopping managed Cloudflare Tunnel connector.");
        current.destroy();
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }
}
