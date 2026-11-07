package dev.xorvila.gdrivebackup;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Archives dashboard audit-console events to Google Drive. The database retains the newest
 * 50 events; older rows are removed only after the corresponding Drive file is confirmed.
 */
final class ConsoleLogArchiver {
    private static final int LOCAL_KEEP = 50;
    private static final int BATCH_SIZE = 500;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gdrive-console-log-archiver");
        t.setDaemon(true);
        return t;
    });
    private static volatile long lastFailureLogAt;

    private ConsoleLogArchiver() {}

    static void start() {
        WORKER.scheduleWithFixedDelay(ConsoleLogArchiver::archivePending, 15, 30, TimeUnit.SECONDS);
    }

    static int localKeepLimit() { return LOCAL_KEEP; }

    private static void archivePending() {
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            BackupConfig config = GDriveBackupMod.CONFIG;
            if (config == null || config.refreshToken == null || config.refreshToken.isBlank()) return;

            GoogleDriveClient drive = new GoogleDriveClient(config);
            String folderId = null;
            while (true) {
                AuditDatabase.ArchiveBatch batch = AuditDatabase.oldestBatchForArchive(LOCAL_KEEP, BATCH_SIZE);
                List<Long> ids = batch.ids();
                if (ids.isEmpty() || batch.rows().isEmpty()) return;

                long firstId = ids.get(0);
                long lastId = ids.get(ids.size() - 1);
                String fileName = "console-events-" + firstId + "-" + lastId + ".json";
                Path temp = null;
                try {
                    JsonObject archive = new JsonObject();
                    archive.addProperty("format", "xorvila-gdrivebackup-console-events-v1");
                    archive.addProperty("createdAt", Instant.now().toString());
                    archive.addProperty("firstEventId", firstId);
                    archive.addProperty("lastEventId", lastId);
                    archive.add("events", batch.rows());
                    temp = Files.createTempFile("gdrive-console-logs-", ".json");
                    Files.writeString(temp, BackupConfig.GSON.toJson(archive));

                    if (folderId == null) folderId = drive.ensureFolder();
                    // If the process stopped after upload but before the DB delete, reuse the
                    // already uploaded archive instead of creating a duplicate Drive file.
                    boolean alreadyUploaded = drive.listBackups(folderId, fileName).stream()
                        .anyMatch(f -> fileName.equals(f.name()));
                    if (!alreadyUploaded) drive.upload(temp, folderId, fileName);

                    // This is intentionally after a successful upload (or verified prior upload).
                    AuditDatabase.deleteArchivedEvents(ids);
                    GDriveBackupMod.LOGGER.info("Archived {} dashboard console events to Google Drive ({}); kept newest {} locally.", ids.size(), fileName, LOCAL_KEEP);
                } finally {
                    if (temp != null) {
                        try { Files.deleteIfExists(temp); }
                        catch (Exception cleanupError) { GDriveBackupMod.LOGGER.warn("Could not remove temporary console archive {}: {}", temp, safeMessage(cleanupError)); }
                    }
                }
            }
        } catch (Exception e) {
            GDriveBackupMod.LOGGER.warn("Console log archive was not completed; local database events were kept: {}", safeMessage(e));
            long now = System.currentTimeMillis();
            if (now - lastFailureLogAt > TimeUnit.MINUTES.toMillis(10)) {
                lastFailureLogAt = now;
                AuditDatabase.log("WARN", "Console Log Archiver", "CONSOLE_LOG_ARCHIVE_FAILED",
                    "Older console events remain safely stored in the local database because their Google Drive archive did not complete: " + safeMessage(e));
            }
        } finally {
            RUNNING.set(false);
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
