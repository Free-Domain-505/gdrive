package dev.xorvila.gdrivebackup;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BackupService {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gdrive-backup-worker"); t.setDaemon(true); return t;
    });
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    static boolean start(MinecraftServer server, boolean manual, ServerCommandSource requester, String actor) {
        String by = actor == null || actor.isBlank() ? (manual ? "Minecraft Command" : "Automatic Scheduler") : actor;
        if (server == null) {
            String reason = "No active Minecraft world/server is available.";
            AuditDatabase.log("ERROR", by, "BACKUP_START_FAILED", reason);
            feedback(requester, reason);
            return false;
        }
        if (GDriveBackupMod.CONFIG.refreshToken.isBlank()) {
            String reason = "Google Drive is not connected. No backup upload was attempted; connect Google Drive first.";
            AuditDatabase.log("ERROR", by, "BACKUP_START_FAILED", reason);
            feedback(requester, reason);
            return false;
        }
        if (!GDriveBackupMod.BACKUP_RUNNING.compareAndSet(false, true)) {
            String reason = "A Google Drive backup is already running; this request was not queued.";
            AuditDatabase.log("WARN", by, "BACKUP_START_REJECTED", reason);
            feedback(requester, reason);
            return false;
        }
        // FabricLoader's game directory is the dedicated-server working directory on a
        // server, and the selected Minecraft game directory for an integrated client.
        // Archiving this root includes the world plus mods/config/logs/plugins if present.
        Path instanceRoot = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
            .toAbsolutePath().normalize();
        String instanceKey = shortHash(instanceRoot.toString());
        String backupPrefix = "mc-instance-backup-" + instanceKey + "-";
        String fileName = backupPrefix + STAMP.format(Instant.now()) + ".zip";
        Path temp;
        try { temp = Files.createTempDirectory("gdrivebackup-").resolve(fileName); }
        catch (IOException e) {
            GDriveBackupMod.BACKUP_RUNNING.set(false);
            AuditDatabase.log("ERROR", by, "BACKUP_TEMP_CREATE_FAILED", safeMessage(e));
            feedback(requester, "Could not create temporary backup directory: " + safeMessage(e));
            return false;
        }

        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "save-off");
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "save-all flush");
        } catch (Exception e) {
            GDriveBackupMod.LOGGER.warn("Could not run save-off/save-all; continuing with last saved world state", e);
            AuditDatabase.log("WARN", by, "WORLD_FLUSH_WARNING", safeMessage(e));
        }
        AtomicBoolean savePaused = new AtomicBoolean(true);
        AuditDatabase.log("INFO", by, "BACKUP_STARTED", "Full Minecraft instance backup started: " + fileName + ". Root=" + instanceRoot + ". Manual=" + manual);
        GDriveBackupMod.LOGGER.info("Google Drive full-instance backup started by {}: {}", by, fileName);
        feedback(requester, "Full Minecraft folder backup started: " + fileName);
        WORKER.submit(() -> {
            boolean uploaded = false;
            try {
                zipInstance(instanceRoot, temp);
                resumeWorldSaving(server, savePaused);
                long bytes = Files.size(temp);
                AuditDatabase.log("INFO", by, "BACKUP_ARCHIVE_CREATED", "Archive created: " + fileName + " (" + bytes + " bytes).");
                GoogleDriveClient drive = new GoogleDriveClient(GDriveBackupMod.CONFIG);
                String folder = drive.ensureFolder();
                drive.upload(temp, folder, fileName);
                uploaded = true;
                AuditDatabase.log("INFO", by, "BACKUP_UPLOADED", "Google Drive upload succeeded: " + fileName + " (" + bytes + " bytes).");
                var backups = drive.listBackups(folder, backupPrefix);
                int excess = backups.size() - GDriveBackupMod.CONFIG.retention;
                int removed = 0;
                for (int i = 0; i < excess; i++) {
                    GoogleDriveClient.DriveFile old = backups.get(i);
                    drive.delete(old.id());
                    removed++;
                    AuditDatabase.log("INFO", by, "RETENTION_DELETED", "Deleted oldest backup after successful upload: " + old.name());
                    GDriveBackupMod.LOGGER.info("Deleted expired backup {}", old.name());
                }
                GDriveBackupMod.LOGGER.info("Google Drive backup complete: {} ({} bytes), retained up to {} backups", fileName, bytes, GDriveBackupMod.CONFIG.retention);
                AuditDatabase.log("INFO", by, "BACKUP_COMPLETED", "Backup complete: " + fileName + "; size=" + bytes + " bytes; retained=" + Math.min(backups.size(), GDriveBackupMod.CONFIG.retention) + "; deleted=" + removed + ".");
                feedback(requester, "Backup uploaded successfully (" + humanSize(bytes) + "). Retention limit: " + GDriveBackupMod.CONFIG.retention + ".");
            } catch (Exception e) {
                String reason = safeMessage(e);
                if (uploaded) {
                    AuditDatabase.log("WARN", by, "RETENTION_CLEANUP_FAILED", "New backup was uploaded, but retention cleanup failed: " + reason);
                    GDriveBackupMod.LOGGER.warn("Backup uploaded but retention cleanup failed: {}", reason);
                    feedback(requester, "Backup uploaded, but old-backup cleanup failed: " + reason);
                } else {
                    AuditDatabase.log("ERROR", by, "BACKUP_FAILED", "Backup did not finish uploading: " + reason);
                    GDriveBackupMod.LOGGER.error("Google Drive backup failed for {}: {}", by, reason, e);
                    feedback(requester, "Google Drive backup failed: " + reason);
                }
            } finally {
                try { Files.deleteIfExists(temp); if (temp.getParent() != null) Files.deleteIfExists(temp.getParent()); }
                catch (IOException e) { AuditDatabase.log("WARN", by, "TEMP_CLEANUP_FAILED", safeMessage(e)); }
                server.execute(() -> {
                    if (savePaused.compareAndSet(true, false)) {
                        try { server.getCommandManager().executeWithPrefix(server.getCommandSource(), "save-on"); }
                        catch (Exception e) {
                            GDriveBackupMod.LOGGER.error("Could not re-enable world saving; run /save-on manually", e);
                            AuditDatabase.log("ERROR", by, "WORLD_SAVE_REENABLE_FAILED", "Could not run save-on; operator must run /save-on manually. " + safeMessage(e));
                        }
                    }
                    GDriveBackupMod.BACKUP_RUNNING.set(false);
                });
            }
        });
        return true;
    }

    private static void resumeWorldSaving(MinecraftServer server, AtomicBoolean savePaused) {
        if (savePaused.compareAndSet(true, false)) {
            server.execute(() -> {
                try { server.getCommandManager().executeWithPrefix(server.getCommandSource(), "save-on"); }
                catch (Exception e) {
                    GDriveBackupMod.LOGGER.error("Could not re-enable world saving; run /save-on manually", e);
                    AuditDatabase.log("ERROR", "system", "WORLD_SAVE_REENABLE_FAILED", safeMessage(e));
                }
            });
        }
    }

    private static void zipInstance(Path root, Path destination) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Minecraft game/server directory does not exist: " + root);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)))) {
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                    // Never follow or archive symlinks (which could escape the instance root).
                    if (Files.isSymbolicLink(path)) continue;
                    if (path.equals(root)) continue;
                    String name = path.getFileName() == null ? "" : path.getFileName().toString();
                    // session.lock is a live process lock, not useful restore data.
                    if (name.equals("session.lock")) continue;
                    String entryName = root.relativize(path).toString().replace('\\', '/');
                    if (Files.isDirectory(path)) {
                        zip.putNextEntry(new ZipEntry(entryName.endsWith("/") ? entryName : entryName + "/"));
                        zip.closeEntry();
                    } else if (Files.isRegularFile(path)) {
                        zip.putNextEntry(new ZipEntry(entryName));
                        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(path))) { in.transferTo(zip); }
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    private static void feedback(ServerCommandSource source, String message) {
        if (source == null) return;
        try { source.getServer().execute(() -> source.sendFeedback(() -> Text.literal(message), false)); }
        catch (Exception ignored) {}
    }
    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 4; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
