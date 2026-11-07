package dev.xorvila.gdrivebackup;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Password-protected web control panel served behind the configured Cloudflare Tunnel. */
final class WebDashboard {
    private static final long SESSION_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static volatile LocalHttpServer server;

    private WebDashboard() {}

    static synchronized void start(BackupConfig config) {
        if (server != null) return;
        try {
            LocalHttpServer http = new LocalHttpServer("127.0.0.1", config.oauthPort);
            http.createContext(config.callbackPath, GoogleDriveOAuth::handleCallback);
            http.createContext("/dashboard", WebDashboard::dashboardPage);
            http.createContext("/api", WebDashboard::api);
            http.createContext("/", WebDashboard::root);
            http.start();
            server = http;
            AuditDatabase.log("INFO", "system", "WEB_DASHBOARD_STARTED", "Dashboard and OAuth callback listening on 127.0.0.1:" + config.oauthPort + ". Public address: " + config.publicBaseUrl + "/dashboard");
            GDriveBackupMod.LOGGER.info("GDrive Backup dashboard listening on 127.0.0.1:{}; public URL: {}/dashboard", config.oauthPort, config.publicBaseUrl);
            CloudflaredTunnel.startIfConfigured(config);
        } catch (Exception e) {
            AuditDatabase.log("ERROR", "system", "WEB_DASHBOARD_START_FAILED", safeMessage(e));
            GDriveBackupMod.LOGGER.error("Could not start GDrive Backup dashboard/OAuth endpoint: {}", safeMessage(e));
        }
    }

    static synchronized void stop() {
        LocalHttpServer current = server;
        server = null;
        if (current != null) current.stop();
        CloudflaredTunnel.stopManagedProcess();
    }

    static boolean isRunning() { return server != null; }

