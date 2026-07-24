package com.qandil.opencodego.database;

import android.content.Context;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Starts app-private MariaDB and PostgreSQL runtime processes when packs are installed. */
public final class DatabaseServerManager {
    public static final class Handle {
        public final String databaseId;
        public final String engine;
        public final int port;
        public final String processId;
        public final String socketPath;

        Handle(String databaseId, String engine, int port, String processId, String socketPath) {
            this.databaseId = databaseId;
            this.engine = engine;
            this.port = port;
            this.processId = processId;
            this.socketPath = socketPath;
        }
    }

    private static DatabaseServerManager instance;
    private final Context context;
    private final RuntimeManager runtimes;
    private final ProcessSupervisor processes;
    private final Map<String, Handle> handles = new LinkedHashMap<>();

    private DatabaseServerManager(Context context) {
        this.context = context.getApplicationContext();
        runtimes = RuntimeManager.get(context);
        processes = ProcessSupervisor.get(context);
    }

    public static synchronized DatabaseServerManager get(Context context) {
        if (instance == null) instance = new DatabaseServerManager(context.getApplicationContext());
        return instance;
    }

    public synchronized Handle start(Project project, DatabaseManager.DbInfo database) throws Exception {
        if (database == null || database.embedded()) throw new IllegalArgumentException("External database required");
        stop(database.id);
        Handle handle;
        if (DatabaseManager.MARIADB.equals(database.engine)) handle = startMariaDb(project, database);
        else if (DatabaseManager.POSTGRES.equals(database.engine)) handle = startPostgres(project, database);
        else throw new IllegalArgumentException("Unsupported engine: " + database.engine);
        handles.put(database.id, handle);
        AuditLog.get(context).append(project.id, "database_server", "start", database.toString(), true);
        return handle;
    }

    public synchronized void stop(String databaseId) {
        Handle handle = handles.remove(databaseId);
        if (handle != null) processes.stop(handle.processId, false);
    }

    public synchronized void stopAll() {
        for (Handle handle : new ArrayList<>(handles.values())) processes.stop(handle.processId, false);
        handles.clear();
    }

    public synchronized boolean any() {
        for (String id : new ArrayList<>(handles.keySet())) if (running(id)) return true;
        return false;
    }

    public synchronized boolean running(String databaseId) {
        Handle handle = handles.get(databaseId);
        if (handle == null) return false;
        ProcessSupervisor.Record record = processes.get(handle.processId);
        return record != null && record.running();
    }

    public synchronized Handle get(String databaseId) { return handles.get(databaseId); }

    public synchronized List<String> logs(String databaseId) {
        Handle handle = handles.get(databaseId);
        if (handle == null) return Collections.emptyList();
        ProcessSupervisor.Record record = processes.get(handle.processId);
        return record == null ? Collections.emptyList() : record.logs();
    }

    public JSONObject execute(Project project, DatabaseManager.DbInfo database, String sql) throws Exception {
        return execute(project, database, sql, false);
    }

    /** Uses a database-native read-only transaction for AI read access. */
    public JSONObject execute(
            Project project,
            DatabaseManager.DbInfo database,
            String sql,
            boolean readOnly) throws Exception {
        if (!running(database.id)) start(project, database);
        if (DatabaseManager.MARIADB.equals(database.engine)) return executeMariaDb(project, database, sql, readOnly);
        if (DatabaseManager.POSTGRES.equals(database.engine)) return executePostgres(project, database, sql, readOnly);
        throw new IllegalArgumentException("Unsupported engine");
    }

