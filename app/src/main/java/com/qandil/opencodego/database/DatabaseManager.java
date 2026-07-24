package com.qandil.opencodego.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.security.SecureStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Database Hosting Center. SQLite is embedded; MariaDB/PostgreSQL profiles use runtime packs. */
public final class DatabaseManager {
    public static final String SQLITE = "sqlite";
    public static final String MARIADB = "mariadb";
    public static final String POSTGRES = "postgres";

    public static final class DbInfo {
        public final String id;
        public final String name;
        public final String engine;
        public final String user;
        public final String password;
        public final String host;
        public final int port;
        public final File file;
        public final File directory;
        public final long createdAt;

        public DbInfo(
                String id,
                String name,
                String engine,
                String user,
                String password,
                String host,
                int port,
                File file,
                File directory,
                long createdAt) {
            this.id = id;
            this.name = name;
            this.engine = engine;
            this.user = user;
            this.password = password;
            this.host = host;
            this.port = port;
            this.file = file;
            this.directory = directory;
            this.createdAt = createdAt;
        }

        public boolean embedded() { return SQLITE.equals(engine); }
        @Override public String toString() { return name + " · " + engine.toUpperCase(Locale.ROOT); }
    }

    public static final class ColumnInfo {
        public final int position;
        public final String name;
        public final String type;
        public final boolean notNull;
        public final String defaultValue;
        public final boolean primaryKey;

        ColumnInfo(int position, String name, String type, boolean notNull, String defaultValue, boolean primaryKey) {
            this.position = position;
            this.name = name;
            this.type = type;
            this.notNull = notNull;
            this.defaultValue = defaultValue;
            this.primaryKey = primaryKey;
        }
    }

    private static DatabaseManager instance;
    private final Context context;
    private final SecureStore secureStore;

    private DatabaseManager(Context context) {
        this.context = context.getApplicationContext();
        secureStore = new SecureStore(context);
    }

    public static synchronized DatabaseManager get(Context context) {
        if (instance == null) instance = new DatabaseManager(context.getApplicationContext());
        return instance;
    }

    public synchronized DbInfo create(Project project, String name) throws Exception {
        return create(project, name, SQLITE, "127.0.0.1", 0);
    }

    public synchronized DbInfo create(
            Project project,
            String name,
            String engine,
            String host,
            int port) throws Exception {
        String normalizedEngine = normalizeEngine(engine);
        String safeName = safeIdentifier(name, "database");
        String id = safeName + "-" + System.currentTimeMillis();
        File databases = databasesDirectory(project);
        File directory = new File(databases, id);
        if (!directory.mkdirs()) throw new IllegalStateException("Не удалось создать каталог базы");
        File databaseFile = SQLITE.equals(normalizedEngine) ? new File(directory, safeName + ".db") : null;
        if (databaseFile != null) {
            SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
            database.execSQL("PRAGMA foreign_keys=ON");
            database.execSQL("CREATE TABLE IF NOT EXISTS _opencode_meta (key TEXT PRIMARY KEY, value TEXT)");
            database.execSQL("INSERT OR REPLACE INTO _opencode_meta(key,value) VALUES('created_at',?)",
                    new Object[]{String.valueOf(System.currentTimeMillis())});
            database.close();
        }
        String user = safeIdentifier(safeName + "_user", "app_user");
        String password = randomPassword(24);
        String secretKey = secretKey(project.id, id);
        secureStore.put(secretKey, password);
        long createdAt = System.currentTimeMillis();
        JSONObject profile = new JSONObject()
                .put("id", id)
                .put("name", safeName)
                .put("engine", normalizedEngine)
                .put("user", user)
                .put("secretRef", secretKey)
                .put("host", host == null || host.trim().isEmpty() ? "127.0.0.1" : host.trim())
                .put("port", port > 0 ? port : nextPort(project, normalizedEngine))
                .put("createdAt", createdAt)
                .put("file", databaseFile == null ? JSONObject.NULL : databaseFile.getName())
                .put("state", SQLITE.equals(normalizedEngine) ? "ready" : "runtime_required");
        ProjectManager.write(new File(directory, "database.json"), profile.toString(2));
        DbInfo info = fromProfile(directory, profile);
        AuditLog.get(context).append(project.id, "database", "create", info.toString(), true);
        return info;
    }

