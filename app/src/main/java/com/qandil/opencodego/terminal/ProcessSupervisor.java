package com.qandil.opencodego.terminal;

import android.content.Context;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.runtime.RuntimeManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Starts and supervises long-running app-private native processes. */
public final class ProcessSupervisor {
    public static final class Result {
        public final int exitCode;
        public final boolean timedOut;
        public final String output;
        Result(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
        }
    }

    public static final class Record {
        public final String id;
        public final String projectId;
        public final String label;
        public final List<String> command;
        public final long startedAt;
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private Process process;

        Record(String id, String projectId, String label, List<String> command) {
            this.id = id;
            this.projectId = projectId;
            this.label = label;
            this.command = new ArrayList<>(command);
            this.startedAt = System.currentTimeMillis();
        }

        public boolean running() { return process != null && process.isAlive(); }
        public List<String> logs() {
            synchronized (logs) { return new ArrayList<>(logs); }
        }
        void append(String line) {
            logs.add(line);
            while (logs.size() > 2_000) logs.remove(0);
        }
    }

    private static ProcessSupervisor instance;
    private final Context context;
    private final RuntimeManager runtimes;
    private final Map<String, Record> records = new LinkedHashMap<>();

    private ProcessSupervisor(Context context) {
        this.context = context.getApplicationContext();
        runtimes = RuntimeManager.get(context);
    }

    public static synchronized ProcessSupervisor get(Context context) {
        if (instance == null) instance = new ProcessSupervisor(context.getApplicationContext());
        return instance;
    }

    public synchronized Record start(
            String projectId,
            String label,
            List<String> command,
            File workingDirectory,
            Map<String, String> additionalEnvironment) throws Exception {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Empty command");
        String id = UUID.randomUUID().toString();
        Record record = new Record(id, projectId, label, command);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.putAll(runtimes.environment(workingDirectory));
        if (additionalEnvironment != null) environment.putAll(additionalEnvironment);
        record.process = builder.start();
        records.put(id, record);
        record.append(new Date() + " START " + printable(command));
        Thread reader = new Thread(() -> readOutput(record), "process-" + label + "-" + id);
        reader.setDaemon(true);
        reader.start();
        AuditLog.get(context).append(projectId, "process", "start", label + " · " + printable(command), true);
        return record;
    }

    public Result run(
            String projectId,
            String label,
            List<String> command,
            File workingDirectory,
            Map<String, String> additionalEnvironment,
            long timeoutSeconds) throws Exception {
        Record record = start(projectId, label, command, workingDirectory, additionalEnvironment);
        boolean completed = record.process.waitFor(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS);
        if (!completed) {
            stop(record.id, true);
            return new Result(-1, true, join(record.logs()));
        }
        int exit = record.process.exitValue();
        AuditLog.get(context).append(projectId, "process", "finish", label + " exit=" + exit, exit == 0);
        return new Result(exit, false, join(record.logs()));
    }

    public synchronized boolean stop(String id, boolean force) {
        Record record = records.get(id);
        if (record == null || record.process == null) return false;
        if (force) record.process.destroyForcibly(); else record.process.destroy();
        record.append(new Date() + " STOP");
        AuditLog.get(context).append(record.projectId, "process", "stop", record.label, true);
        return true;
    }

    public synchronized void stopProject(String projectId) {
        for (Record record : new ArrayList<>(records.values())) {
            if (projectId != null && projectId.equals(record.projectId) && record.running()) stop(record.id, false);
        }
    }

    public synchronized void stopAll() {
        for (Record record : new ArrayList<>(records.values())) {
            if (record.running()) stop(record.id, false);
        }
    }

    public synchronized Record get(String id) { return records.get(id); }

    public synchronized List<Record> list(String projectId) {
        List<Record> result = new ArrayList<>();
        for (Record record : records.values()) {
            if (projectId == null || projectId.equals(record.projectId)) result.add(record);
        }
        return result;
    }

    public synchronized void prune() {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, Record> entry : records.entrySet()) {
            if (!entry.getValue().running() && records.size() - remove.size() > 100) remove.add(entry.getKey());
        }
        for (String id : remove) records.remove(id);
    }

    private void readOutput(Record record) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                record.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) record.append(line);
        } catch (Exception error) {
            record.append("[reader] " + error.getMessage());
        } finally {
            try {
                int exit = record.process.waitFor();
                record.append(new Date() + " EXIT " + exit);
            } catch (Exception ignored) {}
        }
    }

    private static String printable(List<String> command) {
        StringBuilder result = new StringBuilder();
        for (String item : command) {
            if (result.length() > 0) result.append(' ');
            if (item.matches("[A-Za-z0-9_./:=@+-]+")) result.append(item);
            else result.append('\'').append(item.replace("'", "'\\''")).append('\'');
        }
        return result.toString();
    }

    private static String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) result.append('\n');
            result.append(line);
            if (result.length() > 256_000) {
                result.append("\n… output truncated");
                break;
            }
        }
        return result.toString();
    }
}
