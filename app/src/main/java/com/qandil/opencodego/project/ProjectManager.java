package com.qandil.opencodego.project;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProjectManager {
    private static final long MAX_TEXT_FILE = 4L * 1024L * 1024L;
    private static ProjectManager instance;
    private final File projectsRoot;

    private ProjectManager(Context context) {
        projectsRoot = new File(context.getFilesDir(), "projects");
        projectsRoot.mkdirs();
    }

    public static synchronized ProjectManager get(Context context) {
        if (instance == null) instance = new ProjectManager(context.getApplicationContext());
        return instance;
    }

    public synchronized void ensureStarterProject() {
        if (!list().isEmpty()) return;
        try { create("Starter Site", "static"); } catch (Exception ignored) {}
    }

    public synchronized Project create(String name, String type) throws Exception {
        String normalizedType = normalizeType(type);
        String safeName = name == null || name.trim().isEmpty() ? "Новый проект" : name.trim();
        String slug = slug(safeName);
        String id = slug + "-" + System.currentTimeMillis();
        File directory = new File(projectsRoot, id);
        if (!directory.mkdirs()) throw new IOException("Не удалось создать папку проекта");
        new File(directory, "databases").mkdirs();
        new File(directory, ".opencode").mkdirs();
        long now = System.currentTimeMillis();
        Project project = new Project(
                id, safeName, normalizedType, directory,
                defaultEntry(normalizedType), defaultPort(normalizedType), false, now, now);
        save(project);
        writeTemplate(project);
        return project;
    }

    public synchronized Project importExisting(File directory, String name, String type) throws Exception {
        if (directory == null || !directory.isDirectory()) throw new IOException("Папка проекта не найдена");
        String id = directory.getName();
        long now = System.currentTimeMillis();
        Project project = new Project(
                id,
                name == null || name.trim().isEmpty() ? id : name.trim(),
                normalizeType(type),
                directory,
                defaultEntry(type),
                defaultPort(type),
                false,
                now,
                now);
        save(project);
        new File(directory, "databases").mkdirs();
        return project;
    }

    public synchronized void save(Project project) throws Exception {
        JSONObject meta = new JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("type", normalizeType(project.type))
                .put("entryPoint", project.entryPoint)
                .put("preferredPort", project.preferredPort)
                .put("lanEnabled", project.lanEnabled)
                .put("createdAt", project.createdAt)
                .put("updatedAt", System.currentTimeMillis());
        write(new File(project.root, "project.json"), meta.toString(2));
        project.root.setLastModified(System.currentTimeMillis());
    }

    public synchronized Project updateSettings(
            Project project,
            String name,
            String type,
            String entryPoint,
            int port,
            boolean lanEnabled) throws Exception {
        Project updated = new Project(
                project.id,
                name == null || name.trim().isEmpty() ? project.name : name.trim(),
                normalizeType(type),
                project.root,
                entryPoint == null ? "" : entryPoint.trim(),
                port <= 0 ? defaultPort(type) : port,
                lanEnabled,
                project.createdAt,
                System.currentTimeMillis());
        save(updated);
        return updated;
    }

    public synchronized List<Project> list() {
        List<Project> result = new ArrayList<>();
        File[] directories = projectsRoot.listFiles(File::isDirectory);
        if (directories == null) return result;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            try { result.add(readProject(directory)); } catch (Exception ignored) {}
        }
        return result;
    }

    public synchronized Project find(String id) {
        if (id == null) return null;
        for (Project project : list()) if (id.equals(project.id)) return project;
        return null;
    }

    public synchronized Project duplicate(Project source, String newName) throws Exception {
        Project target = create(newName, source.type);
        deleteChildrenExceptMeta(target.root);
        copyDirectory(source.root, target.root, true);
        Project updated = new Project(
                target.id,
                newName,
                source.type,
                target.root,
                source.entryPoint,
                source.preferredPort,
                false,
                target.createdAt,
                System.currentTimeMillis());
        save(updated);
        return updated;
    }

    public synchronized void delete(Project project) throws IOException {
        if (project == null) return;
        deleteRecursively(project.root);
    }

    public synchronized void clearForImport(Project project) throws IOException {
        if (project == null) return;
        File[] children = project.root.listFiles();
        if (children != null) for (File child : children) {
            if ("project.json".equals(child.getName())) continue;
            deleteRecursively(child);
        }
        new File(project.root, "databases").mkdirs();
        new File(project.root, ".opencode").mkdirs();
    }

    public File safeResolve(Project project, String relativePath) throws IOException {
        return safeResolve(project.root, relativePath);
    }

    public static File safeResolve(File root, String relativePath) throws IOException {
        String clean = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        File candidate = new File(root, clean);
        String rootPath = root.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        if (!candidatePath.equals(rootPath)
                && !candidatePath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("Путь выходит за пределы проекта");
        }
        return candidate;
    }

    public List<File> listRecursive(Project project, int maxFiles) {
        List<File> result = new ArrayList<>();
        walk(project.root, result, Math.max(1, maxFiles));
        return result;
    }

    public static String read(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) throw new IOException("Файл не найден");
        if (file.length() > MAX_TEXT_FILE) throw new IOException("Файл слишком большой для редактора");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static void write(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать каталог");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Project readProject(File directory) throws Exception {
        File metadata = new File(directory, "project.json");
        if (!metadata.exists()) {
            String detected = detectType(directory);
            long now = System.currentTimeMillis();
            Project project = new Project(
                    directory.getName(), directory.getName(), detected, directory,
                    defaultEntry(detected), defaultPort(detected), false, now, now);
            save(project);
            return project;
        }
        JSONObject object = new JSONObject(read(metadata));
        String type = normalizeType(object.optString("type", detectType(directory)));
        return new Project(
                object.optString("id", directory.getName()),
                object.optString("name", directory.getName()),
                type,
                directory,
                object.optString("entryPoint", defaultEntry(type)),
                object.optInt("preferredPort", defaultPort(type)),
                object.optBoolean("lanEnabled", false),
                object.optLong("createdAt", directory.lastModified()),
                object.optLong("updatedAt", directory.lastModified()));
    }

    public static String detectType(File directory) {
        if (new File(directory, "artisan").exists()) return "php";
        if (new File(directory, "composer.json").exists() || new File(directory, "index.php").exists()) return "php";
        if (new File(directory, "package.json").exists()) return "node";
        if (new File(directory, "requirements.txt").exists()
                || new File(directory, "pyproject.toml").exists()
                || new File(directory, "manage.py").exists()) return "python";
        return "static";
    }

    private void writeTemplate(Project project) throws Exception {
        if (project.is("php")) {
            write(new File(project.root, "index.php"),
                    "<?php\nheader('Content-Type: text/html; charset=utf-8');\n"
                    + "$now = date('Y-m-d H:i:s');\n?>\n"
                    + "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<link rel=\"stylesheet\" href=\"style.css\"><title>" + escape(project.name) + "</title></head>"
                    + "<body><main><b>PHP SERVER</b><h1>" + escape(project.name) + "</h1>"
                    + "<p>PHP работает внутри Android Runtime Pack.</p><code><?= htmlspecialchars($now) ?></code></main></body></html>");
            writeStyle(project.root);
        } else if (project.is("node")) {
            write(new File(project.root, "package.json"), new JSONObject()
                    .put("name", slug(project.name))
                    .put("version", "1.0.0")
                    .put("private", true)
                    .put("scripts", new JSONObject().put("start", "node server.js"))
                    .toString(2));
            write(new File(project.root, "server.js"),
                    "const http = require('http');\n"
                    + "const port = Number(process.env.PORT || 3000);\n"
                    + "http.createServer((req, res) => {\n"
                    + "  res.writeHead(200, {'content-type':'text/html; charset=utf-8'});\n"
                    + "  res.end('<h1>OpenCode Go Node.js server</h1><p>'+new Date().toISOString()+'</p>');\n"
                    + "}).listen(port, process.env.HOST || '127.0.0.1', () => console.log('Listening '+port));\n");
        } else if (project.is("python")) {
            write(new File(project.root, "app.py"),
                    "from http.server import BaseHTTPRequestHandler, HTTPServer\n"
                    + "import os\n\n"
                    + "class Handler(BaseHTTPRequestHandler):\n"
                    + "    def do_GET(self):\n"
                    + "        body = b'<h1>OpenCode Go Python server</h1>'\n"
                    + "        self.send_response(200)\n"
                    + "        self.send_header('Content-Type', 'text/html; charset=utf-8')\n"
                    + "        self.send_header('Content-Length', str(len(body)))\n"
                    + "        self.end_headers()\n"
                    + "        self.wfile.write(body)\n\n"
                    + "port = int(os.environ.get('PORT', '8000'))\n"
                    + "HTTPServer((os.environ.get('HOST', '127.0.0.1'), port), Handler).serve_forever()\n");
            write(new File(project.root, "requirements.txt"), "");
        } else {
            write(new File(project.root, "index.html"), starterHtml(project.name));
            writeStyle(project.root);
            write(new File(project.root, "app.js"),
                    "document.querySelector('[data-time]').textContent = new Date().toLocaleString();\n");
        }
    }

    private static String starterHtml(String name) {
        return "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(name) + "</title><link rel=\"stylesheet\" href=\"style.css\"></head>"
                + "<body><main><section><b>OPENCODE GO AGENT</b><h1>" + escape(name) + "</h1>"
                + "<p>Встроенный localhost-сервер запущен.</p><span class=\"pill\" data-time></span>"
                + "</section></main><script src=\"app.js\"></script></body></html>";
    }

    private static void writeStyle(File root) throws IOException {
        write(new File(root, "style.css"),
                "*{box-sizing:border-box}body{margin:0;background:#08110f;color:#f0f8f4;font-family:system-ui}"
                + "main{min-height:100vh;display:grid;place-content:center;padding:28px}"
                + "section{max-width:720px;background:#121f1b;border:1px solid #294a3d;border-radius:28px;padding:34px}"
                + "b{color:#3ddc84}h1{font-size:clamp(36px,8vw,64px);margin:12px 0}"
                + "p{color:#9db1a8;line-height:1.7}.pill{display:inline-block;background:#19362b;padding:10px 14px;border-radius:99px}");
    }

    private static String normalizeType(String value) {
        if (value == null) return "static";
        String type = value.toLowerCase(Locale.ROOT).trim();
        if (type.equals("php") || type.equals("node") || type.equals("python")
                || type.equals("nginx") || type.equals("apache")) return type;
        return "static";
    }

    private static int defaultPort(String type) {
        if ("node".equalsIgnoreCase(type)) return 3000;
        if ("python".equalsIgnoreCase(type)) return 8000;
        if ("nginx".equalsIgnoreCase(type)) return 8081;
        if ("apache".equalsIgnoreCase(type)) return 8082;
        return 8080;
    }

    private static String defaultEntry(String type) {
        if ("node".equalsIgnoreCase(type)) return "server.js";
        if ("python".equalsIgnoreCase(type)) return "app.py";
        if ("php".equalsIgnoreCase(type)) return "index.php";
        return "index.html";
    }

    private static String slug(String value) {
        String result = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9а-яё]+", "-")
                .replaceAll("(^-|-$)", "");
        return result.isEmpty() ? "project" : result;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void walk(File directory, List<File> output, int maxFiles) {
        if (output.size() >= maxFiles) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (output.size() >= maxFiles) return;
            if (file.getName().equals("project.json") || file.getName().equals(".opencode")) continue;
            if (file.isDirectory()) walk(file, output, maxFiles);
            else output.add(file);
        }
    }

    private static void copyDirectory(File source, File target, boolean skipMetadata) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IOException("Не удалось создать каталог");
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (skipMetadata && child.getName().equals("project.json")) continue;
                    copyDirectory(child, new File(target, child.getName()), skipMetadata);
                }
            }
        } else {
            Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteChildrenExceptMeta(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.getName().equals("project.json")) deleteRecursively(child);
        }
    }

    public static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Не удалось удалить: " + file.getName());
    }
}
