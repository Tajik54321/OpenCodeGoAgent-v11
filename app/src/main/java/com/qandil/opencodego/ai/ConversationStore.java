package com.qandil.opencodego.ai;

import android.content.Context;
import com.qandil.opencodego.project.ProjectManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Lightweight persistent chat history per project. */
public final class ConversationStore {
    private final File root;

    public ConversationStore(Context context) {
        root = new File(context.getFilesDir(), "conversations");
        root.mkdirs();
    }

    public synchronized void append(String projectId, String role, String content, String providerId) {
        try {
            JSONObject item = new JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("role", role)
                    .put("content", content == null ? "" : content)
                    .put("providerId", providerId == null ? "" : providerId);
            try (FileWriter writer = new FileWriter(file(projectId), true)) {
                writer.write(item.toString());
                writer.write('\n');
            }
        } catch (Exception ignored) {}
    }

    public synchronized List<JSONObject> tail(String projectId, int limit) {
        List<JSONObject> result = new ArrayList<>();
        try {
            String raw = ProjectManager.read(file(projectId));
            String[] lines = raw.split("\\r?\\n");
            int start = Math.max(0, lines.length - Math.max(1, limit));
            for (int i = start; i < lines.length; i++) {
                if (!lines[i].trim().isEmpty()) result.add(new JSONObject(lines[i]));
            }
        } catch (Exception ignored) {}
        return result;
    }

    public synchronized void clear(String projectId) {
        File file = file(projectId);
        if (file.exists()) file.delete();
    }

    private File file(String projectId) {
        String safe = projectId == null ? "global" : projectId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(root, safe + ".jsonl");
    }
}
