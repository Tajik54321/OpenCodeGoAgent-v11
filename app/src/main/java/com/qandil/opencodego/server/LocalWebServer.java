package com.qandil.opencodego.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Static HTTP/1.1 server for project preview. */
public final class LocalWebServer {
    private static final long MAX_FILE = 512L * 1024L * 1024L;
    private final File root;
    private final int requestedPort;
    private final boolean lan;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running;
    private ServerSocket socket;
    private ExecutorService pool;
    private Thread acceptThread;
    private int actualPort;

    public LocalWebServer(File root, int port, boolean lan) {
        this.root = root;
        this.requestedPort = port;
        this.lan = lan;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        InetAddress address = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1");
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(address, requestedPort));
        actualPort = socket.getLocalPort();
        running = true;
        pool = Executors.newFixedThreadPool(8);
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = socket.accept();
                    pool.submit(() -> handle(client));
                } catch (IOException error) {
                    if (running) log("ERROR " + error.getMessage());
                }
            }
        }, "OpenCodeStaticServer-" + actualPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("START " + url());
    }

    public synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
        log("STOP");
    }

    public boolean isRunning() { return running; }
    public int port() { return actualPort; }
    public String url() { return "http://127.0.0.1:" + actualPort + "/"; }
    public List<String> logs() {
        synchronized (logs) { return new ArrayList<>(logs); }
    }

    private void handle(Socket client) {
        try (Socket connection = client) {
            connection.setSoTimeout(15_000);
            Request request = Request.read(connection.getInputStream());
            if (request == null) return;
            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                sendBytes(connection, 405, "text/plain; charset=utf-8",
                        "Method not allowed".getBytes(StandardCharsets.UTF_8), false, null);
                log("405 " + request.method + " " + request.path);
                return;
            }
            if ("/__opencode/status".equals(request.path)) {
                String body = "{\"running\":true,\"port\":" + actualPort + "}";
                sendBytes(connection, 200, "application/json; charset=utf-8",
                        body.getBytes(StandardCharsets.UTF_8), "HEAD".equals(request.method), null);
                return;
            }
            File file = safeFile(request.path);
            if (file == null || !file.exists()) {
                sendBytes(connection, 404, "text/html; charset=utf-8",
                        errorPage(404, "Файл не найден").getBytes(StandardCharsets.UTF_8),
                        "HEAD".equals(request.method), null);
                log("404 " + request.path);
                return;
            }
            if (file.isDirectory()) {
                File index = firstExisting(file, "index.html", "index.htm");
                if (index != null) file = index;
                else {
                    byte[] listing = directoryListing(file, request.path).getBytes(StandardCharsets.UTF_8);
                    sendBytes(connection, 200, "text/html; charset=utf-8", listing,
                            "HEAD".equals(request.method), null);
                    log("200 DIR " + request.path);
                    return;
                }
            }
            if (file.length() > MAX_FILE) {
                sendBytes(connection, 413, "text/plain; charset=utf-8",
                        "File is too large".getBytes(StandardCharsets.UTF_8),
                        "HEAD".equals(request.method), null);
                return;
            }
            sendFile(connection, request, file);
            log("200 " + request.path + " " + file.length());
        } catch (Exception error) {
            log("ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void sendFile(Socket connection, Request request, File file) throws IOException {
        long length = file.length();
        long start = 0L;
        long end = length == 0 ? 0 : length - 1;
        int status = 200;
        String range = request.headers.get("range");
        if (range != null && range.startsWith("bytes=") && length > 0) {
            String[] pair = range.substring(6).split("-", 2);
            try {
                if (!pair[0].isEmpty()) start = Long.parseLong(pair[0]);
                if (pair.length > 1 && !pair[1].isEmpty()) end = Long.parseLong(pair[1]);
                if (start < 0 || end < start || start >= length) throw new NumberFormatException();
                end = Math.min(end, length - 1);
                status = 206;
            } catch (NumberFormatException error) {
                sendBytes(connection, 416, "text/plain; charset=utf-8", new byte[0], true,
                        Collections.singletonMap("Content-Range", "bytes */" + length));
                return;
            }
        }
        long contentLength = length == 0 ? 0 : end - start + 1;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Last-Modified", new Date(file.lastModified()).toString());
        if (status == 206) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
        OutputStream raw = new BufferedOutputStream(connection.getOutputStream());
        writeHeaders(raw, status, mime(file.getName()), contentLength, headers);
        if (!"HEAD".equals(request.method) && contentLength > 0) {
            try (FileInputStream input = new FileInputStream(file)) {
                long skipped = 0L;
                while (skipped < start) {
                    long count = input.skip(start - skipped);
                    if (count <= 0) break;
                    skipped += count;
                }
                byte[] buffer = new byte[32 * 1024];
                long remaining = contentLength;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) break;
                    raw.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
        raw.flush();
    }

    private void sendBytes(
            Socket connection,
            int status,
            String type,
            byte[] body,
            boolean head,
            Map<String, String> extraHeaders) throws IOException {
        OutputStream output = new BufferedOutputStream(connection.getOutputStream());
        writeHeaders(output, status, type, body.length, extraHeaders);
        if (!head) output.write(body);
        output.flush();
    }

    private static void writeHeaders(
            OutputStream output,
            int status,
            String type,
            long contentLength,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(contentLength).append("\r\n")
                .append("Connection: close\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Cache-Control: no-cache\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                headers.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private File safeFile(String path) throws IOException {
        String decoded = URLDecoder.decode(path == null ? "/" : path, "UTF-8");
        int query = decoded.indexOf('?');
        if (query >= 0) decoded = decoded.substring(0, query);
        while (decoded.startsWith("/")) decoded = decoded.substring(1);
        File file = new File(root, decoded);
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) return null;
        return file;
    }

    private static File firstExisting(File directory, String... names) {
        for (String name : names) {
            File file = new File(directory, name);
            if (file.isFile()) return file;
        }
        return null;
    }

    private String directoryListing(File directory, String requestPath) {
        File[] files = directory.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        String base = requestPath == null || requestPath.isEmpty() ? "/" : requestPath;
        if (!base.endsWith("/")) base += "/";
        StringBuilder result = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
                .append("<style>body{font:15px system-ui;background:#08110f;color:#f0f8f4;padding:24px}a{color:#3ddc84;text-decoration:none}li{padding:8px}</style>")
                .append("<h1>").append(escape(base)).append("</h1><ul>");
        if (!"/".equals(base)) result.append("<li><a href=\"../\">../</a></li>");
        for (File file : files) {
            String name = file.getName() + (file.isDirectory() ? "/" : "");
            result.append("<li><a href=\"").append(urlEncode(name)).append("\">")
                    .append(escape(name)).append("</a></li>");
        }
        return result.append("</ul>").toString();
    }

    private static String errorPage(int status, String message) {
        return "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<style>body{font-family:system-ui;background:#08110f;color:white;display:grid;place-content:center;min-height:90vh}</style>"
                + "<main><b>" + status + "</b><h1>" + escape(message) + "</h1></main>";
    }

    private static String urlEncode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception ignored) { return value; }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            default: return "Error";
        }
    }

    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json; charset=utf-8";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private void log(String message) {
        logs.add(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message);
        while (logs.size() > 1_000) logs.remove(0);
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;

        Request(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }

        static Request read(InputStream rawInput) throws IOException {
            BufferedInputStream input = new BufferedInputStream(rawInput);
            String requestLine = readLine(input, 16 * 1024);
            if (requestLine == null || requestLine.isEmpty()) return null;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) return null;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(input, 64 * 1024)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], headers);
        }

        private static String readLine(InputStream input, int max) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0) {
                if (value == '\n') break;
                if (value != '\r') output.write(value);
                if (output.size() > max) throw new IOException("HTTP header is too large");
            }
            if (value < 0 && output.size() == 0) return null;
            return output.toString("UTF-8");
        }
    }
}