    private Handle startMariaDb(Project project, DatabaseManager.DbInfo database) throws Exception {
        if (!runtimes.installed("mariadb")) throw new IllegalStateException("Установите MariaDB ARM64 Runtime Pack");
        File daemon = requireExecutable("mariadb", "mariadbd", "mysqld");
        File installer = runtimes.executableAny("mariadb", "mariadb-install-db", "mysql_install_db");
        File data = new File(database.directory, "data");
        File socket = new File(database.directory, "mariadb.sock");
        File marker = new File(database.directory, ".initialized");
        data.mkdirs();
        if (!marker.exists()) {
            if (installer == null) throw new IllegalStateException("Runtime pack не содержит mariadb-install-db");
            List<String> command = Arrays.asList(
                    installer.getAbsolutePath(),
                    "--datadir=" + data.getAbsolutePath(),
                    "--auth-root-authentication-method=normal",
                    "--skip-test-db");
            ProcessSupervisor.Result init = processes.run(
                    project.id, "MariaDB init", command, database.directory, null, 180);
            if (init.exitCode != 0) throw new IllegalStateException("MariaDB init failed:\n" + init.output);
            ProjectManager.write(marker, "initialized");
        }
        List<String> command = Arrays.asList(
                daemon.getAbsolutePath(),
                "--datadir=" + data.getAbsolutePath(),
                "--port=" + database.port,
                "--bind-address=127.0.0.1",
                "--socket=" + socket.getAbsolutePath(),
                "--pid-file=" + new File(database.directory, "mariadb.pid").getAbsolutePath(),
                "--log-error=" + new File(database.directory, "mariadb-error.log").getAbsolutePath(),
                "--skip-name-resolve",
                "--max-connections=30");
        ProcessSupervisor.Record record = processes.start(
                project.id, "MariaDB " + database.name, command, database.directory, null);
        waitPort(database.port, record, 30_000);
        Handle handle = new Handle(database.id, database.engine, database.port, record.id, socket.getAbsolutePath());
        handles.put(database.id, handle);
        File provisioned = new File(database.directory, ".provisioned");
        if (!provisioned.exists()) {
            provisionMariaDb(project, database, socket);
            ProjectManager.write(provisioned, "provisioned");
        }
        return handle;
    }

    private void provisionMariaDb(Project project, DatabaseManager.DbInfo database, File socket) throws Exception {
        File client = requireExecutable("mariadb", "mariadb", "mysql");
        String sql = "CREATE DATABASE IF NOT EXISTS " + mysqlIdentifier(database.name)
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
                + "CREATE USER IF NOT EXISTS " + mysqlString(database.user) + "@'127.0.0.1' IDENTIFIED BY " + mysqlString(database.password) + ";"
                + "CREATE USER IF NOT EXISTS " + mysqlString(database.user) + "@'localhost' IDENTIFIED BY " + mysqlString(database.password) + ";"
                + "GRANT ALL PRIVILEGES ON " + mysqlIdentifier(database.name) + ".* TO " + mysqlString(database.user) + "@'127.0.0.1';"
                + "GRANT ALL PRIVILEGES ON " + mysqlIdentifier(database.name) + ".* TO " + mysqlString(database.user) + "@'localhost';"
                + "FLUSH PRIVILEGES;";
        List<String> command = Arrays.asList(
                client.getAbsolutePath(), "--protocol=socket", "--socket=" + socket.getAbsolutePath(),
                "-u", "root", "-e", sql);
        ProcessSupervisor.Result result = processes.run(project.id, "MariaDB provision", command, database.directory, null, 60);
        if (result.exitCode != 0) throw new IllegalStateException("MariaDB provision failed:\n" + result.output);
    }

    private JSONObject executeMariaDb(
            Project project,
            DatabaseManager.DbInfo database,
            String sql,
            boolean readOnly) throws Exception {
        File client = requireExecutable("mariadb", "mariadb", "mysql");
        String effectiveSql = readOnly
                ? "START TRANSACTION READ ONLY; " + sql + "; ROLLBACK;"
                : sql;
        List<String> command = Arrays.asList(
                client.getAbsolutePath(),
                "--protocol=tcp", "--host=127.0.0.1", "--port=" + database.port,
                "--user=" + database.user, "--batch", "--raw", database.name, "-e", effectiveSql);
        Map<String, String> environment = new HashMap<>();
        environment.put("MYSQL_PWD", database.password);
        ProcessSupervisor.Result result = processes.run(project.id, "MariaDB SQL", command, database.directory, environment, 180);
        if (result.exitCode != 0) throw new IllegalStateException(result.output);
        return parseDelimited(result.output, '\t');
    }

