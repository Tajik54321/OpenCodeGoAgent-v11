package com.qandil.opencodego.redis;

import android.content.Context;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Redis server and redis-cli management through an ARM64 runtime pack. */
public final class RedisManager {
    private final RuntimeManager runtimes;
    private final ProcessSupervisor processes;

    public RedisManager(Context context) {
        runtimes = RuntimeManager.get(context);
        processes = ProcessSupervisor.get(context);
    }

    public ProcessSupervisor.Record start(Project project, int port, String password) throws Exception {
        File server = runtimes.executableAny("redis", "redis-server");
        if (server == null) throw new IllegalStateException("Redis runtime is not installed");
        int safePort = port <= 0 ? 6379 : Math.min(65535, port);
        File directory = new File(project.root, ".opencode/redis");
        directory.mkdirs();
        File config = new File(directory, "redis.conf");
        StringBuilder value = new StringBuilder()
                .append("bind 127.0.0.1\nprotected-mode yes\n")
                .append("port ").append(safePort).append('\n')
                .append("dir ").append(directory.getAbsolutePath()).append('\n')
                .append("appendonly yes\nappendfilename appendonly.aof\n")
                .append("save 900 1\nsave 300 10\nsave 60 10000\n");
        if (password != null && !password.isEmpty()) value.append("requirepass ").append(quoteConfig(password)).append('\n');
        com.qandil.opencodego.project.ProjectManager.write(config, value.toString());
        return processes.start(project.id, "Redis", java.util.Arrays.asList(server.getAbsolutePath(), config.getAbsolutePath()),
                project.root, Collections.emptyMap());
    }

    public JSONObject info(Project project, int port, String password) throws Exception {
        return new JSONObject().put("raw", cli(project, port, password, "INFO"));
    }

    public JSONArray keys(Project project, int port, String password, String pattern, int count) throws Exception {
        String output = cli(project, port, password, "--scan", "--pattern",
                pattern == null || pattern.isEmpty() ? "*" : pattern, "--count", String.valueOf(Math.max(1, Math.min(1000, count))));
        JSONArray result = new JSONArray();
        for (String line : output.split("\\r?\\n")) if (!line.isEmpty()) result.put(line);
        return result;
    }

    public String get(Project project, int port, String password, String key) throws Exception {
        validateKey(key); return cli(project, port, password, "GET", key);
    }

    public String set(Project project, int port, String password, String key, String value, long ttlSeconds) throws Exception {
        validateKey(key);
        if (ttlSeconds > 0) return cli(project, port, password, "SET", key, value, "EX", String.valueOf(ttlSeconds));
        return cli(project, port, password, "SET", key, value);
    }

    public String delete(Project project, int port, String password, String key) throws Exception {
        validateKey(key); return cli(project, port, password, "DEL", key);
    }

    private String cli(Project project, int port, String password, String... command) throws Exception {
        File cli = runtimes.executableAny("redis", "redis-cli");
        if (cli == null) throw new IllegalStateException("redis-cli runtime is not installed");
        List<String> args = new ArrayList<>();
        args.add(cli.getAbsolutePath()); args.add("-h"); args.add("127.0.0.1");
        args.add("-p"); args.add(String.valueOf(port <= 0 ? 6379 : port));
        if (password != null && !password.isEmpty()) { args.add("-a"); args.add(password); args.add("--no-auth-warning"); }
        Collections.addAll(args, command);
        ProcessSupervisor.Result result = processes.run(project.id, "redis-cli", args, project.root,
                Collections.emptyMap(), 60);
        if (result.exitCode != 0 || result.timedOut) throw new IllegalStateException(result.output);
        return result.output;
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 4096 || key.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid Redis key");
    }
    private static String quoteConfig(String value) { return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; }
}
