package dev.xorvila.gdrivebackup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

final class BackupConfig {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    boolean enabled = true;
    int intervalMinutes = 360;
    // Kept only for migration from 1.0.x-1.2.x config files.
    transient int intervalHours = 0;
    int retention = 10;
    String driveFolder = "Minecraft Backups";

    String clientId = "YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID";
    String clientSecret = "YOUR_GOOGLE_OAUTH_WEB_CLIENT_SECRET";
    String refreshToken = "";

    String publicBaseUrl = "https://xdrive.xorvilahub.indevs.in";
    String callbackPath = "/oauth2/callback";
    int oauthPort = 8765;
    boolean autoStartCloudflared = true;
    String cloudflaredExecutable = "cloudflared";
    String cloudflaredToken = "";

    boolean dashboardEnabled = true;
    String dashboardPassword = "";
    // Stable signing key lets authenticated browser sessions survive process/server restarts.
    // This value is generated once, stored only in this protected config, and never sent to the browser.
    String dashboardSessionSecret = "";

    transient Path file;

    static BackupConfig load(Path configDir) {
        Path file = configDir.resolve("gdrivebackup.json");
        BackupConfig config;
        try {
            if (Files.exists(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!json.has("intervalMinutes") && json.has("intervalHours")) {
                    int oldHours = Math.max(1, json.get("intervalHours").getAsInt());
                    json.addProperty("intervalMinutes", oldHours * 60);
                }
                config = GSON.fromJson(json, BackupConfig.class);
            } else {
                config = new BackupConfig();
            }
        } catch (Exception e) {
            GDriveBackupMod.LOGGER.error("Could not read config; using defaults", e);
            AuditDatabase.log("ERROR", "system", "CONFIG_LOAD_FAILED", safeMessage(e));
            config = new BackupConfig();
        }
        if (config == null) config = new BackupConfig();
        config.file = file;
        config.normalize();
        if (config.dashboardPassword.isBlank()) {
            config.dashboardPassword = randomSecret(24);
            GDriveBackupMod.LOGGER.warn("A dashboard password was generated. See config/gdrivebackup.json -> dashboardPassword. Do not share it.");
            AuditDatabase.log("WARN", "system", "DASHBOARD_PASSWORD_GENERATED", "Dashboard login password generated and stored in config/gdrivebackup.json.");
        }
        if (config.dashboardSessionSecret.isBlank()) {
            config.dashboardSessionSecret = randomSecret(48);
        }
        config.save();
        return config;
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
            try {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Some mobile/Windows filesystems do not expose POSIX permissions.
            }
            AuditDatabase.storeSettings(this);
        } catch (IOException e) {
            GDriveBackupMod.LOGGER.error("Could not save config", e);
            AuditDatabase.log("ERROR", "system", "CONFIG_SAVE_FAILED", safeMessage(e));
        }
    }

    private void normalize() {
        if (intervalMinutes <= 0) intervalMinutes = intervalHours > 0 ? intervalHours * 60 : 360;
        intervalMinutes = Math.max(1, Math.min(43200, intervalMinutes));
        intervalHours = 0;
        retention = Math.max(1, Math.min(100, retention));
        oauthPort = Math.max(1024, Math.min(65535, oauthPort));
        if (driveFolder == null || driveFolder.isBlank()) driveFolder = "Minecraft Backups";
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) publicBaseUrl = "https://xdrive.xorvilahub.indevs.in";
        publicBaseUrl = publicBaseUrl.trim().replaceAll("/+$", "");
        if (callbackPath == null || callbackPath.isBlank()) callbackPath = "/oauth2/callback";
        callbackPath = callbackPath.trim();
        if (!callbackPath.startsWith("/")) callbackPath = "/" + callbackPath;
        if (cloudflaredExecutable == null || cloudflaredExecutable.isBlank()) cloudflaredExecutable = "cloudflared";
        if (cloudflaredToken == null) cloudflaredToken = "";
        if (dashboardPassword == null) dashboardPassword = "";
        if (dashboardSessionSecret == null) dashboardSessionSecret = "";
        if (clientId == null) clientId = "";
        if (clientSecret == null) clientSecret = "";
        if (refreshToken == null) refreshToken = "";
    }

    String redirectUri() {
        URI base = URI.create(publicBaseUrl);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
            || base.getRawQuery() != null || base.getRawFragment() != null
            || (base.getPort() != -1 && base.getPort() != 443)) {
            throw new IllegalArgumentException("publicBaseUrl must be an HTTPS URL on port 443, without a path, query or fragment.");
        }
        if (!callbackPath.matches("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*") || callbackPath.contains("..")) {
            throw new IllegalArgumentException("callbackPath contains unsupported characters.");
        }
        return publicBaseUrl + callbackPath;
    }

    static String randomSecret(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