    private Handle startPostgres(Project project, DatabaseManager.DbInfo database) throws Exception {
        if (!runtimes.installed("postgres")) throw new IllegalStateException("Установите PostgreSQL ARM64 Runtime Pack");
        File postgres = requireExecutable("postgres", "postgres");
        File initdb = requireExecutable("postgres", "initdb");
        File data = new File(database.directory, "data");
        data.mkdirs();
        if (!new File(data, "PG_VERSION").exists()) {
            List<String> command = Arrays.asList(
                    initdb.getAbsolutePath(), "-D", data.getAbsolutePath(),
                    "--auth=trust", "--encoding=UTF8", "--no-locale");
            ProcessSupervisor.Result init = processes.run(
                    project.id, "PostgreSQL initdb", command, database.directory, null, 180);
            if (init.exitCode != 0) throw new IllegalStateException("initdb failed:\n" + init.output);
        }
        List<String> command = Arrays.asList(
                postgres.getAbsolutePath(), "-D", data.getAbsolutePath(),
                "-p", String.valueOf(database.port), "-h", "127.0.0.1",
                "-k", database.directory.getAbsolutePath(),
                "-c", "max_connections=30",
                "-c", "shared_buffers=32MB");
        ProcessSupervisor.Record record = processes.start(
                project.id, "PostgreSQL " + database.name, command, database.directory, null);
        waitPort(database.port, record, 30_000);
        Handle handle = new Handle(database.id, database.engine, database.port, record.id, database.directory.getAbsolutePath());
        handles.put(database.id, handle);
        File provisioned = new File(database.directory, ".provisioned");
        if (!provisioned.exists()) {
            provisionPostgres(project, database);
            ProjectManager.write(provisioned, "provisioned");
        }
        return handle;
    }

    private void provisionPostgres(Project project, DatabaseManager.DbInfo database) throws Exception {
        File psql = requireExecutable("postgres", "psql");
        String sql = "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname=" + pgString(database.user)
                + ") THEN CREATE ROLE " + pgIdentifier(database.user) + " LOGIN PASSWORD " + pgString(database.password)
                + "; END IF; END $$;";
        ProcessSupervisor.Result role = processes.run(project.id, "PostgreSQL role",
                Arrays.asList(psql.getAbsolutePath(), "-h", "127.0.0.1", "-p", String.valueOf(database.port),
                        "-d", "postgres", "-c", sql), database.directory, null, 60);
        if (role.exitCode != 0) throw new IllegalStateException(role.output);
        String existsSql = "SELECT 1 FROM pg_database WHERE datname=" + pgString(database.name);
        ProcessSupervisor.Result exists = processes.run(project.id, "PostgreSQL database check",
                Arrays.asList(psql.getAbsolutePath(), "-h", "127.0.0.1", "-p", String.valueOf(database.port),
                        "-d", "postgres", "-At", "-c", existsSql), database.directory, null, 60);
        if (!exists.output.contains("1")) {
            ProcessSupervisor.Result create = processes.run(project.id, "PostgreSQL create database",
                    Arrays.asList(psql.getAbsolutePath(), "-h", "127.0.0.1", "-p", String.valueOf(database.port),
                            "-d", "postgres", "-c", "CREATE DATABASE " + pgIdentifier(database.name)
                            + " OWNER " + pgIdentifier(database.user)), database.directory, null, 60);
            if (create.exitCode != 0) throw new IllegalStateException(create.output);
        }
    }