    private static void root(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Location", "/dashboard");
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static void dashboardPage(HttpExchange ex) throws IOException {
        if (!GDriveBackupMod.CONFIG.dashboardEnabled) { sendText(ex, 404, "Dashboard is disabled in config.", "text/plain; charset=utf-8"); return; }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 405, error("Method not allowed")); return; }
        try (var in = WebDashboard.class.getResourceAsStream("/dashboard.html")) {
            if (in == null) { sendText(ex, 500, "Dashboard resource dashboard.html is missing from the mod JAR.", "text/plain; charset=utf-8"); return; }
            byte[] page = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'");
            ex.sendResponseHeaders(200, page.length);
            try (var out = ex.getResponseBody()) { out.write(page); }
        }
    }

    private static void api(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-GDrive-Dashboard"))) {
            AuditDatabase.log("WARN", "Web Client", "DASHBOARD_REQUEST_REJECTED", "API request rejected because the required same-origin dashboard header was missing.");
            sendJson(ex, 403, error("Request origin check failed.")); return;
        }
        if (!GDriveBackupMod.CONFIG.dashboardEnabled) { sendJson(ex, 404, error("Dashboard API is disabled in config.")); return; }
        if ("/api/login".equals(path) && "POST".equals(method)) { login(ex); return; }
        if (!authorized(ex)) { sendJson(ex, 401, error("Dashboard login required.")); return; }
        if ("/api/logout".equals(path) && "POST".equals(method)) {
            ex.getResponseHeaders().add("Set-Cookie", "gdb_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (isHttps(ex) ? "; Secure" : ""));
            AuditDatabase.log("INFO", "Dashboard Admin", "DASHBOARD_LOGOUT", "Dashboard session ended.");
            sendJson(ex, 200, ok("Logged out.")); return;
        }
        if ("/api/state".equals(path) && "GET".equals(method)) { sendJson(ex, 200, state()); return; }
        if ("/api/logs".equals(path) && "GET".equals(method)) {
            JsonObject payload = new JsonObject();
            payload.add("logs", AuditDatabase.recentLogs(ConsoleLogArchiver.localKeepLimit()));
            sendJson(ex, 200, payload); return;
        }
        if ("/api/settings".equals(path) && "POST".equals(method)) { saveSettings(ex); return; }
        if ("/api/action".equals(path) && "POST".equals(method)) { action(ex); return; }
        if ("/api/command".equals(path) && "POST".equals(method)) { command(ex); return; }
        sendJson(ex, 404, error("Unknown dashboard API route."));
    }

    private static void login(HttpExchange ex) throws IOException {
        try {
            JsonObject body = readJson(ex);
            String pass = body.has("password") ? body.get("password").getAsString() : "";
            byte[] expected = GDriveBackupMod.CONFIG.dashboardPassword.getBytes(StandardCharsets.UTF_8);
            byte[] received = pass.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, received)) {
                AuditDatabase.log("WARN", "Dashboard login", "DASHBOARD_LOGIN_FAILED", "A login attempt supplied an incorrect dashboard password.");
                sendJson(ex, 401, error("Incorrect dashboard password.")); return;
            }
            ex.getResponseHeaders().add("Set-Cookie", sessionCookie(createSessionToken(GDriveBackupMod.CONFIG)));
            AuditDatabase.log("INFO", "Dashboard Admin", "DASHBOARD_LOGIN", "Dashboard login succeeded.");
            sendJson(ex, 200, ok("Authenticated."));
        } catch (Exception e) {
            AuditDatabase.log("ERROR", "Dashboard login", "DASHBOARD_LOGIN_ERROR", safeMessage(e));
            sendJson(ex, 400, error("Could not process login request."));
        }
    }

    private static boolean authorized(HttpExchange ex) {
        String token = sessionId(ex);
        if (token == null) return false;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) return false;
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            byte[] expectedSignature = hmac(parts[0]);
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) return false;
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 3) return false;
            long now = System.currentTimeMillis();
            long expiry = Long.parseLong(fields[0]);
            if (expiry < now || expiry > now + SESSION_MILLIS + 60_000) return false;
            boolean passwordMatches = MessageDigest.isEqual(fields[1].getBytes(StandardCharsets.UTF_8), passwordFingerprint(GDriveBackupMod.CONFIG.dashboardPassword).getBytes(StandardCharsets.UTF_8));
            if (!passwordMatches) return false;
            // Sliding 30-day expiry: active dashboards renew before the cookie gets close to expiry.
            if (expiry - now < SESSION_MILLIS / 2) ex.getResponseHeaders().set("Set-Cookie", sessionCookie(createSessionToken(GDriveBackupMod.CONFIG)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String createSessionToken(BackupConfig config) throws Exception {
        long expiry = System.currentTimeMillis() + SESSION_MILLIS;
        String payload = expiry + "|" + passwordFingerprint(config.dashboardPassword) + "|" + BackupConfig.randomSecret(18);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(encoded));
    }

    private static byte[] hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(GDriveBackupMod.CONFIG.dashboardSessionSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String passwordFingerprint(String password) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String sessionCookie(String token) {
        return "gdb_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000" + (isPublicHttps() ? "; Secure" : "");
    }

    private static boolean isPublicHttps() {
        return GDriveBackupMod.CONFIG.publicBaseUrl != null && GDriveBackupMod.CONFIG.publicBaseUrl.startsWith("https://");
    }

    private static String sessionId(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("gdb_session=")) return trimmed.substring("gdb_session=".length());
        }
        return null;
    }

    private static JsonObject state() {
        BackupConfig c = GDriveBackupMod.CONFIG;
        JsonObject out = new JsonObject();
        out.addProperty("enabled", c.enabled);
        out.addProperty("intervalMinutes", c.intervalMinutes);
        out.addProperty("retention", c.retention);
        out.addProperty("driveFolder", c.driveFolder);
        out.addProperty("connected", !c.refreshToken.isBlank());
        out.addProperty("clientConfigured", configured(c.clientId) && configured(c.clientSecret));
        out.addProperty("clientId", c.clientId == null ? "" : maskClientId(c.clientId));
        out.addProperty("clientSecretConfigured", configured(c.clientSecret));
        out.addProperty("publicBaseUrl", c.publicBaseUrl);
        out.addProperty("callbackUrl", c.publicBaseUrl + c.callbackPath);
        out.addProperty("oauthPort", c.oauthPort);
        out.addProperty("cloudflaredExecutable", c.cloudflaredExecutable);
        out.addProperty("tunnelRunning", CloudflaredTunnel.isRunning());
        out.addProperty("tunnelAutoStart", c.autoStartCloudflared);
        out.addProperty("tunnelTokenConfigured", c.cloudflaredToken != null && !c.cloudflaredToken.isBlank());
        out.addProperty("dashboardRunning", isRunning());
        out.addProperty("backupRunning", GDriveBackupMod.BACKUP_RUNNING.get());
        if (GDriveBackupMod.nextBackupAt() > 0) out.addProperty("nextBackupAt", GDriveBackupMod.nextBackupAt());
        else out.add("nextBackupAt", com.google.gson.JsonNull.INSTANCE);
        out.addProperty("worldAvailable", GDriveBackupMod.SERVER != null);
        out.addProperty("databaseFile", AuditDatabase.databaseFile() == null ? "unavailable" : AuditDatabase.databaseFile().toString());
        out.addProperty("pendingConsoleLogs", AuditDatabase.eventCount());
        out.addProperty("consoleLogLocalLimit", ConsoleLogArchiver.localKeepLimit());
        out.addProperty("googleCloudConsoleUrl", "https://console.cloud.google.com/auth/clients");
        return out;
    }

    private static void saveSettings(HttpExchange ex) throws IOException {
        try {
            JsonObject body = readJson(ex);
            BackupConfig c = GDriveBackupMod.CONFIG;
            String previousTunnelToken = c.cloudflaredToken;
            boolean previousTunnelAutoStart = c.autoStartCloudflared;
            boolean passwordChanged = false;
            if (body.has("enabled")) c.enabled = body.get("enabled").getAsBoolean();
            if (body.has("intervalMinutes")) {
                int n = body.get("intervalMinutes").getAsInt();
                if (n < 1 || n > 43200) throw new IllegalArgumentException("Backup interval must be between 1 and 43200 minutes.");
                c.intervalMinutes = n;
            }
            if (body.has("retention")) {
                int n = body.get("retention").getAsInt();
                if (n < 1 || n > 100) throw new IllegalArgumentException("Retention must be between 1 and 100 backups.");
                c.retention = n;
            }
            if (body.has("driveFolder")) {
                String name = body.get("driveFolder").getAsString().trim();
                if (name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Drive folder name must be 1-120 characters.");
                c.driveFolder = name;
            }
            if (body.has("clientId")) {
                String value = body.get("clientId").getAsString().trim();
                if (!value.isBlank() && !value.equals(c.clientId)) c.clientId = value;
            }
            if (body.has("clientSecret")) {
                String value = body.get("clientSecret").getAsString().trim();
                if (!value.isBlank()) c.clientSecret = value;
            }
            if (body.has("cloudflaredToken")) {
                String value = body.get("cloudflaredToken").getAsString().trim();
                if (!value.isBlank()) c.cloudflaredToken = value;
            }
            if (body.has("autoStartCloudflared")) c.autoStartCloudflared = body.get("autoStartCloudflared").getAsBoolean();
            if (body.has("cloudflaredExecutable")) {
                String value = body.get("cloudflaredExecutable").getAsString().trim();
                if (!value.isBlank() && value.length() < 500) c.cloudflaredExecutable = value;
            }
            if (body.has("dashboardPassword")) {
                String value = body.get("dashboardPassword").getAsString();
                if (!value.isBlank()) {
                    if (value.length() < 12 || value.length() > 128) throw new IllegalArgumentException("Dashboard password must be 12-128 characters.");
                    if (!MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), c.dashboardPassword.getBytes(StandardCharsets.UTF_8))) {
                        c.dashboardPassword = value;
                        passwordChanged = true;
                    }
                }
            }
            c.save();
            if (passwordChanged) ex.getResponseHeaders().set("Set-Cookie", sessionCookie(createSessionToken(c)));
            GDriveBackupMod.resetSchedule();
            if (!java.util.Objects.equals(previousTunnelToken, c.cloudflaredToken) || previousTunnelAutoStart != c.autoStartCloudflared) {
                CloudflaredTunnel.stopManagedProcess();
                if (c.autoStartCloudflared) CloudflaredTunnel.startIfConfigured(c);
            }
            AuditDatabase.log("INFO", "Dashboard Admin", "SETTINGS_UPDATED", "Live settings saved: enabled=" + c.enabled + ", intervalMinutes=" + c.intervalMinutes + ", retention=" + c.retention + ", driveFolder=" + c.driveFolder + ". Secret values omitted.");
            sendJson(ex, 200, ok("Settings saved and applied live. The signed dashboard session survives server restarts."));
        } catch (IllegalArgumentException e) {
            AuditDatabase.log("WARN", "Dashboard Admin", "SETTINGS_REJECTED", safeMessage(e));
            sendJson(ex, 400, error(safeMessage(e)));
        } catch (Exception e) {
            AuditDatabase.log("ERROR", "Dashboard Admin", "SETTINGS_SAVE_FAILED", safeMessage(e));
            sendJson(ex, 500, error("Settings could not be saved: " + safeMessage(e)));
        }
    }

    private static void action(HttpExchange ex) throws IOException {
        try {
            JsonObject body = readJson(ex);
            String action = body.has("action") ? body.get("action").getAsString() : "";
            switch (action) {
                case "backupNow" -> {
                    if (GDriveBackupMod.SERVER == null) throw new IllegalStateException("No active Minecraft world/server is available yet.");
                    if (!BackupService.start(GDriveBackupMod.SERVER, true, null, "Dashboard Admin")) throw new IllegalStateException("Backup could not start. Check the console for the exact reason.");
                    sendJson(ex, 200, ok("Backup job started."));
                }
                case "connect" -> {
                    String url = GoogleDriveOAuth.begin(GDriveBackupMod.CONFIG, "Dashboard Admin");
                    if (url.startsWith("ERROR: ")) throw new IllegalStateException(url.substring(7));
                    JsonObject out = ok("Google authorization opened in a new tab.");
                    out.addProperty("authUrl", url);
                    sendJson(ex, 200, out);
                }
                case "disconnect" -> {
                    GDriveBackupMod.CONFIG.refreshToken = "";
                    GDriveBackupMod.CONFIG.save();
                    GDriveBackupMod.resetSchedule();
                    AuditDatabase.log("WARN", "Dashboard Admin", "GOOGLE_DISCONNECTED", "Google Drive refresh token removed from this installation.");
                    sendJson(ex, 200, ok("Google Drive disconnected."));
                }
                case "enable" -> {
                    GDriveBackupMod.CONFIG.enabled = true; GDriveBackupMod.CONFIG.save(); GDriveBackupMod.resetSchedule();
                    AuditDatabase.log("INFO", "Dashboard Admin", "BACKUP_SCHEDULER_ENABLED", "Automatic backups enabled from dashboard.");
                    sendJson(ex, 200, ok("Automatic backups enabled."));
                }
                case "disable" -> {
                    GDriveBackupMod.CONFIG.enabled = false; GDriveBackupMod.CONFIG.save(); GDriveBackupMod.resetSchedule();
                    AuditDatabase.log("WARN", "Dashboard Admin", "BACKUP_SCHEDULER_DISABLED", "Automatic backups disabled from dashboard.");
                    sendJson(ex, 200, ok("Automatic backups disabled."));
                }
                default -> sendJson(ex, 400, error("Unknown action."));
            }
        } catch (Exception e) {
            AuditDatabase.log("ERROR", "Dashboard Admin", "DASHBOARD_ACTION_FAILED", safeMessage(e));
            sendJson(ex, 400, error(safeMessage(e)));
        }
    }

    private static void command(HttpExchange ex) throws IOException {
        try {
            JsonObject body = readJson(ex);
            String input = body.has("command") ? body.get("command").getAsString().trim() : "";
            if (input.startsWith("/")) input = input.substring(1).trim();
            if (input.isBlank() || input.length() > 256 || input.contains("\n") || input.contains("\r")
                || input.contains(";") || input.contains("|") || input.contains("&") || input.chars().anyMatch(Character::isISOControl)
                || !input.matches("gdrivebackup(?:\\s+.+)?")) {
                sendJson(ex, 400, error("Only a single /gdrivebackup command is allowed here (for example: /gdrivebackup status)."));
                return;
            }
            var server = GDriveBackupMod.SERVER;
            if (server == null) {
                sendJson(ex, 409, error("Minecraft world/server is not running. Start a world before running commands."));
                return;
            }
            final String command = input;
            AuditDatabase.log("INFO", "Dashboard Admin", "DASHBOARD_COMMAND_SUBMITTED", "Dashboard submitted /" + command + ".");
            server.execute(() -> {
                try {
                    int result = server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
                    AuditDatabase.log(result > 0 ? "INFO" : "WARN", "Dashboard Admin", "DASHBOARD_COMMAND_RESULT",
                        "Command /" + command + " finished with result " + result + ".");
                } catch (Exception e) {
                    AuditDatabase.log("ERROR", "Dashboard Admin", "DASHBOARD_COMMAND_FAILED", "Command /" + command + " failed: " + safeMessage(e));
                }
            });
            sendJson(ex, 200, ok("Command sent to the Minecraft server: /" + command));
        } catch (Exception e) {
            AuditDatabase.log("ERROR", "Dashboard Admin", "DASHBOARD_COMMAND_REQUEST_FAILED", safeMessage(e));
            sendJson(ex, 400, error("Could not submit the Minecraft command."));
        }
    }

    private static JsonObject readJson(HttpExchange ex) throws IOException {
        byte[] data = ex.getRequestBody().readNBytes(65537);
        if (data.length > 65536) throw new IOException("Request body is too large.");
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void sendJson(HttpExchange ex, int status, JsonObject object) throws IOException {
        sendText(ex, status, object.toString(), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static JsonObject ok(String message) { JsonObject j = new JsonObject(); j.addProperty("ok", true); j.addProperty("message", message); return j; }
    private static JsonObject error(String message) { JsonObject j = new JsonObject(); j.addProperty("ok", false); j.addProperty("message", message); return j; }
    private static boolean isHttps(HttpExchange ex) { String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto"); return "https".equalsIgnoreCase(proto == null ? "" : proto.split(",", 2)[0].trim()); }
    private static boolean configured(String s) { return s != null && !s.isBlank() && !s.startsWith("YOUR_") && !s.startsWith("PASTE_"); }
    private static String maskClientId(String id) { return id.length() < 14 ? "configured" : id.substring(0, 8) + "…" + id.substring(id.length() - 8); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
