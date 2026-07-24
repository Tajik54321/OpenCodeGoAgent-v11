package com.qandil.opencodego.ai;

import android.content.Context;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.build.BuildCenter;
import com.qandil.opencodego.cron.CronManager;
import com.qandil.opencodego.git.GitManager;
import com.qandil.opencodego.integration.IntegrationStore;
import com.qandil.opencodego.integration.McpClient;
import com.qandil.opencodego.integration.OpenCodeServerClient;
import com.qandil.opencodego.lsp.LspManager;
import com.qandil.opencodego.redis.RedisManager;
import com.qandil.opencodego.remote.RemoteManager;
import com.qandil.opencodego.security.SecureStore;
import com.qandil.opencodego.database.DatabaseManager;
import com.qandil.opencodego.database.SqlSafety;
import com.qandil.opencodego.history.CheckpointManager;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.server.ServerManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Multi-turn tool-calling coding agent with project-scoped permissions. */
public final class AgentEngine {
    private static final int MAX_STEPS = 24;
    private static final int MAX_FILE_CHARS = 500_000;
    private static final int MAX_TOOL_OUTPUT = 250_000;

    private final Context context;
    private final ProjectManager projects;
    private final RuntimeManager runtimes;
    private final DatabaseManager databases;
    private final ProcessSupervisor processes;
    private final ServerManager servers;
    private final PermissionStore permissions;
    private final AuditLog audit;
    private final CheckpointManager checkpoints;
    private final ProviderClient client = new ProviderClient();
    private final ProviderStore providerStore;
    private final ProviderRouter providerRouter = new ProviderRouter();
    private final GitManager git;
    private final RemoteManager remote;
    private final RedisManager redis;
    private final CronManager cron;
    private final BuildCenter builds;
    private final IntegrationStore integrations;
    private final LspManager lsp;
    private final SecureStore secure;

    public AgentEngine(Context context) {
        this.context = context.getApplicationContext();
        projects = ProjectManager.get(context);
        runtimes = RuntimeManager.get(context);
        databases = DatabaseManager.get(context);
        processes = ProcessSupervisor.get(context);
        servers = ServerManager.get(context);
        permissions = new PermissionStore(context);
        audit = AuditLog.get(context);
        checkpoints = new CheckpointManager(context);
        providerStore = ProviderStore.get(context);
        git = new GitManager(context);
        remote = new RemoteManager(context);
        redis = new RedisManager(context);
        cron = CronManager.get(context);
        builds = new BuildCenter(context);
        integrations = new IntegrationStore(context);
        lsp = new LspManager(context);
        secure = new SecureStore(context);
    }

    public String run(Project project, Provider provider, String userRequest) throws Exception {
        return runWithRole(project, provider, userRequest, AgentProfile.BUILD);
    }

