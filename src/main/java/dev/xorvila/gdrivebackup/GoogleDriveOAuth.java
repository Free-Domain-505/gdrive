package dev.xorvila.gdrivebackup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** OAuth callback is served by the shared HTTPS-tunnel web server, avoiding a second port listener. */
final class GoogleDriveOAuth {
    private static volatile String pendingState;
    private static volatile String pendingVerifier;
    private static volatile String pendingActor;
    private static volatile String pendingRedirect;
    private static volatile long pendingExpiry;

    private GoogleDriveOAuth() {}

    static synchronized String begin(BackupConfig config, String actor) {
        String user = actor == null || actor.isBlank() ? "unknown" : actor;
        if (!configured(config.clientId) || !configured(config.clientSecret)) {
            String reason = "Google OAuth Web client ID/secret are missing. The mod owner must configure them once in config/gdrivebackup.json.";
            AuditDatabase.log("ERROR", user, "OAUTH_START_FAILED", reason);
            return "ERROR: " + reason;
        }
        if (pendingState != null && System.currentTimeMillis() < pendingExpiry) {
            String reason = "Another Google authorization is already pending. Finish it or wait for its 10-minute expiry.";
            AuditDatabase.log("WARN", user, "OAUTH_START_REJECTED", reason);
            return "ERROR: " + reason;
        }
        try {
            String redirect = config.redirectUri();
            byte[] stateBytes = new byte[32];
            byte[] verifierBytes = new byte[48];
            SecureRandom random = new SecureRandom();
            random.nextBytes(stateBytes);
            random.nextBytes(verifierBytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

            CloudflaredTunnel.startIfConfigured(config);
            pendingState = state;
            pendingVerifier = verifier;
            pendingActor = user;
            pendingRedirect = redirect;
            pendingExpiry = System.currentTimeMillis() + 10 * 60 * 1000L;

            String url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(Map.of(
                "client_id", config.clientId,
                "redirect_uri", redirect,
                "response_type", "code",
                "scope", "https://www.googleapis.com/auth/drive.file",
                "access_type", "offline",
                "prompt", "consent",
                "include_granted_scopes", "true",
                "state", state,
                "code_challenge", challenge,
                "code_challenge_method", "S256"
            ));
            AuditDatabase.log("INFO", user, "OAUTH_STARTED", "Google Drive authorization started. Callback=" + redirect);
            GDriveBackupMod.LOGGER.info("Google Drive authorization started by {} using public callback {}", user, redirect);
            return url;
        } catch (Exception e) {
            clearPending();
            String reason = safeMessage(e);
            AuditDatabase.log("ERROR", user, "OAUTH_START_FAILED", reason);
            GDriveBackupMod.LOGGER.error("Google Drive OAuth could not start for {}: {}", user, reason);
            return "ERROR: " + reason;
        }
    }

    static void handleCallback(HttpExchange exchange) throws IOException {
        String actor = pendingActor == null ? "unmatched-callback" : pendingActor;
        int status = 400;
        String message;
        boolean consume = false;
        try {
            if (!exchange.getRequestURI().getPath().equals(GDriveBackupMod.CONFIG.callbackPath)) {
                message = "This is not the configured Google Drive callback path.";
                AuditDatabase.log("WARN", actor, "OAUTH_CALLBACK_PATH_REJECTED", message);
                respond(exchange, 404, message);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                status = 405;
                message = "This endpoint accepts the Google authorization redirect only.";
                AuditDatabase.log("WARN", actor, "OAUTH_CALLBACK_REJECTED", "Callback method was not GET.");
                respond(exchange, status, message);
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String gotState = query.get("state");
            String expected = pendingState;
            if (expected == null || pendingExpiry < System.currentTimeMillis()) {
                message = "There is no active Google authorization request, or it has expired. Return to the Minecraft dashboard and press Connect again.";
                AuditDatabase.log("WARN", actor, "OAUTH_CALLBACK_EXPIRED", message);
                respond(exchange, 400, message);
                return;
            }
            if (gotState == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), gotState.getBytes(StandardCharsets.UTF_8))) {
                message = "Google authorization state did not match. This callback was rejected for security. Start Connect again from the dashboard.";
                AuditDatabase.log("ERROR", actor, "OAUTH_STATE_MISMATCH", message);
                respond(exchange, 400, message);
                return;
            }
            consume = true;
            if (query.containsKey("error")) {
                String error = query.get("error");
                String description = query.getOrDefault("error_description", "No additional reason was returned by Google.");
                message = "Google did not authorize Drive access (" + error + "): " + description;
                AuditDatabase.log("ERROR", actor, "OAUTH_DENIED", message);
                GDriveBackupMod.LOGGER.warn("Google Drive authorization attempted by {} but was not completed: {}", actor, message);
                status = 403;
                respond(exchange, status, message);
                return;
            }
            String code = query.get("code");
            if (code == null || code.isBlank()) {
                message = "Google returned no authorization code. The account connection could not be completed.";
                AuditDatabase.log("ERROR", actor, "OAUTH_CODE_MISSING", message);
                respond(exchange, 400, message);
                return;
            }

            try {
                String body = postForm("https://oauth2.googleapis.com/token", Map.of(
                    "code", code,
                    "client_id", GDriveBackupMod.CONFIG.clientId,
                    "client_secret", GDriveBackupMod.CONFIG.clientSecret,
                    "redirect_uri", pendingRedirect,
                    "grant_type", "authorization_code",
                    "code_verifier", pendingVerifier
                ));
                JsonObject token = JsonParser.parseString(body).getAsJsonObject();
                if (!token.has("refresh_token") || token.get("refresh_token").isJsonNull()
                    || token.get("refresh_token").getAsString().isBlank()) {
                    throw new IOException("Google completed login but returned no refresh token. Reconnect and grant offline access; check the OAuth consent configuration.");
                }
                GDriveBackupMod.CONFIG.refreshToken = token.get("refresh_token").getAsString();
                GDriveBackupMod.CONFIG.save();
                GDriveBackupMod.resetSchedule();
                message = "Google Drive connected successfully. You can close this tab and return to Minecraft.";
                AuditDatabase.log("INFO", actor, "OAUTH_CONNECTED", "Google Drive account authorization succeeded; refresh token saved locally.");
                GDriveBackupMod.LOGGER.info("Google Drive account authorization succeeded for {}.", actor);
                status = 200;
            } catch (Exception tokenError) {
                message = explainGoogleError(tokenError);
                AuditDatabase.log("ERROR", actor, "OAUTH_TOKEN_EXCHANGE_FAILED", message);
                GDriveBackupMod.LOGGER.error("Google Drive authorization attempted by {} but failed during token exchange: {}", actor, message);
                status = 400;
            }
            respond(exchange, status, message);
        } catch (Exception e) {
            message = "OAuth callback processing failed: " + safeMessage(e);
            AuditDatabase.log("ERROR", actor, "OAUTH_CALLBACK_INTERNAL_ERROR", message);
            GDriveBackupMod.LOGGER.error("Google OAuth callback failed for {}: {}", actor, message);
            if (exchange.getResponseCode() == -1) respond(exchange, 500, message);
        } finally {
            if (consume) clearPending();
        }
    }

