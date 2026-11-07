package dev.xorvila.gdrivebackup;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class GoogleDriveClient {
    private static final String API = "https://www.googleapis.com/drive/v3";
    private final BackupConfig config;
    private String accessToken;
    private long tokenExpiry;

    GoogleDriveClient(BackupConfig config) { this.config = config; }

    private synchronized String token() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiry - 60_000) return accessToken;
        String body = GoogleDriveOAuth.postForm("https://oauth2.googleapis.com/token", Map.of(
            "client_id", config.clientId, "client_secret", config.clientSecret,
            "refresh_token", config.refreshToken, "grant_type", "refresh_token"));
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("access_token")) throw new IOException("Google token refresh response did not contain an access token.");
        accessToken = json.get("access_token").getAsString();
        tokenExpiry = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
        return accessToken;
    }

    private Response request(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(30000); c.setReadTimeout(180000);
        c.setRequestProperty("Authorization", "Bearer " + token());
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String response = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        String location = c.getHeaderField("Location");
        c.disconnect();
        return new Response(status, response, location);
    }

    private JsonObject api(String method, String url, String body) throws Exception {
        Response r = request(method, url, body);
        if (r.status < 200 || r.status >= 300) throw new IOException(explainDriveError(r.status, r.body));
        return r.body.isBlank() ? new JsonObject() : JsonParser.parseString(r.body).getAsJsonObject();
    }

    String ensureFolder() throws Exception {
        String escaped = config.driveFolder.replace("\\", "\\\\").replace("'", "\\'");
        String q = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='" + escaped + "' and 'root' in parents";
        String url = API + "/files?q=" + enc(q) + "&fields=files(id,name)&pageSize=100";
        JsonArray files = api("GET", url, null).getAsJsonArray("files");
        if (files != null && !files.isEmpty()) return files.get(0).getAsJsonObject().get("id").getAsString();
        JsonObject meta = new JsonObject(); meta.addProperty("name", config.driveFolder); meta.addProperty("mimeType", "application/vnd.google-apps.folder");
        return api("POST", API + "/files?fields=id", meta.toString()).get("id").getAsString();
    }

    void upload(Path file, String folderId, String name) throws Exception {
        long size = Files.size(file);
        JsonObject meta = new JsonObject(); meta.addProperty("name", name); JsonArray parents = new JsonArray(); parents.add(folderId); meta.add("parents", parents);
        byte[] metadata = meta.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection init = (HttpURLConnection) URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size").toURL().openConnection();
        init.setRequestMethod("POST"); init.setConnectTimeout(30000); init.setReadTimeout(30000); init.setDoOutput(true);
        init.setRequestProperty("Authorization", "Bearer " + token());
        init.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        init.setRequestProperty("X-Upload-Content-Type", "application/zip");
        init.setRequestProperty("X-Upload-Content-Length", Long.toString(size));
        init.setFixedLengthStreamingMode(metadata.length);
        try (OutputStream out = init.getOutputStream()) { out.write(metadata); }
        int initStatus = init.getResponseCode();
        String session = init.getHeaderField("Location");
        if (initStatus != 200 || session == null) {
            InputStream err = init.getErrorStream(); String detail = err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8);
            init.disconnect(); throw new IOException(explainDriveError(initStatus, detail));
        }
        init.disconnect();
        final int chunkSize = 8 * 1024 * 1024;
        try (InputStream in = Files.newInputStream(file)) {
            long offset = 0;
            while (offset < size) {
                int wanted = (int)Math.min(chunkSize, size - offset);
                byte[] bytes = in.readNBytes(wanted);
                if (bytes.length != wanted) throw new IOException("Backup archive changed during upload.");
                long end = offset + bytes.length - 1;
                HttpURLConnection put = (HttpURLConnection) URI.create(session).toURL().openConnection();
                put.setRequestMethod("PUT"); put.setConnectTimeout(30000); put.setReadTimeout(600000); put.setDoOutput(true);
                put.setRequestProperty("Content-Length", Integer.toString(bytes.length));
                put.setRequestProperty("Content-Range", "bytes " + offset + "-" + end + "/" + size);
                put.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = put.getOutputStream()) { out.write(bytes); }
                int status = put.getResponseCode();
                InputStream response = status >= 400 ? put.getErrorStream() : put.getInputStream();
                String detail = response == null ? "" : new String(response.readAllBytes(), StandardCharsets.UTF_8);
                put.disconnect();
                if (status != 308 && (status < 200 || status >= 300)) throw new IOException("Drive upload failed at byte " + offset + ": " + explainDriveError(status, detail));
                offset += bytes.length;
            }
        }
    }

    List<DriveFile> listBackups(String folderId, String backupPrefix) throws Exception {
        List<DriveFile> out = new ArrayList<>(); String page = null;
        do {
            String q = "'" + folderId + "' in parents and trashed=false and name contains '" + backupPrefix.replace("'", "\\'") + "'";
            String url = API + "/files?q=" + enc(q) + "&fields=nextPageToken,files(id,name,createdTime,size)&pageSize=1000&orderBy=createdTime" + (page == null ? "" : "&pageToken=" + enc(page));
            JsonObject json = api("GET", url, null);
            JsonArray files = json.getAsJsonArray("files");
            if (files != null) for (var el : files) { JsonObject f = el.getAsJsonObject(); out.add(new DriveFile(f.get("id").getAsString(), f.get("name").getAsString(), f.has("createdTime") ? f.get("createdTime").getAsString() : "")); }
            page = json.has("nextPageToken") ? json.get("nextPageToken").getAsString() : null;
        } while (page != null);
        out.sort((a,b) -> a.created.compareTo(b.created));
        return out;
    }

    void delete(String id) throws Exception {
        Response r = request("DELETE", API + "/files/" + id, null);
        if (r.status < 200 || r.status >= 300) throw new IOException("Could not delete old Drive backup: " + explainDriveError(r.status, r.body));
    }

    private static String explainDriveError(int code, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("error") && json.get("error").isJsonObject()) {
                JsonObject error = json.getAsJsonObject("error");
                String message = error.has("message") ? error.get("message").getAsString() : "";
                String status = error.has("status") ? error.get("status").getAsString() : "";
                return "Google Drive API HTTP " + code + (status.isBlank() ? "" : " (" + status + ")") + (message.isBlank() ? "." : ": " + message);
            }
        } catch (Exception ignored) {}
        return "Google Drive API HTTP " + code + (body == null || body.isBlank() ? "." : ": " + body);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private record Response(int status, String body, String location) {}
    record DriveFile(String id, String name, String created) {}
}
