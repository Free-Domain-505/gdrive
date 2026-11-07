package dev.xorvila.gdrivebackup;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal one-request-per-connection HTTP/1.1 server; no jdk.httpserver module or native libraries required. */
final class LocalHttpServer {
    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws Exception; }
    private record Context(String path, Handler handler) {}
    private static final int MAX_HEADER_BYTES = 32768;
    private static final int MAX_BODY_BYTES = 65536;
    private static final List<Context> NO_CONTEXTS = List.of();

    private final String host;
    private final int port;
    private final List<Context> contexts = new CopyOnWriteArrayList<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gdrive-http-worker"); t.setDaemon(true); return t;
    });
    private volatile ServerSocket socket;
    private volatile boolean running;

    LocalHttpServer(String host, int port) { this.host = host; this.port = port; }

    void createContext(String path, Handler handler) { contexts.add(new Context(path, handler)); }

    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName(host), port), 64);
        socket = listener;
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "gdrive-http-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(20000);
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) GDriveBackupMod.LOGGER.warn("Dashboard HTTP accept failed: {}", safeMessage(e));
            }
        }
    }

    private void handle(Socket client) {
        try (client; BufferedInputStream in = new BufferedInputStream(client.getInputStream())) {
            String requestLine = readLine(in, 8192);
            if (requestLine == null || requestLine.isBlank()) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) { writeRawError(client.getOutputStream(), 400, "Malformed request."); return; }
            String method = parts[0].toUpperCase(java.util.Locale.ROOT);
            URI uri;
            try { uri = URI.create(parts[1]); }
            catch (Exception e) { writeRawError(client.getOutputStream(), 400, "Malformed request URL."); return; }

            HttpExchange.HttpHeaders headers = new HttpExchange.HttpHeaders();
            int totalHeaderBytes = requestLine.length();
            String line;
            while ((line = readLine(in, 8192)) != null && !line.isEmpty()) {
                totalHeaderBytes += line.length();
                if (totalHeaderBytes > MAX_HEADER_BYTES) { writeRawError(client.getOutputStream(), 431, "Request headers too large."); return; }
                int colon = line.indexOf(':');
                if (colon > 0) headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            String transfer = headers.getFirst("Transfer-Encoding");
            if (transfer != null && transfer.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
                writeRawError(client.getOutputStream(), 411, "Chunked request bodies are not supported."); return;
            }
            int contentLength = 0;
            String lengthHeader = headers.getFirst("Content-Length");
            if (lengthHeader != null) {
                try { contentLength = Integer.parseInt(lengthHeader); }
                catch (NumberFormatException e) { writeRawError(client.getOutputStream(), 400, "Invalid Content-Length."); return; }
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) { writeRawError(client.getOutputStream(), 413, "Request body is too large."); return; }
            byte[] body = in.readNBytes(contentLength);
            if (body.length != contentLength) { writeRawError(client.getOutputStream(), 400, "Incomplete request body."); return; }

            HttpExchange exchange = new HttpExchange(method, uri, headers, body);
            Handler handler = contexts.stream().sorted(Comparator.comparingInt((Context c) -> c.path.length()).reversed())
                .filter(c -> uri.getPath().startsWith(c.path)).map(Context::handler).findFirst().orElse(null);
            if (handler == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                try { handler.handle(exchange); }
                catch (Exception e) {
                    GDriveBackupMod.LOGGER.error("Dashboard request failed for {} {}: {}", method, uri.getPath(), safeMessage(e));
                    AuditDatabase.log("ERROR", "Web Server", "HTTP_HANDLER_FAILED", method + " " + uri.getPath() + ": " + safeMessage(e));
                    if (exchange.getResponseCode() == -1) {
                        byte[] message = "Internal server error.".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                        exchange.getResponseBody().write(message);
                        exchange.sendResponseHeaders(500, message.length);
                    }
                }
            }
            writeResponse(client.getOutputStream(), exchange);
        } catch (Exception e) {
            GDriveBackupMod.LOGGER.debug("Dashboard client connection ended: {}", safeMessage(e));
        }
    }

    private static String readLine(BufferedInputStream in, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > max) throw new IOException("HTTP line too long");
        }
        if (value == -1 && bytes.size() == 0) return null;
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static void writeResponse(OutputStream out, HttpExchange exchange) throws IOException {
        int status = exchange.getResponseCode() < 0 ? 500 : exchange.getResponseCode();
        byte[] body = exchange.responseBytes();
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        HttpExchange.HttpHeaders headers = exchange.getResponseHeaders();
        if (headers.getFirst("Content-Type") == null) headers.set("Content-Type", "text/plain; charset=utf-8");
        if (headers.getFirst("Cache-Control") == null) headers.set("Cache-Control", "no-store");
        if (headers.getFirst("X-Content-Type-Options") == null) headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Length", Integer.toString(body.length));
        headers.set("Connection", "close");
        for (Map.Entry<String, List<String>> entry : headers.entries()) {
            for (String value : entry.getValue()) head.append(entry.getKey()).append(": ").append(value).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void writeRawError(OutputStream out, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK"; case 302 -> "Found"; case 400 -> "Bad Request"; case 401 -> "Unauthorized";
            case 403 -> "Forbidden"; case 404 -> "Not Found"; case 405 -> "Method Not Allowed";
            case 411 -> "Length Required"; case 413 -> "Payload Too Large"; case 431 -> "Request Header Fields Too Large";
            default -> "Internal Server Error";
        };
    }

    synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        workers.shutdownNow();
    }
    boolean isRunning() { return running; }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
