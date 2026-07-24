package com.qandil.opencodego.history;

import android.content.Context;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** File checkpoints stored outside the project root so agent changes can be rolled back. */
public final class CheckpointManager {
    private static final int MAX_CHECKPOINTS = 200;
    private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    private final Context context;
    private final File root;
    private final ProjectManager projects;

    public CheckpointManager(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "checkpoints");
        root.mkdirs();
        projects = ProjectManager.get(context);
    }

    public synchronized JSONObject snapshot(Project project, File target, String action) throws Exception {
        String relative = relative(project, target);
        String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        File directory = new File(projectRoot(project), id);
        if (!directory.mkdirs()) throw new IllegalStateException("Cannot create checkpoint");
        boolean existed = target.exists();
        boolean wasDirectory = existed && target.isDirectory();
        File payload = new File(directory, "payload");
        if (existed) copy(target, payload);
        JSONObject metadata = new JSONObject()
                .put("id", id)
                .put("projectId", project.id)
                .put("path", relative)
                .put("existed", existed)
                .put("directory", wasDirectory)
                .put("action", action == null ? "change" : action)
                .put("time", System.currentTimeMillis())
                .put("bytes", existed ? size(payload) : 0L);
        ProjectManager.write(new File(directory, "checkpoint.json"), metadata.toString(2));
        AuditLog.get(context).append(project.id, "checkpoint", "create",
                id + " · " + relative + " · " + metadata.optString("action"), true);
        prune(project);
        return metadata;
    }

    public synchronized List<JSONObject> list(Project project, int limit) {
        List<JSONObject> result = new ArrayList<>();
        File[] directories = projectRoot(project).listFiles(File::isDirectory);
        if (directories == null) return result;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            if (result.size() >= Math.max(1, limit)) break;
            try {
                File metadata = new File(directory, "checkpoint.json");
                if (metadata.isFile()) result.add(new JSONObject(ProjectManager.read(metadata)));
            } catch (Exception ignored) {}
        }
        return result;
    }

    public synchronized JSONObject restore(Project project, String checkpointId) throws Exception {
        File directory = safeCheckpoint(project, checkpointId);
        JSONObject metadata = new JSONObject(ProjectManager.read(new File(directory, "checkpoint.json")));
        File target = projects.safeResolve(project, metadata.getString("path"));
        if (target.exists()) ProjectManager.deleteRecursively(target);
        if (metadata.optBoolean("existed")) {
            File payload = new File(directory, "payload");
            if (!payload.exists()) throw new IllegalStateException("Checkpoint payload is missing");
            copy(payload, target);
        }
        AuditLog.get(context).append(project.id, "checkpoint", "restore",
                checkpointId + " · " + metadata.optString("path"), true);
        return metadata.put("restored", true);
    }

    public synchronized void clear(Project project) throws Exception {
        File directory = projectRoot(project);
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) ProjectManager.deleteRecursively(child);
    }

    private void prune(Project project) {
        File[] directories = projectRoot(project).listFiles(File::isDirectory);
        if (directories == null) return;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified));
        long total = 0L;
        for (File directory : directories) total += size(directory);
        int count = directories.length;
        for (File directory : directories) {
            if (count <= MAX_CHECKPOINTS && total <= MAX_TOTAL_BYTES) break;
            long bytes = size(directory);
            try { ProjectManager.deleteRecursively(directory); } catch (Exception ignored) {}
            total -= bytes;
            count--;
        }
    }

    private File projectRoot(Project project) {
        File directory = new File(root, project.id.replaceAll("[^A-Za-z0-9._-]", "_"));
        directory.mkdirs();
        return directory;
    }

    private File safeCheckpoint(Project project, String id) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) throw new SecurityException("Invalid checkpoint id");
        File base = projectRoot(project).getCanonicalFile();
        File directory = new File(base, id).getCanonicalFile();
        if (!directory.getPath().startsWith(base.getPath() + File.separator) || !directory.isDirectory()) {
            throw new IllegalArgumentException("Checkpoint not found");
        }
        return directory;
    }

    private static String relative(Project project, File target) throws Exception {
        String rootPath = project.root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("Checkpoint target escapes project");
        }
        return project.root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static void copy(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("Cannot create checkpoint directory");
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copy(child, new File(target, child.getName()));
        } else {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            }
            target.setLastModified(source.lastModified());
        }
    }

    private static long size(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }
}