    public String runWithRole(Project project, Provider provider, String userRequest, String role) throws Exception {
        if (project == null) throw new IllegalArgumentException("Проект не выбран");
        if (provider == null) throw new IllegalArgumentException("Провайдер не выбран");
        String request = userRequest == null ? "" : userRequest.trim();
        if (request.isEmpty()) throw new IllegalArgumentException("Задача пуста");
        AgentProfile profile = AgentProfile.of(role);
        List<Provider> candidates = providerRouter.candidates(provider, providerStore.enabled(), request, profile.id);
        if (candidates.isEmpty()) throw new IllegalStateException("Нет доступного провайдера с ключом или локальным endpoint");
        for (Provider candidate : candidates) if (!candidate.local()) {
            permissions.require(project.id, PermissionStore.NETWORK);
            break;
        }
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(project, profile)));
        messages.put(new JSONObject().put("role", "user").put("content", request));
        JSONArray tools = tools(profile);
        StringBuilder finalText = new StringBuilder();
        audit.append(project.id, "agent", "task_start", profile.id + " · " + request, true);
        Provider activeProvider = candidates.get(0);
        try {
            for (int step = 0; step < MAX_STEPS; step++) {
                ProviderClient.Reply reply = null;
                Exception lastProviderError = null;
                for (Provider candidate : candidates) {
                    try {
                        reply = client.send(candidate, messages, tools);
                        activeProvider = candidate;
                        if (candidate != provider) audit.append(project.id, "agent", "provider_fallback",
                                provider.name + " -> " + candidate.name, true);
                        break;
                    } catch (Exception error) {
                        lastProviderError = error;
                        audit.append(project.id, "agent", "provider_error", candidate.name + " · " + safeError(error), false);
                        if (!providerRouter.retryable(error)) throw error;
                    }
                }
                if (reply == null) throw lastProviderError == null
                        ? new IllegalStateException("Все провайдеры недоступны") : lastProviderError;
                if (!reply.text.trim().isEmpty()) {
                    if (finalText.length() > 0) finalText.append("\n\n");
                    finalText.append(reply.text.trim());
                }
                if (reply.toolCalls.isEmpty()) {
                    audit.append(project.id, "agent", "task_finish", activeProvider.name + " · " + truncate(finalText.toString(), 4_000), true);
                    return finalText.length() == 0 ? "Задача завершена." : finalText.toString();
                }
                JSONArray callsJson = new JSONArray();
                for (ProviderClient.ToolCall call : reply.toolCalls) {
                    callsJson.put(new JSONObject()
                            .put("id", call.id)
                            .put("name", call.name)
                            .put("arguments", call.arguments));
                }
                messages.put(new JSONObject()
                        .put("role", "assistant")
                        .put("content", reply.text)
                        .put("tool_calls", callsJson));
                for (ProviderClient.ToolCall call : reply.toolCalls) {
                    JSONObject result;
                    try {
                        result = executeTool(project, call.name, call.arguments);
                        audit.append(project.id, "agent_tool", call.name,
                                auditArguments(call.name, call.arguments), true);
                    } catch (Exception error) {
                        result = new JSONObject()
                                .put("ok", false)
                                .put("error", error.getClass().getSimpleName())
                                .put("message", safeError(error));
                        audit.append(project.id, "agent_tool", call.name,
                                auditArguments(call.name, call.arguments) + " · " + safeError(error), false);
                    }
                    messages.put(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.id)
                            .put("name", call.name)
                            .put("content", truncate(result.toString(), MAX_TOOL_OUTPUT)));
                }
            }
            throw new IllegalStateException("Агент превысил лимит из " + MAX_STEPS + " действий");
        } catch (Exception error) {
            audit.append(project.id, "agent", "task_finish", safeError(error), false);
            throw error;
        }
    }

    private JSONObject executeTool(Project project, String tool, JSONObject args) throws Exception {
        switch (tool) {
            case "project_info": return projectInfo(project);
            case "list_files": return listFiles(project, args);
            case "search_files": return searchFiles(project, args);
            case "read_file": return readFile(project, args);
            case "write_file": return writeFile(project, args);
            case "patch_file": return patchFile(project, args);
            case "make_directory": return makeDirectory(project, args);
            case "move_path": return movePath(project, args);
            case "delete_path": return deletePath(project, args);
            case "runtime_status": return runtimeStatus();
            case "run_command": return runCommand(project, args);
            case "server_start": return serverStart(project, args);
            case "server_stop": return serverStop(project);
            case "server_status": return serverStatus(project);
            case "server_logs": return serverLogs(project, args);
            case "database_list": return databaseList(project);
            case "database_schema": return databaseSchema(project, args);
            case "database_query": return databaseQuery(project, args);
            case "database_backup": return databaseBackup(project, args);
            case "audit_tail": return auditTail(project, args);
            case "checkpoint_list": return checkpointList(project, args);
            case "checkpoint_restore": return checkpointRestore(project, args);
            case "git_status": return gitStatus(project);
            case "git_log": return gitLog(project, args);
            case "git_diff": return gitDiff(project, args);
            case "git_commit": return gitCommit(project, args);
            case "git_pull": return gitPull(project, args);
            case "git_push": return gitPush(project, args);
            case "remote_ssh": return remoteSsh(project, args);
            case "remote_deploy": return remoteDeploy(project, args);
            case "redis_info": return redisInfo(project, args);
            case "redis_keys": return redisKeys(project, args);
            case "redis_get": return redisGet(project, args);
            case "redis_set": return redisSet(project, args);
            case "redis_delete": return redisDelete(project, args);
            case "cron_list": return cronList(project);
            case "cron_save": return cronSave(project, args);
            case "cron_run": return cronRun(project, args);
            case "build_inspect": return buildInspect(project);
            case "build_android": return buildAndroid(project, args);
            case "opencode_health": return openCodeHealth(project, args);
            case "opencode_sessions": return openCodeSessions(project, args);
            case "opencode_prompt": return openCodePrompt(project, args);
            case "mcp_list_tools": return mcpListTools(project, args);
            case "mcp_call_tool": return mcpCallTool(project, args);
            case "lsp_start": return lspStart(project, args);
            case "lsp_request": return lspRequest(project, args);
            default: throw new IllegalArgumentException("Неизвестный инструмент: " + tool);
        }
    }

    private JSONObject projectInfo(Project project) throws Exception {
        permissions.require(project.id, PermissionStore.READ_FILES);
        JSONArray runtimeStatus = new JSONArray();
        for (RuntimeManager.RuntimeInfo runtime : runtimes.list()) {
            runtimeStatus.put(new JSONObject()
                    .put("name", runtime.name).put("installed", runtime.installed)
                    .put("version", runtime.version).put("problem", runtime.problem));
        }
        return new JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("type", project.type)
                .put("entryPoint", project.entryPoint)
                .put("preferredPort", project.preferredPort)
                .put("root", project.root.getAbsolutePath())
                .put("runtimes", runtimeStatus)
                .put("databases", permissions.allowed(project.id, PermissionStore.DB_READ)
                        ? databaseList(project).getJSONArray("databases") : new JSONArray())
                .put("databaseAccess", permissions.allowed(project.id, PermissionStore.DB_READ));
    }

    private JSONObject listFiles(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.READ_FILES);
        String relative = args.optString("path", "");
        File directory = projects.safeResolve(project, relative);
        if (!directory.isDirectory()) throw new IllegalArgumentException("Каталог не найден: " + relative);
        File[] files = directory.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        JSONArray result = new JSONArray();
        for (File file : files) {
            if ("project.json".equals(file.getName())) continue;
            result.put(new JSONObject()
                    .put("name", file.getName())
                    .put("path", relative(project, file))
                    .put("directory", file.isDirectory())
                    .put("size", file.length())
                    .put("modifiedAt", file.lastModified()));
        }
        return new JSONObject().put("ok", true).put("path", relative).put("items", result);
    }

    private JSONObject searchFiles(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.READ_FILES);
        String query = args.optString("query", "");
        String path = args.optString("path", "");
        int limit = Math.max(1, Math.min(args.optInt("limit", 100), 500));
        if (query.isEmpty()) throw new IllegalArgumentException("Строка поиска пуста");
        File start = projects.safeResolve(project, path);
        if (!start.exists()) throw new IllegalArgumentException("Путь не найден");
        JSONArray matches = new JSONArray();
        String lower = query.toLowerCase(Locale.ROOT);
        searchRecursive(project, start, lower, matches, limit);
        return new JSONObject().put("ok", true).put("matches", matches).put("count", matches.length());
    }

    private JSONObject readFile(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.READ_FILES);
        String path = required(args, "path");
        if (secretPath(path) && !permissions.allowed(project.id, PermissionStore.READ_SECRETS)) {
            throw new SecurityException("Для чтения секретов включите разрешение read_secrets");
        }
        File file = projects.safeResolve(project, path);
        String content = ProjectManager.read(file);
        int start = Math.max(0, args.optInt("start", 0));
        int max = Math.max(1, Math.min(args.optInt("maxChars", 100_000), MAX_FILE_CHARS));
        if (start > content.length()) start = content.length();
        int end = Math.min(content.length(), start + max);
        return new JSONObject()
                .put("ok", true).put("path", path)
                .put("content", content.substring(start, end))
                .put("start", start).put("end", end)
                .put("totalChars", content.length()).put("truncated", end < content.length());
    }

    private JSONObject writeFile(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        String path = required(args, "path");
        if (secretPath(path) && !permissions.allowed(project.id, PermissionStore.READ_SECRETS)) {
            throw new SecurityException("Для изменения секретного файла включите read_secrets");
        }
        File file = projects.safeResolve(project, path);
        String content = args.optString("content", "");
        if (content.length() > 2_000_000) throw new IllegalArgumentException("Содержимое слишком большое");
        boolean existed = file.exists();
        checkpoints.snapshot(project, file, "write_file");
        ProjectManager.write(file, content);
        return new JSONObject().put("ok", true).put("path", path)
                .put("created", !existed).put("bytes", file.length());
    }

    private JSONObject patchFile(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        String path = required(args, "path");
        File file = projects.safeResolve(project, path);
        String original = ProjectManager.read(file);
        checkpoints.snapshot(project, file, "patch_file");
        String before = required(args, "before");
        String after = args.optString("after", "");
        int expected = Math.max(1, args.optInt("expectedOccurrences", 1));
        int found = occurrences(original, before);
        if (found != expected) throw new IllegalStateException(
                "Ожидалось совпадений: " + expected + ", найдено: " + found);
        String updated = original.replace(before, after);
        ProjectManager.write(file, updated);
        return new JSONObject().put("ok", true).put("path", path)
                .put("occurrences", found).put("changedChars", updated.length() - original.length());
    }

    private JSONObject makeDirectory(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        String path = required(args, "path");
        File directory = projects.safeResolve(project, path);
        boolean created = directory.isDirectory() || directory.mkdirs();
        if (!created) throw new IllegalStateException("Не удалось создать каталог");
        return new JSONObject().put("ok", true).put("path", path);
    }

    private JSONObject movePath(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        File source = projects.safeResolve(project, required(args, "source"));
        File target = projects.safeResolve(project, required(args, "target"));
        if (!source.exists()) throw new IllegalArgumentException("Исходный путь не найден");
        checkpoints.snapshot(project, source, "move_source");
        checkpoints.snapshot(project, target, "move_target");
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new JSONObject().put("ok", true).put("source", relative(project, source))
                .put("target", relative(project, target));
    }

    private JSONObject deletePath(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        String path = required(args, "path");
        if (path.isEmpty() || ".".equals(path) || "/".equals(path)) {
            throw new SecurityException("Нельзя удалить корень проекта");
        }
        File target = projects.safeResolve(project, path);
        if (!target.exists()) return new JSONObject().put("ok", true).put("deleted", false);
        checkpoints.snapshot(project, target, "delete_path");
        ProjectManager.deleteRecursively(target);
        return new JSONObject().put("ok", true).put("deleted", true).put("path", path);
    }

    private JSONObject runtimeStatus() {
        JSONArray result = new JSONArray();
        for (RuntimeManager.RuntimeInfo info : runtimes.list()) {
            result.put(new JSONObject().put("name", info.name).put("installed", info.installed)
                    .put("version", info.version).put("abi", info.abi).put("problem", info.problem));
        }
        return new JSONObject().put("ok", true).put("runtimes", result);
    }

    private JSONObject runCommand(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.EXEC_COMMAND);
        JSONArray commandJson = args.optJSONArray("command");
        if (commandJson == null || commandJson.length() == 0) {
            throw new IllegalArgumentException("command должен быть массивом аргументов");
        }
        List<String> command = new ArrayList<>();
        for (int i = 0; i < commandJson.length(); i++) command.add(commandJson.getString(i));
        if (destructiveCommand(command)) permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        command.set(0, resolveCommand(command.get(0)));
        long timeout = Math.max(1, Math.min(args.optLong("timeoutSeconds", 120), 600));
        ProcessSupervisor.Result result = processes.run(
                project.id, "AI command", command, project.root, null, timeout);
        return new JSONObject().put("ok", result.exitCode == 0 && !result.timedOut)
                .put("exitCode", result.exitCode).put("timedOut", result.timedOut)
                .put("output", truncate(result.output, MAX_TOOL_OUTPUT));
    }

    private JSONObject serverStart(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.SERVER);
        int port = args.optInt("port", project.preferredPort);
        boolean lan = args.optBoolean("lan", false);
        ServerManager.ServerHandle handle = servers.start(project, port, lan);
        return new JSONObject().put("ok", true).put("engine", handle.engine)
                .put("port", handle.port).put("url", handle.url).put("warning", handle.warning);
    }

    private JSONObject serverStop(Project project) {
        permissions.require(project.id, PermissionStore.SERVER);
        servers.stop(project.id);
        return new JSONObject().put("ok", true).put("running", false);
    }

    private JSONObject serverStatus(Project project) {
        ServerManager.ServerHandle handle = servers.get(project.id);
        boolean running = servers.isRunning(project.id);
        JSONObject result = new JSONObject().put("ok", true).put("running", running);
        if (handle != null) result.put("engine", handle.engine).put("port", handle.port)
                .put("url", handle.url).put("warning", handle.warning);
        return result;
    }

    private JSONObject serverLogs(Project project, JSONObject args) {
        permissions.require(project.id, PermissionStore.SERVER);
        int limit = Math.max(1, Math.min(args.optInt("limit", 200), 1_000));
        List<String> lines = servers.logs(project.id);
        int start = Math.max(0, lines.size() - limit);
        return new JSONObject().put("ok", true)
                .put("lines", new JSONArray(lines.subList(start, lines.size())));
    }

    private JSONObject databaseList(Project project) throws Exception {
        permissions.require(project.id, PermissionStore.DB_READ);
        JSONArray result = new JSONArray();
        for (DatabaseManager.DbInfo info : databases.list(project)) {
            JSONObject item = databases.connectionInfo(info)
                    .put("id", info.id).put("name", info.name);
            result.put(item);
        }
        return new JSONObject().put("ok", true).put("databases", result);
    }

    private JSONObject databaseSchema(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.DB_READ);
        DatabaseManager.DbInfo info = requireDatabase(project, required(args, "database"));
        return new JSONObject().put("ok", true).put("database", info.name)
                .put("schema", databases.schema(project, info));
    }

    private JSONObject databaseQuery(Project project, JSONObject args) throws Exception {
        String sql = required(args, "sql");
        boolean write = databases.isWriteSql(sql);
        if (write) {
            permissions.require(project.id, PermissionStore.DB_WRITE);
            if (SqlSafety.isDestructive(sql)) permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        } else permissions.require(project.id, PermissionStore.DB_READ);
        DatabaseManager.DbInfo info = requireDatabase(project, required(args, "database"));
        if (SqlSafety.isDestructive(sql)) databases.backup(project, info);
        return databases.execute(project, info, sql, !write);
    }

    private JSONObject databaseBackup(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.DB_READ);
        DatabaseManager.DbInfo info = requireDatabase(project, required(args, "database"));
        File backup = databases.backup(project, info);
        return new JSONObject().put("ok", true).put("path", relative(project, backup))
                .put("bytes", backup.length());
    }

    private JSONObject auditTail(Project project, JSONObject args) {
        int limit = Math.max(1, Math.min(args.optInt("limit", 100), 500));
        return new JSONObject().put("ok", true).put("events", new JSONArray(audit.tail(project.id, limit)));
    }

    private JSONObject checkpointList(Project project, JSONObject args) {
        permissions.require(project.id, PermissionStore.READ_FILES);
        int limit = Math.max(1, Math.min(args.optInt("limit", 50), 200));
        return new JSONObject().put("ok", true).put("checkpoints", new JSONArray(checkpoints.list(project, limit)));
    }

    private JSONObject checkpointRestore(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        return new JSONObject().put("ok", true)
                .put("checkpoint", checkpoints.restore(project, required(args, "checkpointId")));
    }


    private JSONObject gitStatus(Project project) throws Exception {
        permissions.require(project.id, PermissionStore.GIT);
        return new JSONObject().put("ok", true).put("status", git.status(project));
    }

    private JSONObject gitLog(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.GIT);
        return new JSONObject().put("ok", true).put("log", git.log(project, args.optInt("count", 20)));
    }

    private JSONObject gitDiff(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.GIT);
        return new JSONObject().put("ok", true).put("diff", truncate(git.diff(project,
                args.optBoolean("staged", false)), MAX_TOOL_OUTPUT));
    }

    private JSONObject gitCommit(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.GIT);
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        git.addAll(project);
        return new JSONObject().put("ok", true).put("output", git.commit(project, required(args, "message")));
    }

    private JSONObject gitPull(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.GIT);
        permissions.require(project.id, PermissionStore.NETWORK);
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        return new JSONObject().put("ok", true).put("output", git.pull(project,
                args.optString("remote", "origin"), required(args, "branch")));
    }

    private JSONObject gitPush(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.GIT);
        permissions.require(project.id, PermissionStore.NETWORK);
        return new JSONObject().put("ok", true).put("output", git.push(project,
                args.optString("remote", "origin"), required(args, "branch"),
                args.optBoolean("setUpstream", false)));
    }

    private JSONObject remoteSsh(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REMOTE);
        permissions.require(project.id, PermissionStore.NETWORK);
        permissions.require(project.id, PermissionStore.EXEC_COMMAND);
        String output = remote.ssh(project, required(args, "host"), args.optInt("port", 22),
                required(args, "user"), required(args, "command"));
        return new JSONObject().put("ok", true).put("output", truncate(output, MAX_TOOL_OUTPUT));
    }

    private JSONObject remoteDeploy(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REMOTE);
        permissions.require(project.id, PermissionStore.NETWORK);
        if (args.optBoolean("delete", false)) permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        String output = remote.syncRsync(project, required(args, "host"), args.optInt("port", 22),
                required(args, "user"), required(args, "remotePath"), args.optBoolean("delete", false));
        return new JSONObject().put("ok", true).put("output", truncate(output, MAX_TOOL_OUTPUT));
    }

    private String redisPassword(Project project) {
        return secure.get("redis." + project.id + ".password", "");
    }

    private JSONObject redisInfo(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REDIS);
        return new JSONObject().put("ok", true).put("info", redis.info(project,
                args.optInt("port", 6379), redisPassword(project)));
    }

    private JSONObject redisKeys(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REDIS);
        return new JSONObject().put("ok", true).put("keys", redis.keys(project,
                args.optInt("port", 6379), redisPassword(project), args.optString("pattern", "*"),
                args.optInt("count", 100)));
    }

    private JSONObject redisGet(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REDIS);
        return new JSONObject().put("ok", true).put("value", redis.get(project,
                args.optInt("port", 6379), redisPassword(project), required(args, "key")));
    }

    private JSONObject redisSet(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REDIS);
        permissions.require(project.id, PermissionStore.DB_WRITE);
        return new JSONObject().put("ok", true).put("result", redis.set(project,
                args.optInt("port", 6379), redisPassword(project), required(args, "key"),
                args.optString("value", ""), args.optLong("ttlSeconds", 0)));
    }

    private JSONObject redisDelete(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.REDIS);
        permissions.require(project.id, PermissionStore.DB_WRITE);
        permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        return new JSONObject().put("ok", true).put("result", redis.delete(project,
                args.optInt("port", 6379), redisPassword(project), required(args, "key")));
    }

    private JSONObject cronList(Project project) {
        permissions.require(project.id, PermissionStore.SCHEDULE);
        JSONArray result = new JSONArray();
        for (CronManager.Task task : cron.list(project.id)) {
            result.put(new JSONObject().put("id", task.id).put("name", task.name)
                    .put("command", task.command).put("enabled", task.enabled)
                    .put("nextRunAt", task.nextRunAt).put("lastRunAt", task.lastRunAt)
                    .put("lastResult", truncate(task.lastResult, 4_000)));
        }
        return new JSONObject().put("ok", true).put("tasks", result);
    }

    private JSONObject cronSave(Project project, JSONObject args) {
        permissions.require(project.id, PermissionStore.SCHEDULE);
        permissions.require(project.id, PermissionStore.EXEC_COMMAND);
        JSONArray command = args.optJSONArray("command");
        if (command == null || command.length() == 0) throw new IllegalArgumentException("command is required");
        CronManager.Task task = new CronManager.Task();
        task.projectId = project.id;
        task.name = args.optString("name", "AI scheduled task");
        task.command = command;
        task.intervalMinutes = Math.max(1, args.optInt("intervalMinutes", 60));
        task.enabled = args.optBoolean("enabled", true);
        cron.save(task);
        return new JSONObject().put("ok", true).put("id", task.id).put("nextRunAt", task.nextRunAt);
    }

    private JSONObject cronRun(Project project, JSONObject args) {
        permissions.require(project.id, PermissionStore.SCHEDULE);
        permissions.require(project.id, PermissionStore.EXEC_COMMAND);
        String id = required(args, "taskId");
        CronManager.Task selected = null;
        for (CronManager.Task task : cron.list(project.id)) if (id.equals(task.id)) selected = task;
        if (selected == null) throw new IllegalArgumentException("Cron task not found");
        cron.runNow(id);
        return new JSONObject().put("ok", true).put("result", selected.lastResult);
    }

    private JSONObject buildInspect(Project project) {
        permissions.require(project.id, PermissionStore.BUILD);
        return new JSONObject().put("ok", true).put("project", builds.inspect(project));
    }

    private JSONObject buildAndroid(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.BUILD);
        permissions.require(project.id, PermissionStore.EXEC_COMMAND);
        ProcessSupervisor.Result result = builds.localAndroidBuild(project, args.optString("variant", "Debug"),
                args.optBoolean("bundle", false), args.optInt("timeoutSeconds", 1_800));
        return new JSONObject().put("ok", result.exitCode == 0 && !result.timedOut)
                .put("exitCode", result.exitCode).put("timedOut", result.timedOut)
                .put("output", truncate(result.output, MAX_TOOL_OUTPUT));
    }

    private OpenCodeServerClient openCodeClient(String profileId) {
        IntegrationStore.OpenCodeProfile selected = null;
        for (IntegrationStore.OpenCodeProfile profile : integrations.openCodeProfiles()) {
            if ((profileId == null || profileId.isEmpty()) && profile.enabled) { selected = profile; break; }
            if (profile.id.equals(profileId)) { selected = profile; break; }
        }
        if (selected == null) throw new IllegalArgumentException("OpenCode Server profile not found");
        OpenCodeServerClient.Profile profile = new OpenCodeServerClient.Profile();
        profile.name = selected.name; profile.baseUrl = selected.baseUrl; profile.username = selected.username;
        profile.password = selected.password; profile.directory = selected.directory;
        return new OpenCodeServerClient(profile);
    }

    private JSONObject openCodeHealth(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        permissions.require(project.id, PermissionStore.NETWORK);
        return new JSONObject().put("ok", true).put("health", openCodeClient(args.optString("profileId", "")).health());
    }

    private JSONObject openCodeSessions(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        permissions.require(project.id, PermissionStore.NETWORK);
        return new JSONObject().put("ok", true).put("sessions", openCodeClient(args.optString("profileId", "")).sessions());
    }

    private JSONObject openCodePrompt(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        permissions.require(project.id, PermissionStore.NETWORK);
        permissions.require(project.id, PermissionStore.WRITE_FILES);
        Object result = openCodeClient(args.optString("profileId", "")).prompt(required(args, "sessionId"),
                required(args, "text"), args.optString("agent", "build"),
                args.optString("providerId", ""), args.optString("modelId", ""));
        return new JSONObject().put("ok", true).put("result", result);
    }

    private McpClient mcpClient(String profileId) throws Exception {
        IntegrationStore.McpProfile selected = null;
        for (IntegrationStore.McpProfile profile : integrations.mcpProfiles()) {
            if ((profileId == null || profileId.isEmpty()) && profile.enabled) { selected = profile; break; }
            if (profile.id.equals(profileId)) { selected = profile; break; }
        }
        if (selected == null) throw new IllegalArgumentException("MCP profile not found");
        McpClient client = new McpClient(selected.url, McpClient.parseHeaders(selected.headersJson));
        client.initialize();
        return client;
    }

    private JSONObject mcpListTools(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        permissions.require(project.id, PermissionStore.NETWORK);
        return new JSONObject().put("ok", true).put("result", mcpClient(args.optString("profileId", "")).listTools());
    }

    private JSONObject mcpCallTool(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        permissions.require(project.id, PermissionStore.NETWORK);
        permissions.require(project.id, PermissionStore.DESTRUCTIVE);
        JSONObject arguments = args.optJSONObject("arguments");
        return new JSONObject().put("ok", true).put("result", mcpClient(args.optString("profileId", ""))
                .callTool(required(args, "tool"), arguments == null ? new JSONObject() : arguments));
    }

    private JSONObject lspStart(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        LspManager.Session session = lsp.start(project, required(args, "language"));
        return new JSONObject().put("ok", true).put("alive", session.process.isAlive());
    }

    private JSONObject lspRequest(Project project, JSONObject args) throws Exception {
        permissions.require(project.id, PermissionStore.INTEGRATIONS);
        String language = required(args, "language");
        LspManager.Session session = lsp.get(project, language);
        if (session == null || !session.process.isAlive()) session = lsp.start(project, language);
        JSONObject params = args.optJSONObject("params");
        return new JSONObject().put("ok", true).put("response", session.request(required(args, "method"),
                params == null ? new JSONObject() : params, args.optLong("timeoutMillis", 15_000)));
    }

    private DatabaseManager.DbInfo requireDatabase(Project project, String name) {
        DatabaseManager.DbInfo info = databases.find(project, name);
        if (info == null) throw new IllegalArgumentException("База не найдена: " + name);
        return info;
    }

    private String systemPrompt(Project project, AgentProfile profile) {
        return "Ты автономный Android coding agent внутри OpenCode Go Server Studio. "
                + "Роль: " + profile.title + ". " + profile.instruction + " "
                + "Работай только инструментами и только в корне выбранного проекта. "
                + "Сначала изучай структуру, затем выполняй минимально необходимые изменения, после этого проверяй результат. "
                + "Не выдумывай выполненные действия: каждый файл, SQL или запуск должен идти через инструмент. "
                + "Не проси пользователя запускать команды, если разрешение уже выдано и инструмент доступен. "
                + "Перед разрушительной операцией сделай backup, когда инструмент это позволяет. "
                + "Если runtime отсутствует, честно сообщи какой ARM64 Android runtime pack нужен.\n"
                + "Проект: " + project.name + "\n"
                + "Тип: " + project.type + "\n"
                + "Entry point: " + project.entryPoint + "\n"
                + "Разрешения: " + permissionsSummary(project.id);
    }

    private String permissionsSummary(String projectId) {
        StringBuilder result = new StringBuilder();
        for (String permission : new String[]{PermissionStore.READ_FILES, PermissionStore.WRITE_FILES,
                PermissionStore.SERVER, PermissionStore.DB_READ, PermissionStore.DB_WRITE,
                PermissionStore.EXEC_COMMAND, PermissionStore.DESTRUCTIVE, PermissionStore.READ_SECRETS,
                PermissionStore.NETWORK, PermissionStore.GIT, PermissionStore.REMOTE,
                PermissionStore.INTEGRATIONS, PermissionStore.BUILD, PermissionStore.REDIS,
                PermissionStore.SCHEDULE}) {
            if (result.length() > 0) result.append(", ");
            result.append(permission).append('=').append(permissions.allowed(projectId, permission));
        }
        return result.toString();
    }

    private JSONArray tools(AgentProfile profile) {
        JSONArray tools = new JSONArray();
        boolean readOnly = profile != null && profile.readOnly;
        tools.put(tool("project_info", "Информация о проекте, runtimes и базах", object(), required()));
        tools.put(tool("list_files", "Список файлов каталога проекта", object(
                property("path", "string", "Относительный путь; пусто означает корень")), required()));
        tools.put(tool("search_files", "Поиск текста и имён файлов по проекту", object(
                property("query", "string", "Строка поиска"),
                property("path", "string", "Каталог начала поиска"),
                property("limit", "integer", "Максимум результатов")), required("query")));
        tools.put(tool("read_file", "Чтение текстового файла с диапазоном символов", object(
                property("path", "string", "Относительный путь"),
                property("start", "integer", "Начальная позиция"),
                property("maxChars", "integer", "Максимум символов")), required("path")));
        if (!readOnly) {
            tools.put(tool("write_file", "Создать или полностью заменить файл", object(
                    property("path", "string", "Относительный путь"),
                    property("content", "string", "Полное содержимое")), required("path", "content")));
        }
        if (!readOnly) {
            tools.put(tool("patch_file", "Точная замена фрагмента в файле с проверкой количества совпадений", object(
                    property("path", "string", "Относительный путь"),
                    property("before", "string", "Точный старый фрагмент"),
                    property("after", "string", "Новый фрагмент"),
                    property("expectedOccurrences", "integer", "Ожидаемое количество совпадений")), required("path", "before", "after")));
        }
        if (!readOnly) {
            tools.put(tool("make_directory", "Создать каталог", object(
                    property("path", "string", "Относительный путь")), required("path")));
        }
        if (!readOnly) {
            tools.put(tool("move_path", "Переместить или переименовать файл/каталог", object(
                    property("source", "string", "Исходный путь"),
                    property("target", "string", "Новый путь")), required("source", "target")));
        }
        if (!readOnly) {
            tools.put(tool("delete_path", "Удалить файл или каталог; требует destructive permission", object(
                    property("path", "string", "Относительный путь")), required("path")));
        }
        tools.put(tool("runtime_status", "Статус PHP, Node, Python, Git, Composer, npm и DB runtimes", object(), required()));
        if (!readOnly) {
            tools.put(tool("run_command", "Запустить команду без shell-интерполяции", object(
                    arrayProperty("command", "string", "Массив: executable и аргументы"),
                    property("timeoutSeconds", "integer", "Тайм-аут 1-600 секунд")), required("command")));
        }
        if (!readOnly) {
            tools.put(tool("server_start", "Запустить сервер текущего проекта", object(
                    property("port", "integer", "Порт"),
                    property("lan", "boolean", "Доступ из локальной сети")), required()));
        }
        if (!readOnly) {
            tools.put(tool("server_stop", "Остановить сервер текущего проекта", object(), required()));
        }
        tools.put(tool("server_status", "Статус сервера", object(), required()));
        tools.put(tool("server_logs", "Последние строки логов сервера", object(
                property("limit", "integer", "Количество строк")), required()));
        tools.put(tool("database_list", "Список баз проекта без паролей", object(), required()));
        tools.put(tool("database_schema", "Схема SQLite базы", object(
                property("database", "string", "ID или имя базы")), required("database")));
        if (!readOnly) {
            tools.put(tool("database_query", "Выполнить SQL; write требует db_write, DROP/DELETE требует destructive", object(
                    property("database", "string", "ID или имя базы"),
                    property("sql", "string", "SQL запрос")), required("database", "sql")));
        }
        tools.put(tool("database_backup", "Создать резервную копию SQLite", object(
                property("database", "string", "ID или имя базы")), required("database")));
        tools.put(tool("audit_tail", "Последние события журнала действий", object(
                property("limit", "integer", "Количество событий")), required()));
        tools.put(tool("checkpoint_list", "Список автоматических контрольных точек файлов", object(
                property("limit", "integer", "Количество точек")), required()));
        if (!readOnly) {
            tools.put(tool("checkpoint_restore", "Восстановить файл или каталог из контрольной точки", object(
                    property("checkpointId", "string", "ID контрольной точки")), required("checkpointId")));
        }

        tools.put(tool("git_status", "Git status and branch", object(), required()));
        tools.put(tool("git_log", "Recent Git commits", object(
                property("count", "integer", "Number of commits")), required()));
        tools.put(tool("git_diff", "Git working tree or staged diff", object(
                property("staged", "boolean", "Read staged changes")), required()));
        if (!readOnly) {
            tools.put(tool("git_commit", "Stage all changes and create a commit", object(
                    property("message", "string", "Commit message")), required("message")));
            tools.put(tool("git_pull", "Pull a branch from a remote", object(
                    property("remote", "string", "Remote name"), property("branch", "string", "Branch")), required("branch")));
            tools.put(tool("git_push", "Push a branch to a remote", object(
                    property("remote", "string", "Remote name"), property("branch", "string", "Branch"),
                    property("setUpstream", "boolean", "Set upstream")), required("branch")));
            tools.put(tool("remote_ssh", "Run a command over key-based SSH", object(
                    property("host", "string", "Remote host"), property("port", "integer", "SSH port"),
                    property("user", "string", "Remote user"), property("command", "string", "Remote command")),
                    required("host", "user", "command")));
            tools.put(tool("remote_deploy", "Deploy project through rsync over SSH", object(
                    property("host", "string", "Remote host"), property("port", "integer", "SSH port"),
                    property("user", "string", "Remote user"), property("remotePath", "string", "Remote project path"),
                    property("delete", "boolean", "Delete remote files absent locally")), required("host", "user", "remotePath")));
        }
        tools.put(tool("redis_info", "Read Redis server information", object(property("port", "integer", "Redis port")), required()));
        tools.put(tool("redis_keys", "Scan Redis keys", object(property("port", "integer", "Redis port"),
                property("pattern", "string", "Glob pattern"), property("count", "integer", "Maximum keys")), required()));
        tools.put(tool("redis_get", "Read a Redis key", object(property("port", "integer", "Redis port"),
                property("key", "string", "Key")), required("key")));
        if (!readOnly) {
            tools.put(tool("redis_set", "Set a Redis key", object(property("port", "integer", "Redis port"),
                    property("key", "string", "Key"), property("value", "string", "Value"),
                    property("ttlSeconds", "integer", "Optional TTL")), required("key", "value")));
            tools.put(tool("redis_delete", "Delete a Redis key", object(property("port", "integer", "Redis port"),
                    property("key", "string", "Key")), required("key")));
        }
        tools.put(tool("cron_list", "List project scheduled tasks", object(), required()));
        if (!readOnly) {
            tools.put(tool("cron_save", "Create a periodic command task", object(
                    property("name", "string", "Task name"), arrayProperty("command", "string", "Executable and arguments"),
                    property("intervalMinutes", "integer", "Interval in minutes"), property("enabled", "boolean", "Enabled")), required("command")));
            tools.put(tool("cron_run", "Run a saved scheduled task immediately", object(
                    property("taskId", "string", "Task ID")), required("taskId")));
        }
        tools.put(tool("build_inspect", "Inspect Android build capability", object(), required()));
        if (!readOnly) tools.put(tool("build_android", "Run local Gradle Android build", object(
                property("variant", "string", "Build variant"), property("bundle", "boolean", "Build AAB instead of APK"),
                property("timeoutSeconds", "integer", "Timeout")), required()));
        tools.put(tool("opencode_health", "Check connected OpenCode Server", object(
                property("profileId", "string", "Optional profile ID")), required()));
        tools.put(tool("opencode_sessions", "List OpenCode Server sessions", object(
                property("profileId", "string", "Optional profile ID")), required()));
        if (!readOnly) tools.put(tool("opencode_prompt", "Send a coding task to OpenCode Server session", object(
                property("profileId", "string", "Optional profile ID"), property("sessionId", "string", "Session ID"),
                property("text", "string", "Task"), property("agent", "string", "OpenCode agent"),
                property("providerId", "string", "Provider ID"), property("modelId", "string", "Model ID")),
                required("sessionId", "text")));
        tools.put(tool("mcp_list_tools", "List tools from a configured MCP server", object(
                property("profileId", "string", "Optional MCP profile ID")), required()));
        if (!readOnly) tools.put(tool("mcp_call_tool", "Call an MCP tool; requires destructive approval", object(
                property("profileId", "string", "Optional MCP profile ID"), property("tool", "string", "Tool name"),
                property("arguments", "object", "Tool arguments")), required("tool")));
        tools.put(tool("lsp_start", "Start a language server for the project", object(
                property("language", "string", "typescript, python, php or java")), required("language")));
        tools.put(tool("lsp_request", "Send a JSON-RPC request to the language server", object(
                property("language", "string", "Language"), property("method", "string", "LSP method"),
                property("params", "object", "LSP parameters"), property("timeoutMillis", "integer", "Timeout")),
                required("language", "method")));
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject parameters, JSONArray required) {
        parameters.put("required", required).put("additionalProperties", false);
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description).put("parameters", parameters));
    }

    private static JSONObject object(JSONObject... properties) {
        JSONObject values = new JSONObject();
        for (JSONObject property : properties) {
            String name = property.optString("__name");
            property.remove("__name");
            values.put(name, property);
        }
        return new JSONObject().put("type", "object").put("properties", values);
    }

    private static JSONObject property(String name, String type, String description) {
        return new JSONObject().put("__name", name).put("type", type).put("description", description);
    }

    private static JSONObject arrayProperty(String name, String itemType, String description) {
        return new JSONObject().put("__name", name).put("type", "array")
                .put("items", new JSONObject().put("type", itemType)).put("description", description);
    }

    private static JSONArray required(String... names) { return new JSONArray(Arrays.asList(names)); }

    private void searchRecursive(Project project, File file, String query, JSONArray matches, int limit) throws Exception {
        if (matches.length() >= limit) return;
        if (file.isDirectory()) {
            if (file.getName().equals(".git") || file.getName().equals("node_modules")
                    || file.getName().equals("vendor") || file.getName().equals(".gradle")) return;
            File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                searchRecursive(project, child, query, matches, limit);
                if (matches.length() >= limit) return;
            }
            return;
        }
        String relative = relative(project, file);
        if (relative.toLowerCase(Locale.ROOT).contains(query)) {
            matches.put(new JSONObject().put("path", relative).put("match", "filename"));
            if (matches.length() >= limit) return;
        }
        if (file.length() > 2_000_000 || binaryFile(file)) return;
        String content;
        try { content = ProjectManager.read(file); } catch (Exception ignored) { return; }
        String lower = content.toLowerCase(Locale.ROOT);
        int position = lower.indexOf(query);
        if (position >= 0) {
            int start = Math.max(0, position - 120);
            int end = Math.min(content.length(), position + query.length() + 180);
            matches.put(new JSONObject().put("path", relative).put("match", "content")
                    .put("position", position).put("preview", content.substring(start, end)));
        }
    }

    private String resolveCommand(String executable) throws Exception {
        if (executable == null || executable.trim().isEmpty()) throw new IllegalArgumentException("Пустая команда");
        String command = executable.trim();
        if (command.contains(File.separator)) {
            File file = new File(command).getCanonicalFile();
            String runtimeRoot = runtimes.root().getCanonicalPath() + File.separator;
            if (!file.getPath().startsWith(runtimeRoot)
                    && !file.getPath().startsWith("/system/bin/")
                    && !file.getPath().startsWith("/system/xbin/")) {
                throw new SecurityException("Абсолютная команда разрешена только из runtime pack или /system/bin");
            }
            if (!file.isFile()) throw new IllegalArgumentException("Команда не найдена: " + command);
            return file.getAbsolutePath();
        }
        File direct = runtimes.executable(command);
        if (direct != null) return direct.getAbsolutePath();
        for (String runtime : RuntimeManager.SUPPORTED) {
            File candidate = runtimes.executableAny(runtime, command);
            if (candidate != null) return candidate.getAbsolutePath();
        }
        File system = new File("/system/bin", command);
        if (system.isFile()) return system.getAbsolutePath();
        throw new IllegalArgumentException("Команда не установлена: " + command);
    }

    private static boolean destructiveCommand(List<String> command) {
        if (command.isEmpty()) return false;
        String name = new File(command.get(0)).getName().toLowerCase(Locale.ROOT);
        if (name.equals("rm") || name.equals("rmdir") || name.equals("truncate")) return true;
        String joined = String.join(" ", command).toLowerCase(Locale.ROOT);
        return joined.contains(" reset --hard") || joined.contains(" clean -fd")
                || joined.contains(" drop database") || joined.contains(" delete from");
    }

    private static boolean secretPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.equals(".env") || lower.endsWith("/.env") || lower.contains("credentials")
                || lower.endsWith(".pem") || lower.endsWith(".key") || lower.contains("secret");
    }

    private static boolean binaryFile(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(png|jpe?g|gif|webp|ico|pdf|zip|apk|so|bin|db|sqlite|mp3|mp4|webm|woff2?|ttf)$");
    }

    private static int occurrences(String text, String target) {
        if (target.isEmpty()) throw new IllegalArgumentException("before не может быть пустым");
        int count = 0, index = 0;
        while ((index = text.indexOf(target, index)) >= 0) { count++; index += target.length(); }
        return count;
    }

    private static String auditArguments(String tool, JSONObject args) {
        if (args == null) return "{}";
        if ("write_file".equals(tool)) return "path=" + args.optString("path")
                + " chars=" + args.optString("content").length();
        if ("patch_file".equals(tool)) return "path=" + args.optString("path")
                + " beforeChars=" + args.optString("before").length()
                + " afterChars=" + args.optString("after").length();
        if ("database_query".equals(tool)) return "database=" + args.optString("database")
                + " sql=" + truncate(args.optString("sql").replaceAll("(?i)(PASSWORD\s+)[^\s;]+", "$1***"), 500);
        return truncate(args.toString(), 1_500);
    }

    private static String required(JSONObject args, String name) {
        String value = args.optString(name, "");
        if (value.isEmpty()) throw new IllegalArgumentException("Обязательный параметр: " + name);
        return value;
    }

    private static String relative(Project project, File file) throws Exception {
        return project.root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
        return truncate(message, 4_000);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
