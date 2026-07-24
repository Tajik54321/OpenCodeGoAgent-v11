package com.qandil.opencodego.audit;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Append-only JSONL audit trail for agent, server, terminal and database actions. */
public final class AuditLog {
    private static AuditLog instance;
    private final File root;

    private AuditLog(Context context) {
        root = new File(context.getFilesDir(), "audit");
        root.mkdirs();
    }

    public static synchronized AuditLog get(Context context) {
        if (instance == null) instance = new AuditLog(context.getApplicationContext());
        return instance;
    }

    public synchronized void append(
            String projectId,
            String category,
            String action,
            String summary,
            boolean success) {
        try {
            JSONObject event = new JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("timeText", new Date().toString())
                    .put("projectId", projectId == null ? "global" : projectId)
                    .put("category", category)
                    .put("action", action)
                    .put("summary", truncate(redact(summary), 8_000))
                    .put("success", success);
            try (FileWriter writer = new FileWriter(file(projectId), true)) {
                writer.write(event.toString());
                writer.write('\n');
            }
        } catch (Exception ignored) {
            // Audit must never crash the primary operation.
        }
    }

    public synchronized List<JSONObject> tail(String projectId, int limit) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        File target = file(projectId);
        if (!target.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(target))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= Math.max(1, limit)) lines.removeFirst();
                lines.addLast(line);
            }
        } catch (Exception ignored) {
        }
        List<JSONObject> result = new ArrayList<>();
        for (String line : lines) {
            try { result.add(new JSONObject(line)); } catch (Exception ignored) {}
        }
        return result;
    }

    public synchronized void clear(String projectId) {
        File target = file(projectId);
        if (target.exists()) target.delete();
    }

    private File file(String projectId) {
        String safe = projectId == null ? "global" : projectId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(root, safe + ".jsonl");
    }

    private static String redact(String value) {
        if (value == null) return "";
        String result = value;
        result = result.replaceAll("(?i)(authorization\s*[:=]\s*bearer\s+)[^\s,;\"]+", "$1***");
        result = result.replaceAll("(?i)((?:api[_-]?key|password|passwd|secret|access[_-]?token|refresh[_-]?token|db_password)\s*[=:]\s*)[^\s,;]+", "$1***");
        result = result.replaceAll("(?i)(\"(?:apiKey|password|secret|token|authorization|content)\"\s*:\s*\")[^\"]{8,}(\")", "$1***$2");
        return result;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
