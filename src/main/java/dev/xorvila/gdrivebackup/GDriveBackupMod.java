package dev.xorvila.gdrivebackup;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class GDriveBackupMod implements ModInitializer {
    public static final String MOD_ID = "gdrivebackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    static BackupConfig CONFIG;
    static volatile MinecraftServer SERVER;
    static final AtomicBoolean BACKUP_RUNNING = new AtomicBoolean(false);
    private static volatile long nextBackupAtMillis = -1L;

    @Override public void onInitialize() {
        var configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        AuditDatabase.initialize(configDir);
        CONFIG = BackupConfig.load(configDir);
        WebDashboard.start(CONFIG);
        ConsoleLogArchiver.start();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            resetSchedule();
            String gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
            LOGGER.info("Google Drive Backup ready. Full Minecraft game/server directory: {}", gameDir);
            AuditDatabase.log("INFO", "system", "WORLD_SERVER_STARTED", "Minecraft server started. Full instance backup root: " + gameDir);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SERVER = null;
            GoogleDriveOAuth.stop();
            nextBackupAtMillis = -1L;
            AuditDatabase.log("INFO", "system", "WORLD_SERVER_STOPPING", "Minecraft world server is stopping.");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (SERVER != server || !CONFIG.enabled || CONFIG.refreshToken.isBlank()) return;
            long now = System.currentTimeMillis();
            if (nextBackupAtMillis < 0) resetSchedule();
            if (nextBackupAtMillis > 0 && now >= nextBackupAtMillis) {
                resetSchedule();
                BackupService.start(server, false, null, "Automatic Scheduler");
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            literal("gdrivebackup").requires(source -> source.hasPermissionLevel(2))
                .then(literal("now").executes(ctx -> {
                    String actor = actor(ctx.getSource());
                    boolean started = BackupService.start(ctx.getSource().getServer(), true, ctx.getSource(), actor);
                    if (started) ctx.getSource().sendFeedback(() -> Text.literal("Google Drive backup requested."), true);
                    return started ? 1 : 0;
                }))
                .then(literal("connect").executes(ctx -> {
                    String actor = actor(ctx.getSource());
                    String result = GoogleDriveOAuth.begin(CONFIG, actor);
                    ctx.getSource().sendFeedback(() -> Text.literal(result.startsWith("ERROR: ") ? result : "Open this Google authorization link: " + result), false);
                    return result.startsWith("ERROR: ") ? 0 : 1;
                }))
                .then(literal("status").executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    src.sendFeedback(() -> Text.literal("Google Drive: " + (CONFIG.refreshToken.isBlank() ? "not connected" : "connected")), false);
                    src.sendFeedback(() -> Text.literal("Auto backup: " + (CONFIG.enabled ? "enabled" : "disabled") + " | every " + CONFIG.intervalMinutes + " minutes | keep " + CONFIG.retention), false);
                    src.sendFeedback(() -> Text.literal("Backup running: " + BACKUP_RUNNING.get()), false);
                    src.sendFeedback(() -> Text.literal("Drive folder: " + CONFIG.driveFolder), false);
                    src.sendFeedback(() -> Text.literal("Dashboard: " + CONFIG.publicBaseUrl + "/dashboard"), false);
                    src.sendFeedback(() -> Text.literal("Cloudflared running: " + CloudflaredTunnel.isRunning() + " | DB: " + AuditDatabase.databaseFile()), false);
                    return 1;
                }))
                .then(literal("dashboard").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Dashboard: " + CONFIG.publicBaseUrl + "/dashboard"), false);
                    ctx.getSource().sendFeedback(() -> Text.literal("Dashboard password is stored in config/gdrivebackup.json -> dashboardPassword."), false);
                    return 1;
                }))
                .then(literal("enable").executes(ctx -> {
                    CONFIG.enabled = true; CONFIG.save(); resetSchedule();
                    AuditDatabase.log("INFO", actor(ctx.getSource()), "BACKUP_SCHEDULER_ENABLED", "Automatic backups enabled from Minecraft command.");
                    ctx.getSource().sendFeedback(() -> Text.literal("Automatic backups enabled."), true); return 1;
                }))
                .then(literal("disable").executes(ctx -> {
                    CONFIG.enabled = false; CONFIG.save(); resetSchedule();
                    AuditDatabase.log("WARN", actor(ctx.getSource()), "BACKUP_SCHEDULER_DISABLED", "Automatic backups disabled from Minecraft command.");
                    ctx.getSource().sendFeedback(() -> Text.literal("Automatic backups disabled."), true); return 1;
                }))
                .then(literal("interval").then(argument("minutes", IntegerArgumentType.integer(1, 43200)).executes(ctx -> {
                    CONFIG.intervalMinutes = IntegerArgumentType.getInteger(ctx, "minutes"); CONFIG.save(); resetSchedule();
                    AuditDatabase.log("INFO", actor(ctx.getSource()), "BACKUP_INTERVAL_CHANGED", "Backup interval changed to " + CONFIG.intervalMinutes + " minutes.");
                    ctx.getSource().sendFeedback(() -> Text.literal("Backup interval set to " + CONFIG.intervalMinutes + " minutes."), true); return 1;
                })))
                .then(literal("intervalhours").then(argument("hours", IntegerArgumentType.integer(1, 720)).executes(ctx -> {
                    CONFIG.intervalMinutes = IntegerArgumentType.getInteger(ctx, "hours") * 60; CONFIG.save(); resetSchedule();
                    AuditDatabase.log("INFO", actor(ctx.getSource()), "BACKUP_INTERVAL_CHANGED", "Backup interval changed to " + CONFIG.intervalMinutes + " minutes using legacy hours command.");
                    ctx.getSource().sendFeedback(() -> Text.literal("Backup interval set to " + CONFIG.intervalMinutes + " minutes."), true); return 1;
                })))
                .then(literal("retention").then(argument("count", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                    CONFIG.retention = IntegerArgumentType.getInteger(ctx, "count"); CONFIG.save();
                    AuditDatabase.log("INFO", actor(ctx.getSource()), "RETENTION_CHANGED", "Retention changed to " + CONFIG.retention + " backups.");
                    ctx.getSource().sendFeedback(() -> Text.literal("Retention set to " + CONFIG.retention + " backups."), true); return 1;
                })))
                .then(literal("folder").then(argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(ctx -> {
                    CONFIG.driveFolder = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"); CONFIG.save();
                    AuditDatabase.log("INFO", actor(ctx.getSource()), "DRIVE_FOLDER_CHANGED", "Drive folder changed to " + CONFIG.driveFolder + ".");
                    ctx.getSource().sendFeedback(() -> Text.literal("Drive folder set to " + CONFIG.driveFolder), true); return 1;
                })))
                .then(literal("tunnel").then(literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Cloudflared auto-start: " + CONFIG.autoStartCloudflared + " | managed process running: " + CloudflaredTunnel.isRunning()), false);
                    ctx.getSource().sendFeedback(() -> Text.literal("Public callback: " + CONFIG.publicBaseUrl + CONFIG.callbackPath), false);
                    return 1;
                })))
                .then(literal("disconnect").executes(ctx -> {
                    String actor = actor(ctx.getSource());
                    CONFIG.refreshToken = ""; CONFIG.save();
                    AuditDatabase.log("WARN", actor, "GOOGLE_DISCONNECTED", "Google Drive refresh token removed from this installation.");
                    ctx.getSource().sendFeedback(() -> Text.literal("Google account disconnected."), true); return 1;
                }))
        ));
        LOGGER.info("Google Drive Auto Backup 1.5.0 initialized for Minecraft 1.20.1 / Fabric.");
    }

    static void resetSchedule() {
        if (CONFIG == null || SERVER == null || !CONFIG.enabled || CONFIG.refreshToken.isBlank()) { nextBackupAtMillis = -1L; return; }
        nextBackupAtMillis = System.currentTimeMillis() + CONFIG.intervalMinutes * 60_000L;
    }

    static long nextBackupAt() { return nextBackupAtMillis; }

    private static String actor(ServerCommandSource source) {
        try { return source.getName(); } catch (Exception ignored) { return "Minecraft Command"; }
    }
}