    private JSONObject executePostgres(
            Project project,
            DatabaseManager.DbInfo database,
            String sql,
            boolean readOnly) throws Exception {
        File psql = requireExecutable("postgres", "psql");
        Map<String, String> environment = new HashMap<>();
        environment.put("PGPASSWORD", database.password);
        String effectiveSql = readOnly ? "BEGIN READ ONLY; " + sql + "; ROLLBACK;" : sql;
        List<String> command = Arrays.asList(
                psql.getAbsolutePath(), "-q", "-v", "ON_ERROR_STOP=1",
                "-h", "127.0.0.1", "-p", String.valueOf(database.port),
                "-U", database.user, "-d", database.name, "--csv", "-c", effectiveSql);
        ProcessSupervisor.Result result = processes.run(project.id, "PostgreSQL SQL", command, database.directory, environment, 180);
        if (result.exitCode != 0) throw new IllegalStateException(result.output);
        return parseCsv(result.output);
    }

    public File backup(Project project, DatabaseManager.DbInfo database) throws Exception {
        if (database == null || database.embedded()) throw new IllegalArgumentException("External database required");
        if (!running(database.id)) start(project, database);
        File directory = new File(project.root, "backups/databases");
        directory.mkdirs();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        File target = new File(directory, database.name + "-" + stamp + ".sql");
        if (DatabaseManager.MARIADB.equals(database.engine)) {
            File dump = requireExecutable("mariadb", "mariadb-dump", "mysqldump");
            Map<String, String> environment = new HashMap<>();
            environment.put("MYSQL_PWD", database.password);
            List<String> command = Arrays.asList(
                    dump.getAbsolutePath(), "--protocol=tcp", "--host=127.0.0.1",
                    "--port=" + database.port, "--user=" + database.user,
                    "--single-transaction", "--routines", "--events", "--triggers",
                    "--result-file=" + target.getAbsolutePath(), database.name);
            ProcessSupervisor.Result result = processes.run(project.id, "MariaDB backup", command,
                    database.directory, environment, 300);
            if (result.exitCode != 0) throw new IllegalStateException(result.output);
        } else if (DatabaseManager.POSTGRES.equals(database.engine)) {
            File dump = requireExecutable("postgres", "pg_dump");
            Map<String, String> environment = new HashMap<>();
            environment.put("PGPASSWORD", database.password);
            List<String> command = Arrays.asList(
                    dump.getAbsolutePath(), "-h", "127.0.0.1", "-p", String.valueOf(database.port),
                    "-U", database.user, "-d", database.name, "--clean", "--if-exists",
                    "--no-owner", "--no-privileges", "--file", target.getAbsolutePath());
            ProcessSupervisor.Result result = processes.run(project.id, "PostgreSQL backup", command,
                    database.directory, environment, 300);
            if (result.exitCode != 0) throw new IllegalStateException(result.output);
        } else throw new IllegalArgumentException("Unsupported engine");
        if (!target.isFile()) throw new IllegalStateException("Database dump was not created");
        AuditLog.get(context).append(project.id, "database", "backup", target.getName(), true);
        return target;
    }

    public void restore(Project project, DatabaseManager.DbInfo database, InputStream input) throws Exception {
        if (database == null || database.embedded()) throw new IllegalArgumentException("External database required");
        File safetyBackup = backup(project, database);
        File temporary = new File(database.directory, ".restore-" + System.currentTimeMillis() + ".sql");
        try (FileOutputStream output = new FileOutputStream(temporary)) { copy(input, output); }
        try {
            if (DatabaseManager.MARIADB.equals(database.engine)) {
                File client = requireExecutable("mariadb", "mariadb", "mysql");
                Map<String, String> environment = new HashMap<>();
                environment.put("MYSQL_PWD", database.password);
                String source = "source " + temporary.getAbsolutePath().replace("\\", "\\\\").replace("'", "\\'");
                ProcessSupervisor.Result result = processes.run(project.id, "MariaDB restore",
                        Arrays.asList(client.getAbsolutePath(), "--protocol=tcp", "--host=127.0.0.1",
                                "--port=" + database.port, "--user=" + database.user, database.name,
                                "-e", source), database.directory, environment, 600);
                if (result.exitCode != 0) throw new IllegalStateException(result.output);
            } else if (DatabaseManager.POSTGRES.equals(database.engine)) {
                File psql = requireExecutable("postgres", "psql");
                Map<String, String> environment = new HashMap<>();
                environment.put("PGPASSWORD", database.password);
                ProcessSupervisor.Result result = processes.run(project.id, "PostgreSQL restore",
                        Arrays.asList(psql.getAbsolutePath(), "-h", "127.0.0.1", "-p", String.valueOf(database.port),
                                "-U", database.user, "-d", database.name, "-v", "ON_ERROR_STOP=1",
                                "-f", temporary.getAbsolutePath()), database.directory, environment, 600);
                if (result.exitCode != 0) throw new IllegalStateException(result.output);
            } else throw new IllegalArgumentException("Unsupported engine");
            AuditLog.get(context).append(project.id, "database", "restore",
                    database.name + " safety=" + safetyBackup.getName(), true);
        } finally {
            temporary.delete();
        }
    }