    public synchronized List<DbInfo> list(Project project) {
        List<DbInfo> result = new ArrayList<>();
        File[] directories = databasesDirectory(project).listFiles(File::isDirectory);
        if (directories == null) return result;
        Arrays.sort(directories, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File directory : directories) {
            try {
                File profileFile = new File(directory, "database.json");
                if (!profileFile.isFile()) continue;
                result.add(fromProfile(directory, new JSONObject(ProjectManager.read(profileFile))));
            } catch (Exception ignored) {}
        }
        migrateLegacy(project, result);
        return result;
    }

    public synchronized DbInfo find(Project project, String idOrName) {
        if (idOrName == null) return null;
        for (DbInfo info : list(project)) {
            if (idOrName.equals(info.id) || idOrName.equals(info.name)) return info;
        }
        return null;
    }

    public synchronized void delete(Project project, DbInfo info) throws Exception {
        if (info == null) return;
        if (!info.embedded()) DatabaseServerManager.get(context).stop(info.id);
        ProjectManager.deleteRecursively(info.directory);
        secureStore.remove(secretKey(project.id, info.id));
        AuditLog.get(context).append(project.id, "database", "delete", info.toString(), true);
    }

    public JSONObject execute(Project project, DbInfo info, String sql) throws Exception {
        return execute(project, info, sql, false);
    }