    private static String explainGoogleError(Exception e) {
        String detail = safeMessage(e);
        try {
            int idx = detail.indexOf('{');
            if (idx >= 0) {
                JsonObject json = JsonParser.parseString(detail.substring(idx)).getAsJsonObject();
                if (json.has("error_description") && !json.get("error_description").isJsonNull()) return "Google token exchange failed: " + json.get("error_description").getAsString();
                if (json.has("error") && json.get("error").isJsonObject() && json.getAsJsonObject("error").has("message")) return "Google token exchange failed: " + json.getAsJsonObject("error").get("message").getAsString();
                if (json.has("error") && json.get("error").isJsonPrimitive()) return "Google token exchange failed: " + json.get("error").getAsString();
            }
        } catch (Exception ignored) {}
        return "Google token exchange failed: " + detail;
    }

    private static synchronized void clearPending() {
        pendingState = null;
        pendingVerifier = null;
        pendingActor = null;
        pendingRedirect = null;
        pendingExpiry = 0;
    }

    static synchronized void stop() { clearPending(); }

    static String postForm(String url, Map<String, String> values) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");
        byte[] bytes = form(values).getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = c.getOutputStream()) { out.write(bytes); }
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        if (code < 200 || code >= 300) {
            String safeBody = body.replaceAll("(?i)(client_secret|refresh_token|access_token)\\\"?\\s*[:=]\\s*\\\"?[^\\\",} ]+", "$1=[REDACTED]");
            throw new IOException("Google OAuth token endpoint returned HTTP " + code + ": " + safeBody);
        }
        return body;
    }

    static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> { if (out.length() > 0) out.append('&'); out.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)); });
        return out.toString();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        String color = status >= 200 && status < 300 ? "#34d399" : "#fb7185";
        String safe = escapeHtml(message);
        String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>GDrive Backup</title>"
            + "<style>body{margin:0;background:#090b16;color:#eef0ff;font:16px system-ui;display:grid;min-height:100vh;place-items:center}.card{max-width:620px;margin:24px;padding:32px;border:1px solid #292d49;border-radius:24px;background:#12162a;box-shadow:0 20px 80px #0008}h1{color:" + color + "}p{line-height:1.7;color:#bec5dd}a{color:#a78bfa}</style></head>"
            + "<body><main class='card'><h1>" + (status >= 200 && status < 300 ? "Drive connected" : "Connection not completed") + "</h1><p>" + safe + "</p><p>You may close this tab and return to Minecraft.</p><a href='/dashboard'>Open backup dashboard</a></main></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static boolean configured(String value) { return value != null && !value.isBlank() && !value.startsWith("YOUR_") && !value.startsWith("PASTE_"); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
