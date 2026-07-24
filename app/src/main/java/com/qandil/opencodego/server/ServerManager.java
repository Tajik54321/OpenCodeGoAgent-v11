package com.qandil.opencodego.server;

import android.content.Context;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;

import java.io.File;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServerManager {
    public static final class ServerHandle {
        public final String projectId;
        public final String engine;
        public final int port;
        public final boolean lan;
        public final String url;
        public final String warning;
        final LocalWebServer staticServer;
        final String processId;

        ServerHandle(
                String projectId,
                String engine,
                int port,
                boolean lan,
                String url,
                String warning,
                LocalWebServer staticServer,
                String processId) {
            this.projectId = projectId;
            this.engine = engine;
            this.port = port;
            this.lan = lan;
            this.url = url;
            this.warning = warning;
            this.staticServer = staticServer;
            this.processId = processId;
        }

        public boolean running(ProcessSupervisor supervisor) {
            if (staticServer != null) return staticServer.isRunning();
            ProcessSupervisor.Record record = supervisor.get(processId);
            return record != null && record.running();
        }
    }

    private static ServerManager instance;
    private final Context context;
    private final RuntimeManager runtimes;
    private final ProcessSupervisor processes;
    private final Map<String, ServerHandle> servers = new LinkedHashMap<>();

    private ServerManager(Context context) {
        this.context = context.getApplicationContext();
        runtimes = RuntimeManager.get(context);
        processes = ProcessSupervisor.get(context);
    }

    public static synchronized ServerManager get(Context context) {
        if (instance == null) instance = new ServerManager(context.getApplicationContext());
        return instance;
    }

    public synchronized ServerHandle start(Project project, int requestedPort, boolean lan) throws Exception {
        stop(project.id);
        int port = requestedPort <= 0 ? project.preferredPort : requestedPort;
        if (port <= 0) port = freePort();
        String host = lan ? "0.0.0.0" : "127.0.0.1";
        String url = "http://127.0.0.1:" + port + "/";
        ServerHandle handle;
        if (project.is("php") && runtimes.installed("php")) {
            File php = runtimes.executable("php");
            File artisan = new File(project.root, "artisan");
            List<String> command = artisan.isFile()
                    ? Arrays.asList(php.getAbsolutePath(), artisan.getAbsolutePath(), "serve",
                            "--host=" + host, "--port=" + port)
                    : Arrays.asList(php.getAbsolutePath(), "-S", host + ":" + port,
                            "-t", project.root.getAbsolutePath());
            ProcessSupervisor.Record record = processes.start(
                    project.id, "PHP server", command, project.root,
                    environment(host, port));
            waitForPort(port, record, 15_000);
            handle = new ServerHandle(project.id, "php", port, lan, url, "", null, record.id);
        } else if (project.is("node") && runtimes.installed("node")) {
            File node = runtimes.executable("node");
            File entry = ProjectManager.get(context).safeResolve(project, project.entryPoint);
            if (!entry.isFile()) throw new IllegalStateException("Node entry point not found: " + project.entryPoint);
            ProcessSupervisor.Record record = processes.start(
                    project.id, "Node.js server",
                    Arrays.asList(node.getAbsolutePath(), entry.getAbsolutePath()), project.root,
                    environment(host, port));
            waitForPort(port, record, 15_000);
            handle = new ServerHandle(project.id, "node", port, lan, url, "", null, record.id);
        } else if (project.is("python") && runtimes.installed("python")) {
            File python = runtimes.executable("python");
            File entry = ProjectManager.get(context).safeResolve(project, project.entryPoint);
            List<String> command;
            if (entry.isFile()) command = Arrays.asList(python.getAbsolutePath(), entry.getAbsolutePath());
            else command = Arrays.asList(python.getAbsolutePath(), "-m", "http.server", String.valueOf(port), "--bind", host);
            ProcessSupervisor.Record record = processes.start(
                    project.id, "Python server", command, project.root,
                    environment(host, port));
            waitForPort(port, record, 15_000);
            handle = new ServerHandle(project.id, "python", port, lan, url, "", null, record.id);
        } else if (project.is("nginx") && runtimes.installed("nginx")) {
            File nginx = runtimes.executableAny("nginx", "nginx");
            File work = new File(project.root, ".opencode/nginx");
            work.mkdirs();
            File logs = new File(work, "logs"); logs.mkdirs();
            File temp = new File(work, "temp"); temp.mkdirs();
            File config = new File(work, "nginx.conf");
            ProjectManager.write(config, nginxConfig(project.root, work, host, port));
            ProcessSupervisor.Record record = processes.start(project.id, "Nginx",
                    Arrays.asList(nginx.getAbsolutePath(), "-p", work.getAbsolutePath() + "/",
                            "-c", config.getAbsolutePath(), "-g", "daemon off;"),
                    project.root, environment(host, port));
            waitForPort(port, record, 15_000);
            handle = new ServerHandle(project.id, "nginx", port, lan, url, "", null, record.id);
        } else if (project.is("apache") && runtimes.installed("apache")) {
            File httpd = runtimes.executableAny("apache", "httpd", "apache2");
            File work = new File(project.root, ".opencode/apache");
            work.mkdirs(); new File(work, "logs").mkdirs();
            File config = new File(work, "httpd.conf");
            ProjectManager.write(config, apacheConfig(project.root, work, host, port));
            ProcessSupervisor.Record record = processes.start(project.id, "Apache",
                    Arrays.asList(httpd.getAbsolutePath(), "-f", config.getAbsolutePath(), "-DFOREGROUND"),
                    project.root, environment(host, port));
            waitForPort(port, record, 15_000);
            handle = new ServerHandle(project.id, "apache", port, lan, url, "", null, record.id);
        } else {
            LocalWebServer server = new LocalWebServer(project.root, port, lan);
            server.start();
            String warning = project.is("static") ? "" : project.type.toUpperCase() + " runtime не установлен; запущен статический preview";
            handle = new ServerHandle(project.id, "static", server.port(), lan, server.url(), warning, server, null);
        }
        servers.put(project.id, handle);
        AuditLog.get(context).append(project.id, "server", "start", handle.engine + " " + handle.url, true);
        return handle;
    }

    public synchronized void stop(String projectId) {
        ServerHandle handle = servers.remove(projectId);
        if (handle == null) return;
        if (handle.staticServer != null) handle.staticServer.stop();
        if (handle.processId != null) processes.stop(handle.processId, false);
        AuditLog.get(context).append(projectId, "server", "stop", handle.engine, true);
    }

    public synchronized void stopAll() {
        for (String projectId : new ArrayList<>(servers.keySet())) stop(projectId);
    }

    public synchronized ServerHandle get(String projectId) {
        ServerHandle handle = servers.get(projectId);
        if (handle != null && !handle.running(processes)) return handle;
        return handle;
    }

    public synchronized boolean isRunning(String projectId) {
        ServerHandle handle = servers.get(projectId);
        return handle != null && handle.running(processes);
    }

    public synchronized boolean any() {
        for (ServerHandle handle : servers.values()) if (handle.running(processes)) return true;
        return false;
    }

    public synchronized List<String> logs(String projectId) {
        ServerHandle handle = servers.get(projectId);
        if (handle == null) return Collections.emptyList();
        if (handle.staticServer != null) return handle.staticServer.logs();
        ProcessSupervisor.Record record = processes.get(handle.processId);
        return record == null ? Collections.emptyList() : record.logs();
    }


    private static String nginxConfig(File documentRoot, File work, String host, int port) {
        String root = documentRoot.getAbsolutePath().replace("\\", "/");
        String prefix = work.getAbsolutePath().replace("\\", "/");
        return "worker_processes 1;\n"
                + "error_log " + prefix + "/logs/error.log info;\n"
                + "pid " + prefix + "/nginx.pid;\n"
                + "events { worker_connections 256; }\n"
                + "http {\n  include mime.types;\n  default_type application/octet-stream;\n"
                + "  access_log " + prefix + "/logs/access.log;\n"
                + "  client_body_temp_path " + prefix + "/temp;\n"
                + "  server { listen " + host + ":" + port + "; server_name localhost;\n"
                + "    root " + quoteNginx(root) + "; index index.html index.htm index.php;\n"
                + "    location / { try_files $uri $uri/ /index.html; }\n"
                + "  }\n}\n";
    }

    private static String apacheConfig(File documentRoot, File work, String host, int port) {
        String root = documentRoot.getAbsolutePath();
        String logs = new File(work, "logs").getAbsolutePath();
        return "ServerRoot \"" + work.getAbsolutePath() + "\"\n"
                + "Listen " + host + ":" + port + "\n"
                + "PidFile \"" + new File(work, "httpd.pid").getAbsolutePath() + "\"\n"
                + "DocumentRoot \"" + root + "\"\n"
                + "<Directory \"" + root + "\">\n  Require all granted\n  AllowOverride All\n  Options Indexes FollowSymLinks\n</Directory>\n"
                + "DirectoryIndex index.html index.htm index.php\n"
                + "ErrorLog \"" + new File(logs, "error.log").getAbsolutePath() + "\"\n"
                + "CustomLog \"" + new File(logs, "access.log").getAbsolutePath() + "\" common\n";
    }

    private static String quoteNginx(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void waitForPort(int port, ProcessSupervisor.Record record, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!record.running()) {
                throw new IllegalStateException("Server process exited:\n"
                        + String.join("\n", record.logs()));
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
                return;
            } catch (Exception error) {
                last = error;
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("Server did not open port " + port
                + (last == null ? "" : ": " + last.getMessage()));
    }

    private static Map<String, String> environment(String host, int port) {
        Map<String, String> environment = new HashMap<>();
        environment.put("HOST", host);
        environment.put("PORT", String.valueOf(port));
        environment.put("NODE_ENV", "development");
        environment.put("PYTHONUNBUFFERED", "1");
        return environment;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