    /** Executes SQL with an optional DB-enforced read-only connection/transaction. */
    public JSONObject execute(Project project, DbInfo info, String sql, boolean readOnly) throws Exception {
        if (info == null) throw new IllegalArgumentException("База не выбрана");
        String query = sql == null ? "" : sql.trim();
        if (query.isEmpty()) throw new IllegalArgumentException("SQL запрос пуст");
        if (readOnly && !SqlSafety.isSingleReadQuery(query)) {
            throw new SecurityException("Read-only SQL channel rejected a non-read query");
        }
        if (!SQLITE.equals(info.engine)) {
            return DatabaseServerManager.get(context).execute(project, info, query, readOnly);
        }
        int flags = readOnly ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.OPEN_READWRITE;
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, flags);
        if (!readOnly) database.execSQL("PRAGMA foreign_keys=ON");
        else database.execSQL("PRAGMA query_only=ON");
        try {
            JSONObject result;
            if (isQuery(query)) result = query(database, query, null, 1_000);
            else result = executeStatements(database, query);
            AuditLog.get(context).append(project.id, "database", readOnly ? "sql_readonly" : "sql",
                    summarizeSql(query), true);
            return result;
        } catch (Exception error) {
            AuditLog.get(context).append(project.id, "database", readOnly ? "sql_readonly" : "sql",
                    summarizeSql(query) + " · " + error.getMessage(), false);
            throw error;
        } finally {
            database.close();
        }
    }

    public JSONArray schema(Project project, DbInfo info) throws Exception {
        if (!SQLITE.equals(info.engine)) {
            String sql;
            if (MARIADB.equals(info.engine)) {
                sql = "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + info.name.replace("'", "''")
                        + "' ORDER BY TABLE_NAME,ORDINAL_POSITION";
            } else {
                sql = "SELECT table_name,column_name,data_type,is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' ORDER BY table_name,ordinal_position";
            }
            JSONObject result = DatabaseServerManager.get(context).execute(project, info, sql);
            return result.optJSONArray("rows") == null ? new JSONArray() : result.getJSONArray("rows");
        }
        return schema(info);
    }

    public JSONArray schema(DbInfo info) throws Exception {
        ensureSqlite(info);
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            JSONObject result = query(database,
                    "SELECT type,name,tbl_name,sql FROM sqlite_master "
                            + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",
                    null, 5_000);
            return result.getJSONArray("rows");
        } finally { database.close(); }
    }

    public List<String> tables(DbInfo info) throws Exception {
        ensureSqlite(info);
        List<String> result = new ArrayList<>();
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
                        + "AND name!='_opencode_meta' ORDER BY name", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        } finally { database.close(); }
        return result;
    }

    public List<ColumnInfo> columns(DbInfo info, String table) throws Exception {
        ensureSqlite(info);
        String safe = quoteIdentifier(table);
        List<ColumnInfo> result = new ArrayList<>();
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + safe + ")", null)) {
            while (cursor.moveToNext()) {
                result.add(new ColumnInfo(
                        cursor.getInt(cursor.getColumnIndexOrThrow("cid")),
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                        cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))
                                ? null : cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("pk")) > 0));
            }
        } finally { database.close(); }
        return result;
    }

    public JSONObject rows(DbInfo info, String table, int limit, int offset, String orderBy) throws Exception {
        ensureSqlite(info);
        StringBuilder sql = new StringBuilder("SELECT rowid AS __rowid__, * FROM ")
                .append(quoteIdentifier(table));
        if (orderBy != null && !orderBy.trim().isEmpty()) {
            sql.append(" ORDER BY ").append(quoteIdentifier(orderBy.trim()));
        }
        sql.append(" LIMIT ").append(Math.max(1, Math.min(limit, 500)))
                .append(" OFFSET ").append(Math.max(0, offset));
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try { return query(database, sql.toString(), null, 500); }
        finally { database.close(); }
    }

    public long insert(DbInfo info, String table, Map<String, Object> values) throws Exception {
        ensureSqlite(info);
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Нет данных");
        android.content.ContentValues contentValues = new android.content.ContentValues();
        for (Map.Entry<String, Object> entry : values.entrySet()) put(contentValues, entry.getKey(), entry.getValue());
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try { return database.insertOrThrow(table, null, contentValues); }
        finally { database.close(); }
    }

    public int updateByRowId(DbInfo info, String table, long rowId, Map<String, Object> values) throws Exception {
        ensureSqlite(info);
        android.content.ContentValues contentValues = new android.content.ContentValues();
        for (Map.Entry<String, Object> entry : values.entrySet()) put(contentValues, entry.getKey(), entry.getValue());
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try { return database.update(table, contentValues, "rowid=?", new String[]{String.valueOf(rowId)}); }
        finally { database.close(); }
    }

    public int deleteByRowId(DbInfo info, String table, long rowId) throws Exception {
        ensureSqlite(info);
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try { return database.delete(table, "rowid=?", new String[]{String.valueOf(rowId)}); }
        finally { database.close(); }
    }

    public File backup(Project project, DbInfo info) throws Exception {
        if (info != null && !info.embedded()) return DatabaseServerManager.get(context).backup(project, info);
        ensureSqlite(info);
        File directory = new File(project.root, "backups/databases");
        directory.mkdirs();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File target = new File(directory, info.name + "-" + timestamp + ".db");
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try { database.execSQL("PRAGMA wal_checkpoint(FULL)"); }
        finally { database.close(); }
        Files.copy(info.file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AuditLog.get(context).append(project.id, "database", "backup", target.getName(), true);
        return target;
    }

    public void restore(Project project, DbInfo info, InputStream input) throws Exception {
        if (info != null && !info.embedded()) {
            DatabaseServerManager.get(context).restore(project, info, input);
            return;
        }
        ensureSqlite(info);
        File temporary = new File(info.directory, ".restore-" + System.currentTimeMillis() + ".db");
        try (FileOutputStream output = new FileOutputStream(temporary)) { copy(input, output); }
        SQLiteDatabase check = SQLiteDatabase.openDatabase(temporary.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = check.rawQuery("PRAGMA integrity_check", null)) {
            if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))) {
                throw new IllegalArgumentException("Резервная копия SQLite повреждена");
            }
        } finally { check.close(); }
        File backup = backup(project, info);
        Files.move(temporary.toPath(), info.file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AuditLog.get(context).append(project.id, "database", "restore", info.name + " from input; previous=" + backup.getName(), true);
    }

    public void exportSql(DbInfo info, OutputStream output) throws Exception {
        ensureSqlite(info);
        BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(output, StandardCharsets.UTF_8));
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            writer.write("PRAGMA foreign_keys=OFF;\nBEGIN TRANSACTION;\n");
            try (Cursor objects = database.rawQuery(
                    "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL "
                            + "AND name NOT LIKE 'sqlite_%' AND name!='_opencode_meta' "
                            + "ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END,name", null)) {
                while (objects.moveToNext()) {
                    String type = objects.getString(0);
                    String name = objects.getString(1);
                    String createSql = objects.getString(2);
                    if ("table".equals(type)) {
                        writer.write(createSql); writer.write(";\n");
                        dumpTable(database, writer, name);
                    }
                }
            }
            try (Cursor objects = database.rawQuery(
                    "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL "
                            + "AND type IN ('index','trigger','view') AND name NOT LIKE 'sqlite_%' ORDER BY type,name", null)) {
                while (objects.moveToNext()) { writer.write(objects.getString(0)); writer.write(";\n"); }
            }
            writer.write("COMMIT;\nPRAGMA foreign_keys=ON;\n");
            writer.flush();
        } finally { database.close(); }
    }

    public void exportSql(Project project, DbInfo info, OutputStream output) throws Exception {
        if (info != null && !info.embedded()) {
            DatabaseServerManager.get(context).exportSql(project, info, output);
            return;
        }
        exportSql(info, output);
    }

    public JSONObject importSql(Project project, DbInfo info, InputStream input) throws Exception {
        if (info != null && !info.embedded()) {
            DatabaseServerManager.get(context).restore(project, info, input);
            return new JSONObject().put("ok", true).put("restored", true);
        }
        StringBuilder sql = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[16 * 1024];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                sql.append(buffer, 0, count);
                if (sql.length() > 64 * 1024 * 1024) throw new IllegalArgumentException("SQL-файл слишком большой");
            }
        }
        backup(project, info);
        return execute(project, info, sql.toString());
    }

    public void exportCsv(DbInfo info, String table, OutputStream output) throws Exception {
        ensureSqlite(info);
        BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(output, StandardCharsets.UTF_8));
        SQLiteDatabase database = SQLiteDatabase.openDatabase(info.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = database.rawQuery("SELECT * FROM " + quoteIdentifier(table), null)) {
            String[] columns = cursor.getColumnNames();
            writeCsvRow(writer, Arrays.asList(columns));
            while (cursor.moveToNext()) {
                List<String> row = new ArrayList<>();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    row.add(cursor.isNull(i) ? "" : cursor.getString(i));
                }
                writeCsvRow(writer, row);
            }
            writer.flush();
        } finally { database.close(); }
    }

    public JSONObject connectionInfo(DbInfo info) throws Exception {
        JSONObject object = new JSONObject()
                .put("engine", info.engine)
                .put("database", info.name)
                .put("host", info.host)
                .put("port", info.port)
                .put("user", info.user);
        if (info.embedded()) object.put("path", info.file.getAbsolutePath());
        return object;
    }

    public boolean isWriteSql(String sql) {
        return SqlSafety.isWrite(sql);
    }

    private JSONObject executeStatements(SQLiteDatabase database, String sql) throws Exception {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) return new JSONObject().put("ok", true).put("statements", 0);
        database.beginTransaction();
        int completed = 0;
        try {
            for (String statement : statements) {
                if (statement.trim().isEmpty()) continue;
                database.execSQL(statement);
                completed++;
            }
            database.setTransactionSuccessful();
            return new JSONObject().put("ok", true).put("statements", completed).put("message", "Запрос выполнен");
        } finally { database.endTransaction(); }
    }

    private static JSONObject query(
            SQLiteDatabase database,
            String sql,
            String[] arguments,
            int rowLimit) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray columns = new JSONArray();
        JSONArray rows = new JSONArray();
        boolean truncated = false;
        try (Cursor cursor = database.rawQuery(sql, arguments)) {
            for (String column : cursor.getColumnNames()) columns.put(column);
            int count = 0;
            while (cursor.moveToNext()) {
                if (count >= rowLimit) { truncated = true; break; }
                JSONArray row = new JSONArray();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: row.put(JSONObject.NULL); break;
                        case Cursor.FIELD_TYPE_INTEGER: row.put(cursor.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(cursor.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_BLOB: row.put("<BLOB " + cursor.getBlob(i).length + " bytes>"); break;
                        default: row.put(cursor.getString(i));
                    }
                }
                rows.put(row);
                count++;
            }
        }
        return result.put("columns", columns).put("rows", rows)
                .put("count", rows.length()).put("truncated", truncated);
    }

    private static boolean isQuery(String sql) {
        return SqlSafety.isSingleReadQuery(sql);
    }

    private static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dual = false, lineComment = false, blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                current.append(c);
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                current.append(c);
                if (c == '*' && next == '/') { current.append(next); i++; blockComment = false; }
                continue;
            }
            if (!single && !dual && c == '-' && next == '-') { current.append(c).append(next); i++; lineComment = true; continue; }
            if (!single && !dual && c == '/' && next == '*') { current.append(c).append(next); i++; blockComment = true; continue; }
            if (!dual && c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) single = !single;
            if (!single && c == '"' && (i == 0 || sql.charAt(i - 1) != '\\')) dual = !dual;
            if (c == ';' && !single && !dual) {
                if (!current.toString().trim().isEmpty()) result.add(current.toString().trim());
                current.setLength(0);
            } else current.append(c);
        }
        if (!current.toString().trim().isEmpty()) result.add(current.toString().trim());
        return result;
    }

    private static void dumpTable(SQLiteDatabase database, BufferedWriter writer, String table) throws Exception {
        try (Cursor cursor = database.rawQuery("SELECT * FROM " + quoteIdentifier(table), null)) {
            while (cursor.moveToNext()) {
                writer.write("INSERT INTO "); writer.write(quoteIdentifier(table)); writer.write(" VALUES(");
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    if (i > 0) writer.write(',');
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: writer.write("NULL"); break;
                        case Cursor.FIELD_TYPE_INTEGER: writer.write(String.valueOf(cursor.getLong(i))); break;
                        case Cursor.FIELD_TYPE_FLOAT: writer.write(String.valueOf(cursor.getDouble(i))); break;
                        case Cursor.FIELD_TYPE_BLOB:
                            byte[] blob = cursor.getBlob(i);
                            writer.write("X'");
                            for (byte value : blob) writer.write(String.format(Locale.ROOT, "%02X", value));
                            writer.write("'");
                            break;
                        default: writer.write(sqlString(cursor.getString(i)));
                    }
                }
                writer.write(");\n");
            }
        }
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws Exception {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) writer.write(',');
            String value = values.get(i) == null ? "" : values.get(i);
            writer.write('"'); writer.write(value.replace("\"", "\"\"")); writer.write('"');
        }
        writer.write('\n');
    }

    private DbInfo fromProfile(File directory, JSONObject profile) throws Exception {
        String id = profile.optString("id", directory.getName());
        String engine = normalizeEngine(profile.optString("engine", SQLITE));
        String password = secureStore.get(profile.optString("secretRef", secretKey("unknown", id)), "");
        File file = null;
        String fileName = profile.optString("file", "");
        if (SQLITE.equals(engine)) {
            if (fileName.isEmpty()) fileName = profile.optString("name", "database") + ".db";
            file = new File(directory, fileName);
        }
        return new DbInfo(
                id,
                profile.optString("name", id),
                engine,
                profile.optString("user", "app_user"),
                password,
                profile.optString("host", "127.0.0.1"),
                profile.optInt("port", defaultPort(engine)),
                file,
                directory,
                profile.optLong("createdAt", directory.lastModified()));
    }

    private void migrateLegacy(Project project, List<DbInfo> result) {
        File legacy = new File(project.root, "databases");
        File[] files = legacy.listFiles((directory, name) -> name.endsWith(".db"));
        if (files == null) return;
        for (File file : files) {
            boolean already = false;
            for (DbInfo info : result) if (file.equals(info.file)) { already = true; break; }
            if (already) continue;
            try {
                String name = file.getName().substring(0, file.getName().length() - 3);
                String id = name + "-legacy";
                File directory = new File(legacy, id);
                directory.mkdirs();
                File moved = new File(directory, file.getName());
                Files.move(file.toPath(), moved.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                String secretRef = secretKey(project.id, id);
                secureStore.put(secretRef, randomPassword(24));
                JSONObject profile = new JSONObject()
                        .put("id", id).put("name", name).put("engine", SQLITE)
                        .put("user", name + "_user").put("secretRef", secretRef)
                        .put("host", "127.0.0.1").put("port", 0)
                        .put("file", moved.getName()).put("createdAt", moved.lastModified());
                ProjectManager.write(new File(directory, "database.json"), profile.toString(2));
                result.add(fromProfile(directory, profile));
            } catch (Exception ignored) {}
        }
    }

    private static File databasesDirectory(Project project) {
        File directory = new File(project.root, "databases");
        directory.mkdirs();
        return directory;
    }

    private static void ensureSqlite(DbInfo info) {
        if (info == null || !SQLITE.equals(info.engine) || info.file == null) {
            throw new UnsupportedOperationException("Операция доступна только для SQLite");
        }
    }

    private static String normalizeEngine(String engine) {
        String value = engine == null ? SQLITE : engine.toLowerCase(Locale.ROOT).trim();
        if (value.equals("mysql")) value = MARIADB;
        if (!value.equals(SQLITE) && !value.equals(MARIADB) && !value.equals(POSTGRES)) return SQLITE;
        return value;
    }

    private int nextPort(Project project, String engine) {
        int base = defaultPort(engine);
        if (base == 0) return 0;
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        for (DbInfo info : list(project)) if (engine.equals(info.engine)) used.add(info.port);
        int candidate = base;
        while (used.contains(candidate) && candidate < base + 500) candidate++;
        return candidate;
    }

    private static int defaultPort(String engine) {
        if (MARIADB.equals(engine)) return 3306;
        if (POSTGRES.equals(engine)) return 5432;
        return 0;
    }

    private static String safeIdentifier(String value, String fallback) {
        String result = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        if (result.isEmpty()) result = fallback;
        if (Character.isDigit(result.charAt(0))) result = "db_" + result;
        return result.substring(0, Math.min(63, result.length()));
    }

    private static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) throw new IllegalArgumentException("Invalid identifier");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String sqlString(String value) {
        if (value == null) return "NULL";
        return "'" + value.replace("'", "''") + "'";
    }

    private static String secretKey(String projectId, String databaseId) {
        return "db:" + projectId + ":" + databaseId + ":password";
    }

    private static String randomPassword(int length) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%_-";
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return result.toString();
    }

    private static void put(android.content.ContentValues values, String key, Object value) {
        if (value == null || value == JSONObject.NULL) values.putNull(key);
        else if (value instanceof byte[]) values.put(key, (byte[]) value);
        else if (value instanceof Integer) values.put(key, (Integer) value);
        else if (value instanceof Long) values.put(key, (Long) value);
        else if (value instanceof Float) values.put(key, (Float) value);
        else if (value instanceof Double) values.put(key, (Double) value);
        else if (value instanceof Boolean) values.put(key, (Boolean) value ? 1 : 0);
        else values.put(key, String.valueOf(value));
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    private static String summarizeSql(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "…";
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
    }
}
