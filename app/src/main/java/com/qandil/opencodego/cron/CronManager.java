package com.qandil.opencodego.cron;

import android.content.Context;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Persistent app-level scheduler for project commands. */
public final class CronManager {
    public static final String EVERY_MINUTES = "every_minutes";
    public static final String DAILY = "daily";

    public static final class Task {
        public String id;
        public String projectId;
        public String name;
        public String scheduleType;
        public int intervalMinutes;
        public int hour;
        public int minute;
        public JSONArray command;
        public boolean enabled;
        public long lastRunAt;
        public long nextRunAt;
        public String lastResult;

        public Task() {
            id = UUID.randomUUID().toString();
            name = "Scheduled task";
            scheduleType = EVERY_MINUTES;
            intervalMinutes = 60;
            command = new JSONArray();
            enabled = true;
            lastResult = "";
        }

        JSONObject json() {
            return new JSONObject().put("id", id).put("projectId", projectId).put("name", name)
                    .put("scheduleType", scheduleType).put("intervalMinutes", intervalMinutes)
                    .put("hour", hour).put("minute", minute).put("command", command)
                    .put("enabled", enabled).put("lastRunAt", lastRunAt).put("nextRunAt", nextRunAt)
                    .put("lastResult", lastResult);
        }

        public List<String> commandList() {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < command.length(); i++) result.add(command.optString(i, ""));
            return result;
        }
    }

    private static CronManager instance;
    private final Context context;
    private final File file;

    private CronManager(Context context) {
        this.context = context.getApplicationContext();
        file = new File(context.getFilesDir(), "cron-tasks.json");
    }

    public static synchronized CronManager get(Context context) {
        if (instance == null) instance = new CronManager(context.getApplicationContext());
        return instance;
    }

    public synchronized List<Task> list(String projectId) {
        List<Task> result = new ArrayList<>();
        JSONArray array = readArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            Task task = from(value);
            if (projectId == null || projectId.equals(task.projectId)) result.add(task);
        }
        return result;
    }

    public synchronized void save(Task task) {
        if (task.projectId == null || task.projectId.isEmpty()) throw new IllegalArgumentException("Project is required");
        if (task.command == null || task.command.length() == 0) throw new IllegalArgumentException("Command is empty");
        if (EVERY_MINUTES.equals(task.scheduleType)) task.intervalMinutes = Math.max(1, task.intervalMinutes);
        task.hour = Math.max(0, Math.min(23, task.hour));
        task.minute = Math.max(0, Math.min(59, task.minute));
        task.nextRunAt = computeNext(task, System.currentTimeMillis());
        List<Task> tasks = list(null);
        boolean replaced = false;
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id.equals(task.id)) {
            tasks.set(i, task); replaced = true; break;
        }
        if (!replaced) tasks.add(task);
        write(tasks);
    }

    public synchronized void delete(String id) {
        List<Task> updated = new ArrayList<>();
        for (Task task : list(null)) if (!task.id.equals(id)) updated.add(task);
        write(updated);
    }

    public boolean hasEnabled() {
        for (Task task : list(null)) if (task.enabled) return true;
        return false;
    }

    public void runDue() {
        long now = System.currentTimeMillis();
        for (Task task : list(null)) {
            if (!task.enabled || task.nextRunAt > now) continue;
            runOne(task, now);
        }
    }

    public void runNow(String id) {
        for (Task task : list(null)) if (task.id.equals(id)) {
            runOne(task, System.currentTimeMillis());
            return;
        }
        throw new IllegalArgumentException("Task not found");
    }

    private void runOne(Task task, long now) {
        Project project = ProjectManager.get(context).find(task.projectId);
        if (project == null) {
            task.lastResult = "Project not found";
            task.lastRunAt = now;
            task.nextRunAt = computeNext(task, now + 60_000L);
            saveQuiet(task);
            return;
        }
        try {
            List<String> command = task.commandList();
            validateCommand(command);
            ProcessSupervisor.Result result = ProcessSupervisor.get(context).run(project.id,
                    "Cron · " + task.name, command, project.root, Collections.emptyMap(), 900);
            task.lastResult = (result.timedOut ? "TIMEOUT\n" : "exit=" + result.exitCode + "\n") + result.output;
            AuditLog.get(context).append(project.id, "cron", task.name, task.lastResult, result.exitCode == 0);
        } catch (Exception error) {
            task.lastResult = "ERROR: " + error.getMessage();
            AuditLog.get(context).append(project.id, "cron", task.name, task.lastResult, false);
        }
        task.lastRunAt = now;
        task.nextRunAt = computeNext(task, now + 1_000L);
        saveQuiet(task);
    }

    private synchronized void saveQuiet(Task task) {
        try { save(task); } catch (Exception ignored) {}
    }

    private static long computeNext(Task task, long from) {
        if (DAILY.equals(task.scheduleType)) {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTimeInMillis(from);
            calendar.set(java.util.Calendar.HOUR_OF_DAY, task.hour);
            calendar.set(java.util.Calendar.MINUTE, task.minute);
            calendar.set(java.util.Calendar.SECOND, 0);
            calendar.set(java.util.Calendar.MILLISECOND, 0);
            if (calendar.getTimeInMillis() <= from) calendar.add(java.util.Calendar.DAY_OF_YEAR, 1);
            return calendar.getTimeInMillis();
        }
        return from + Math.max(1, task.intervalMinutes) * 60_000L;
    }

    public static JSONArray parseCommandLine(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dual = false, escape = false;
        for (int i = 0; i < (value == null ? 0 : value.length()); i++) {
            char c = value.charAt(i);
            if (escape) { current.append(c); escape = false; continue; }
            if (c == '\\' && !single) { escape = true; continue; }
            if (c == '\'' && !dual) { single = !single; continue; }
            if (c == '"' && !single) { dual = !dual; continue; }
            if (Character.isWhitespace(c) && !single && !dual) {
                if (current.length() > 0) { parts.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (escape || single || dual) throw new IllegalArgumentException("Unclosed quote or escape");
        if (current.length() > 0) parts.add(current.toString());
        return new JSONArray(parts);
    }

    private static void validateCommand(List<String> command) {
        if (command.isEmpty()) throw new IllegalArgumentException("Empty command");
        String executable = command.get(0);
        if (executable == null || executable.isEmpty() || executable.contains("\n") || executable.contains("\r")) {
            throw new IllegalArgumentException("Invalid executable");
        }
    }

    private JSONArray readArray() {
        try { return file.isFile() ? new JSONArray(ProjectManager.read(file)) : new JSONArray(); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void write(List<Task> tasks) {
        JSONArray array = new JSONArray();
        for (Task task : tasks) array.put(task.json());
        try { ProjectManager.write(file, array.toString(2)); }
        catch (Exception error) { throw new IllegalStateException("Unable to save cron tasks", error); }
    }

    private static Task from(JSONObject value) {
        Task task = new Task();
        task.id = value.optString("id", task.id);
        task.projectId = value.optString("projectId", "");
        task.name = value.optString("name", task.name);
        task.scheduleType = value.optString("scheduleType", EVERY_MINUTES);
        task.intervalMinutes = value.optInt("intervalMinutes", 60);
        task.hour = value.optInt("hour", 0);
        task.minute = value.optInt("minute", 0);
        task.command = value.optJSONArray("command");
        if (task.command == null) task.command = new JSONArray();
        task.enabled = value.optBoolean("enabled", true);
        task.lastRunAt = value.optLong("lastRunAt", 0L);
        task.nextRunAt = value.optLong("nextRunAt", computeNext(task, System.currentTimeMillis()));
        task.lastResult = value.optString("lastResult", "");
        return task;
    }
}
