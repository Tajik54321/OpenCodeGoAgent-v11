package com.qandil.opencodego.integration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free HTTP/JSON client with strict timeouts and bounded responses. */
public final class HttpJson {
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    public static final class Response {
        public final int code;
        public final String contentType;
        public final String body;
        public final Map<String, String> headers;

        Response(int code, String contentType, String body, Map<String, String> headers) {
            this.code = code;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? "" : body;
            this.headers = headers;
        }

        public boolean ok() { return code >= 200 && code < 300; }
        public JSONObject object() { return new JSONObject(body); }
        public JSONArray array() { return new JSONArray(body); }
        public Object json() {
            String value = body.trim();
            if (value.startsWith("{")) return new JSONObject(value);
            if (value.startsWith("[")) return new JSONArray(value);
            return value;
        }
    }

    private HttpJson() {}

    public static Response request(
            String method,
            String url,
            String body,
            Map<String, String> headers,
            String username,
            String password) throws Exception {
        URL parsed = new URL(url);
        String protocol = parsed.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("Unsupported URL protocol: " + protocol);
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method == null ? "GET" : method.toUpperCase());
        connection.setRequestProperty("Accept", "application/json, text/event-stream, text/plain, */*");
        connection.setRequestProperty("User-Agent", "OpenCode-Go-Agent/11.0 Android");
        if (username != null && !username.isEmpty()) {
            String credentials = username + ":" + (password == null ? "" : password);
            connection.setRequestProperty("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            if (connection.getRequestProperty("Content-Type") == null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = input == null ? "" : readBounded(input, MAX_RESPONSE_BYTES);
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                responseHeaders.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        Response response = new Response(code, connection.getContentType(), responseBody, responseHeaders);
        if (!response.ok()) {
            throw new IllegalStateException("HTTP " + code + " from " + url + "\n" + truncate(responseBody, 4000));
        }
        return response;
    }

    public static Response get(String url, Map<String, String> headers, String username, String password) throws Exception {
        return request("GET", url, null, headers, username, password);
    }

    public static Response post(String url, JSONObject body, Map<String, String> headers, String username, String password) throws Exception {
        return request("POST", url, body == null ? "{}" : body.toString(), headers, username, password);
    }

    public static Response patch(String url, JSONObject body, Map<String, String> headers, String username, String password) throws Exception {
        return request("PATCH", url, body == null ? "{}" : body.toString(), headers, username, password);
    }

    public static String joinUrl(String base, String path) {
        String left = base == null ? "" : base.trim();
        while (left.endsWith("/")) left = left.substring(0, left.length() - 1);
        String right = path == null ? "" : path.trim();
        if (!right.startsWith("/")) right = "/" + right;
        return left + right;
    }

    private static String readBounded(InputStream input, int maxBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) throw new IllegalStateException("HTTP response exceeds " + maxBytes + " bytes");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "…";
    }
}
