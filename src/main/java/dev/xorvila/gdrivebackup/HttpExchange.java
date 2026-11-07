package dev.xorvila.gdrivebackup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small HTTP exchange abstraction implemented only with java.net sockets for desktop and Android runtimes. */
final class HttpExchange {
    private final String method;
    private final URI uri;
    private final HttpHeaders requestHeaders;
    private final HttpHeaders responseHeaders = new HttpHeaders();
    private final InputStream requestBody;
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private volatile int responseCode = -1;
    private volatile long responseLength = -1;

    HttpExchange(String method, URI uri, HttpHeaders headers, byte[] body) {
        this.method = method;
        this.uri = uri;
        this.requestHeaders = headers;
        this.requestBody = new ByteArrayInputStream(body);
    }

    String getRequestMethod() { return method; }
    URI getRequestURI() { return uri; }
    HttpHeaders getRequestHeaders() { return requestHeaders; }
    HttpHeaders getResponseHeaders() { return responseHeaders; }
    InputStream getRequestBody() { return requestBody; }
    OutputStream getResponseBody() { return responseBody; }
    int getResponseCode() { return responseCode; }
    long getResponseLength() { return responseLength; }
    byte[] responseBytes() { return responseBody.toByteArray(); }
    void sendResponseHeaders(int status, long length) { this.responseCode = status; this.responseLength = length; }
    void close() { /* The owning socket writes and closes the response after the handler returns. */ }

    static final class HttpHeaders {
        private final Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        void set(String name, String value) { values.put(name, new ArrayList<>(List.of(value))); }
        void add(String name, String value) { values.computeIfAbsent(name, key -> new ArrayList<>()).add(value); }
        String getFirst(String name) {
            List<String> list = values.get(name);
            return list == null || list.isEmpty() ? null : list.get(0);
        }
        Iterable<Map.Entry<String, List<String>>> entries() { return values.entrySet(); }
    }
}