    public void exportSql(Project project, DatabaseManager.DbInfo database, OutputStream output) throws Exception {
        File dump = backup(project, database);
        try (java.io.FileInputStream input = new java.io.FileInputStream(dump)) { copy(input, output); }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
    }

    private File requireExecutable(String runtime, String... names) {
        File file = runtimes.executableAny(runtime, names);
        if (file == null) throw new IllegalStateException(runtime + " runtime не содержит " + String.join("/", names));
        return file;
    }

    private static void waitPort(int port, ProcessSupervisor.Record record, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!record.running()) throw new IllegalStateException("Database process exited:\n" + String.join("\n", record.logs()));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (Exception error) { last = error; Thread.sleep(250); }
        }
        throw new IllegalStateException("Database did not open port " + port + (last == null ? "" : ": " + last.getMessage()));
    }

    private static JSONObject parseDelimited(String output, char delimiter) throws Exception {
        String[] lines = cleanProcessOutput(output).split("\\r?\\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) return new JSONObject().put("ok", true).put("raw", output);
        JSONArray columns = new JSONArray();
        for (String column : lines[0].split(String.valueOf(delimiter), -1)) columns.put(column);
        JSONArray rows = new JSONArray();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            JSONArray row = new JSONArray();
            for (String value : lines[i].split(String.valueOf(delimiter), -1)) row.put(value);
            rows.put(row);
        }
        return new JSONObject().put("ok", true).put("columns", columns).put("rows", rows).put("count", rows.length());
    }

    private static JSONObject parseCsv(String output) throws Exception {
        List<List<String>> parsed = csv(cleanProcessOutput(output));
        if (parsed.isEmpty()) return new JSONObject().put("ok", true).put("raw", output);
        JSONArray columns = new JSONArray(parsed.get(0));
        JSONArray rows = new JSONArray();
        for (int i = 1; i < parsed.size(); i++) rows.put(new JSONArray(parsed.get(i)));
        return new JSONObject().put("ok", true).put("columns", columns).put("rows", rows).put("count", rows.length());
    }

    private static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                else if (c == '"') quoted = false;
                else value.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(value.toString()); value.setLength(0); }
            else if (c == '\n') { row.add(value.toString()); value.setLength(0); rows.add(row); row = new ArrayList<>(); }
            else if (c != '\r') value.append(c);
        }
        if (value.length() > 0 || !row.isEmpty()) { row.add(value.toString()); rows.add(row); }
        return rows;
    }

    private static String cleanProcessOutput(String output) {
        StringBuilder result = new StringBuilder();
        for (String line : output.split("\\r?\\n")) {
            if (line.contains(" START ") || line.matches(".* EXIT -?\\d+$") || line.endsWith(" STOP")) continue;
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        return result.toString().trim();
    }

    private static String mysqlIdentifier(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String mysqlString(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"; }
    private static String pgIdentifier(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String pgString(String value) { return "'" + value.replace("'", "''") + "'"; }
}
