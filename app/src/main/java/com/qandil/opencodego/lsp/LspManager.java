package com.qandil.opencodego.lsp;

import android.content.Context;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.runtime.RuntimeManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal but byte-correct Language Server Protocol stdio client. */
public final class LspManager {
    public static final class Session {
        public final String language;
        public final Process process;
        private final BufferedInputStream input;
        private final OutputStream output;
        private final BlockingQueue<JSONObject> incoming = new LinkedBlockingQueue<>();
        private final AtomicLong ids = new AtomicLong(1);
        private volatile Exception readerFailure;

        Session(String language, Process process) {
            this.language = language;
            this.process = process;
            this.input = new BufferedInputStream(process.getInputStream());
            this.output = process.getOutputStream();
            Thread reader = new Thread(this::readerLoop, "lsp-reader-" + language);
            reader.setDaemon(true);
            reader.start();
            Thread errors = new Thread(() -> drain(process.getErrorStream()), "lsp-stderr-" + language);
            errors.setDaemon(true);
            errors.start();
        }

        public synchronized JSONObject request(String method, JSONObject params, long timeoutMillis) throws Exception {
            long id = ids.getAndIncrement();
            send(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
                    .put("params", params == null ? new JSONObject() : params));
            long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMillis);
            while (System.currentTimeMillis() < deadline) {
                if (readerFailure != null) throw new IllegalStateException("LSP reader failed", readerFailure);
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                JSONObject message = incoming.poll(Math.min(remaining, 250L), TimeUnit.MILLISECONDS);
                if (message == null) continue;
                Object responseId = message.opt("id");
                if (responseId != null && String.valueOf(responseId).equals(String.valueOf(id))) {
                    if (message.has("error")) throw new IllegalStateException("LSP error: " + message.opt("error"));
                    return message;
                }
                // Notifications and unrelated responses are intentionally ignored; only one request is active per session.
            }
            throw new IllegalStateException("LSP request timed out: " + method);
        }

        public synchronized void notify(String method, JSONObject params) throws Exception {
            send(new JSONObject().put("jsonrpc", "2.0").put("method", method)
                    .put("params", params == null ? new JSONObject() : params));
        }

        public synchronized void close() {
            try { request("shutdown", new JSONObject(), 3_000); } catch (Exception ignored) {}
            try { notify("exit", new JSONObject()); } catch (Exception ignored) {}
            process.destroy();
        }

        private synchronized void send(JSONObject message) throws Exception {
            byte[] payload = message.toString().getBytes(StandardCharsets.UTF_8);
            byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            output.write(header);
            output.write(payload);
            output.flush();
        }

        private void readerLoop() {
            try {
                while (process.isAlive()) incoming.put(readMessage());
            } catch (Exception error) {
                readerFailure = error;
            }
        }

        private JSONObject readMessage() throws Exception {
            int contentLength = -1;
            while (true) {
                String line = readAsciiLine(input);
                if (line == null) throw new IllegalStateException("LSP process closed output");
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (contentLength < 0 || contentLength > 16 * 1024 * 1024) {
                throw new IllegalStateException("Invalid LSP Content-Length: " + contentLength);
            }
            byte[] payload = new byte[contentLength];
            int offset = 0;
            while (offset < payload.length) {
                int read = input.read(payload, offset, payload.length - offset);
                if (read < 0) throw new IllegalStateException("LSP process closed output");
                offset += read;
            }
            return new JSONObject(new String(payload, StandardCharsets.UTF_8));
        }

        private static String readAsciiLine(InputStream input) throws Exception {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) return line.size() == 0 ? null : line.toString("US-ASCII");
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = line.toByteArray();
                    int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                    return new String(bytes, 0, length, StandardCharsets.US_ASCII);
                }
                line.write(current);
                previous = current;
                if (line.size() > 32 * 1024) throw new IllegalStateException("LSP header line too large");
            }
        }

        private static void drain(InputStream stream) {
            try {
                byte[] buffer = new byte[4096];
                while (stream.read(buffer) >= 0) { /* prevent child stderr back-pressure */ }
            } catch (Exception ignored) {}
        }
    }

    private final RuntimeManager runtimes;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public LspManager(Context context) {
        runtimes = RuntimeManager.get(context.getApplicationContext());
    }

    public synchronized Session start(Project project, String language) throws Exception {
        String key = project.id + ":" + language;
        Session old = sessions.remove(key);
        if (old != null) old.close();
        List<String> command = command(language);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.root);
        builder.redirectErrorStream(false);
        builder.environment().putAll(runtimes.environment(project.root));
        Process process = builder.start();
        Session session = new Session(language, process);
        JSONObject initialize = new JSONObject()
                .put("processId", JSONObject.NULL)
                .put("rootUri", project.root.toURI().toString())
                .put("capabilities", new JSONObject())
                .put("workspaceFolders", new JSONArray().put(new JSONObject()
                        .put("uri", project.root.toURI().toString()).put("name", project.name)));
        session.request("initialize", initialize, 15_000);
        session.notify("initialized", new JSONObject());
        sessions.put(key, session);
        return session;
    }

    public synchronized Session get(Project project, String language) {
        return sessions.get(project.id + ":" + language);
    }

    public synchronized void stop(Project project, String language) {
        Session session = sessions.remove(project.id + ":" + language);
        if (session != null) session.close();
    }

    public synchronized void stopAll() {
        for (Session session : new ArrayList<>(sessions.values())) session.close();
        sessions.clear();
    }

    private List<String> command(String language) {
        String normalized = language == null ? "" : language.toLowerCase();
        File executable;
        if ("typescript".equals(normalized) || "javascript".equals(normalized)) {
            executable = runtimes.executableAny("typescript-language-server", "typescript-language-server");
            if (executable == null) throw new IllegalStateException("typescript-language-server runtime is missing");
            return java.util.Arrays.asList(executable.getAbsolutePath(), "--stdio");
        }
        if ("python".equals(normalized)) {
            executable = runtimes.executableAny("pyright", "pyright-langserver");
            if (executable == null) throw new IllegalStateException("pyright runtime is missing");
            return java.util.Arrays.asList(executable.getAbsolutePath(), "--stdio");
        }
        if ("php".equals(normalized)) {
            executable = runtimes.executableAny("php-language-server", "intelephense");
            if (executable == null) throw new IllegalStateException("PHP language server runtime is missing");
            return java.util.Arrays.asList(executable.getAbsolutePath(), "--stdio");
        }
        if ("java".equals(normalized)) {
            executable = runtimes.executableAny("jdtls", "jdtls");
            if (executable == null) throw new IllegalStateException("JDT LS runtime is missing");
            return java.util.Collections.singletonList(executable.getAbsolutePath());
        }
        throw new IllegalArgumentException("Unsupported language server: " + language);
    }
}
