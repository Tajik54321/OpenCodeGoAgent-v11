package com.qandil.opencodego;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.database.DatabaseManager;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DatabaseBrowserActivity extends Activity {
    private static final int EXPORT_CSV = 200;
    private static final int IMPORT_SQL = 201;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Project project;
    private DatabaseManager.DbInfo database;
    private DatabaseManager manager;
    private LinearLayout content;
    private TextView title;
    private String currentTable;
    private List<BrowserColumn> currentColumns = new ArrayList<>();

    private static final class BrowserColumn {
        final String name;
        final String type;
        final boolean primaryKey;
        final boolean notNull;
        final String defaultValue;
        final boolean generated;

        BrowserColumn(String name, String type, boolean primaryKey, boolean notNull,
                      String defaultValue, boolean generated) {
            this.name = name;
            this.type = type == null ? "" : type;
            this.primaryKey = primaryKey;
            this.notNull = notNull;
            this.defaultValue = defaultValue;
            this.generated = generated;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        project = ProjectManager.get(this).find(getIntent().getStringExtra("projectId"));
        manager = DatabaseManager.get(this);
        database = project == null ? null : manager.find(project, getIntent().getStringExtra("databaseId"));
        if (project == null || database == null) { finish(); return; }
        build();
        showTables();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        TextView back = Ui.button(this, "←", false);
        back.setOnClickListener(view -> {
            if (currentTable != null) showTables(); else finish();
        });
        title = Ui.text(this, database.name, 16, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        TextView sql = Ui.button(this, "SQL", false);
        sql.setOnClickListener(view -> startActivity(new Intent(this, SqlConsoleActivity.class)
                .putExtra("projectId", project.id).putExtra("databaseId", database.id)));
        TextView add = Ui.button(this, "+", true);
        add.setOnClickListener(view -> {
            if (currentTable == null) createTableDialog(); else insertRowDialog();
        });
        bar.addView(back); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(sql); bar.addView(Ui.horizontalSpace(this, 6)); bar.addView(add);
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void showTables() {
        currentTable = null;
        title.setText(database.name + " · Таблицы");
        content.removeAllViews();
        LinearLayout actions = Ui.row(this);
        TextView importSql = Ui.button(this, "Импорт SQL", false);
        importSql.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), IMPORT_SQL));
        TextView schema = Ui.button(this, "Схема JSON", false);
        schema.setOnClickListener(view -> showSchema());
        actions.addView(importSql, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 8));
        actions.addView(schema, Ui.weight(1));
        content.addView(actions);
        content.addView(Ui.space(this, 12));
        executor.submit(() -> {
            try {
                List<String> tables = loadTableNames();
                runOnUiThread(() -> {
                    if (tables.isEmpty()) {
                        LinearLayout card = Ui.card(this);
                        card.addView(Ui.text(this, "Таблиц пока нет", 18, Ui.TEXT, true));
                        card.addView(Ui.text(this, "Нажмите +, чтобы создать первую таблицу.", 13, Ui.MUTED, false));
                        content.addView(card);
                        return;
                    }
                    for (String table : tables) {
                        LinearLayout card = Ui.card(this);
                        LinearLayout row = Ui.row(this);
                        row.addView(Ui.text(this, "▦  " + table, 16, Ui.TEXT, true), Ui.weight(1));
                        TextView open = Ui.button(this, "Открыть", true);
                        open.setOnClickListener(view -> showTable(table));
                        row.addView(open);
                        card.addView(row);
                        content.addView(card);
                        content.addView(Ui.space(this, 8));
                    }
                });
            } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка базы", error)); }
        });
    }

    private void showTable(String table) {
        currentTable = table;
        title.setText(database.name + " · " + table);
        content.removeAllViews();
        LinearLayout actions = Ui.row(this);
        TextView refresh = Ui.button(this, "Обновить", true);
        refresh.setOnClickListener(view -> showTable(table));
        TextView columns = Ui.button(this, "Колонки", false);
        columns.setOnClickListener(view -> showColumns(table));
        TextView export = Ui.button(this, "CSV", false);
        export.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, table + ".csv"), EXPORT_CSV));
        actions.addView(refresh, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
        actions.addView(columns, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
        actions.addView(export, Ui.weight(1));
        content.addView(actions);
        content.addView(Ui.space(this, 12));
        TextView loading = Ui.text(this, "Загрузка строк…", 14, Ui.MUTED, false);
        content.addView(loading);
        executor.submit(() -> {
            try {
                List<BrowserColumn> metadata = loadColumns(table);
                JSONObject rows = loadRows(table);
                runOnUiThread(() -> {
                    currentColumns = metadata;
                    renderRows(table, rows);
                });
            } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка таблицы", error)); }
        });
    }

    private void renderRows(String table, JSONObject result) {
        while (content.getChildCount() > 2) content.removeViewAt(2);
        JSONArray columns = result.optJSONArray("columns");
        JSONArray rows = result.optJSONArray("rows");
        if (columns == null || rows == null || rows.length() == 0) {
            content.addView(Ui.text(this, "Таблица пуста", 14, Ui.MUTED, false));
            return;
        }
        BrowserColumn primary = primaryColumn();
        int primaryIndex = database.embedded() ? 0 : indexOf(columns, primary == null ? null : primary.name);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(tableRow(columns, true, -1));
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;
            Object keyValue = primaryIndex >= 0 && primaryIndex < row.length() ? row.opt(primaryIndex) : null;
            View rowView = tableRow(row, false, database.embedded() ? row.optLong(0, -1) : -1);
            if (database.embedded() || (primary != null && keyValue != null && keyValue != JSONObject.NULL)) {
                String keyName = database.embedded() ? "rowid" : primary.name;
                rowView.setOnLongClickListener(view -> {
                    rowActions(table, keyName, keyValue, columns, row);
                    return true;
                });
            } else {
                rowView.setOnClickListener(view -> showRow(columns, row));
            }
            grid.addView(rowView);
        }
        horizontal.addView(grid);
        content.addView(horizontal);
        content.addView(Ui.space(this, 8));
        String actions = database.embedded() || primary != null
                ? " · удерживайте строку для действий"
                : " · таблица без PRIMARY KEY доступна только для просмотра";
        content.addView(Ui.text(this,
                "Показано: " + rows.length() + (result.optBoolean("truncated") ? " · результат обрезан" : "") + actions,
                11, Ui.MUTED, false));
    }

    private LinearLayout tableRow(JSONArray values, boolean header, long rowId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Ui.outlined(header ? Ui.SURFACE_2 : Ui.SURFACE, Ui.BORDER, 0, this));
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            TextView cell = Ui.text(this,
                    value == null || value == JSONObject.NULL ? "NULL" : String.valueOf(value),
                    12, value == JSONObject.NULL ? Ui.MUTED : Ui.TEXT, header);
            cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            cell.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
            cell.setMinWidth(Ui.dp(this, 130));
            cell.setMaxWidth(Ui.dp(this, 260));
            row.addView(cell);
        }
        return row;
    }

    private void rowActions(String table, String keyName, Object keyValue, JSONArray columns, JSONArray row) {
        new AlertDialog.Builder(this).setTitle("Строка " + keyName + "=" + String.valueOf(keyValue))
                .setItems(new String[]{"Просмотреть", "Редактировать", "Удалить"}, (dialog, which) -> {
                    if (which == 0) showRow(columns, row);
                    else if (which == 1) editRowDialog(table, keyName, keyValue, columns, row);
                    else confirmDelete(table, keyName, keyValue);
                }).setNegativeButton("Закрыть", null).show();
    }

    private void showRow(JSONArray columns, JSONArray row) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(columns.length(), row.length()); i++) {
            text.append(columns.optString(i)).append(" = ").append(String.valueOf(row.opt(i))).append('\n');
        }
        TextView view = Ui.text(this, text.toString(), 13, Ui.TEXT, false);
        view.setTextIsSelectable(true); view.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        new AlertDialog.Builder(this).setTitle("Строка").setView(view).setPositiveButton("Закрыть", null).show();
    }

    private void editRowDialog(String table, String keyName, Object keyValue, JSONArray columns, JSONArray row) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 10));
        Map<String, EditText> inputs = new LinkedHashMap<>();
        for (int i = 0; i < columns.length(); i++) {
            String column = columns.optString(i);
            if ((database.embedded() && i == 0) || (!database.embedded() && column.equals(keyName))) continue;
            EditText input = Ui.input(this, column, false);
            Object value = row.opt(i);
            if (value != null && value != JSONObject.NULL) input.setText(String.valueOf(value));
            form.addView(Ui.labeledInput(this, column, input)); form.addView(Ui.space(this, 7));
            inputs.put(column, input);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Редактировать строку").setView(scroll)
                .setPositiveButton("Сохранить", (dialog, which) -> executor.submit(() -> {
                    try {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (Map.Entry<String, EditText> entry : inputs.entrySet()) {
                            String value = entry.getValue().getText().toString();
                            values.put(entry.getKey(), value.isEmpty() ? null : value);
                        }
                        if (database.embedded()) {
                            manager.updateByRowId(database, table, Long.parseLong(String.valueOf(keyValue)), values);
                        } else {
                            List<String> assignments = new ArrayList<>();
                            for (Map.Entry<String, Object> entry : values.entrySet()) {
                                assignments.add(quote(entry.getKey()) + "=" + sqlLiteral(entry.getValue()));
                            }
                            if (assignments.isEmpty()) throw new IllegalArgumentException("Нет изменяемых колонок");
                            String sql = "UPDATE " + quote(table) + " SET " + join(assignments)
                                    + " WHERE " + quote(keyName) + "=" + sqlLiteral(keyValue)
                                    + (DatabaseManager.MARIADB.equals(database.engine) ? " LIMIT 1" : "");
                            manager.execute(project, database, sql);
                        }
                        runOnUiThread(() -> showTable(table));
                    } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                })).setNegativeButton("Отмена", null).show();
    }

    private void confirmDelete(String table, String keyName, Object keyValue) {
        new AlertDialog.Builder(this).setTitle("Удалить строку?")
                .setMessage(keyName + "=" + String.valueOf(keyValue))
                .setPositiveButton("Удалить", (dialog, which) -> executor.submit(() -> {
                    try {
                        if (database.embedded()) {
                            manager.deleteByRowId(database, table, Long.parseLong(String.valueOf(keyValue)));
                        } else {
                            String sql = "DELETE FROM " + quote(table) + " WHERE " + quote(keyName) + "="
                                    + sqlLiteral(keyValue)
                                    + (DatabaseManager.MARIADB.equals(database.engine) ? " LIMIT 1" : "");
                            manager.execute(project, database, sql);
                        }
                        runOnUiThread(() -> showTable(table));
                    } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                })).setNegativeButton("Отмена", null).show();
    }

    private void insertRowDialog() {
        String table = currentTable;
        executor.submit(() -> {
            try {
                List<BrowserColumn> columns = loadColumns(table);
                runOnUiThread(() -> {
                    LinearLayout form = new LinearLayout(this);
                    form.setOrientation(LinearLayout.VERTICAL);
                    form.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 10));
                    Map<String, EditText> inputs = new LinkedHashMap<>();
                    for (BrowserColumn column : columns) {
                        if (column.generated || (column.primaryKey && isIntegerType(column.type) && column.defaultValue != null)) continue;
                        EditText input = Ui.input(this, column.type + (column.notNull ? " · NOT NULL" : ""), false);
                        form.addView(Ui.labeledInput(this, column.name, input)); form.addView(Ui.space(this, 7));
                        inputs.put(column.name, input);
                    }
                    ScrollView scroll = new ScrollView(this); scroll.addView(form);
                    new AlertDialog.Builder(this).setTitle("Добавить строку").setView(scroll)
                            .setPositiveButton("Добавить", (dialog, which) -> executor.submit(() -> {
                                try {
                                    Map<String, Object> values = new LinkedHashMap<>();
                                    for (Map.Entry<String, EditText> entry : inputs.entrySet()) {
                                        String value = entry.getValue().getText().toString();
                                        values.put(entry.getKey(), value.isEmpty() ? null : value);
                                    }
                                    if (database.embedded()) {
                                        manager.insert(database, table, values);
                                    } else if (values.isEmpty()) {
                                        manager.execute(project, database, "INSERT INTO " + quote(table) + " DEFAULT VALUES");
                                    } else {
                                        List<String> names = new ArrayList<>();
                                        List<String> literals = new ArrayList<>();
                                        for (Map.Entry<String, Object> entry : values.entrySet()) {
                                            names.add(quote(entry.getKey()));
                                            literals.add(sqlLiteral(entry.getValue()));
                                        }
                                        manager.execute(project, database, "INSERT INTO " + quote(table)
                                                + " (" + join(names) + ") VALUES (" + join(literals) + ")");
                                    }
                                    runOnUiThread(() -> showTable(table));
                                } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                            })).setNegativeButton("Отмена", null).show();
                });
            } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
        });
    }

    private void createTableDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        EditText name = Ui.input(this, "users", false);
        EditText columns = Ui.input(this, "name TEXT NOT NULL, email TEXT UNIQUE", true);
        form.addView(Ui.labeledInput(this, "Название таблицы", name)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Колонки после id INTEGER PRIMARY KEY AUTOINCREMENT", columns));
        new AlertDialog.Builder(this).setTitle("Новая таблица").setView(form)
                .setPositiveButton("Создать", (dialog, which) -> executor.submit(() -> {
                    try {
                        String table = name.getText().toString().replaceAll("[^A-Za-z0-9_]", "_");
                        if (table.isEmpty()) throw new IllegalArgumentException("Недопустимое имя");
                        String extras = columns.getText().toString().trim();
                        String sql = "CREATE TABLE \"" + table + "\" (id INTEGER PRIMARY KEY AUTOINCREMENT"
                                + (extras.isEmpty() ? "" : ", " + extras) + ")";
                        manager.execute(project, database, sql);
                        runOnUiThread(this::showTables);
                    } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                })).setNegativeButton("Отмена", null).show();
    }

    private void showColumns(String table) {
        executor.submit(() -> {
            try {
                StringBuilder text = new StringBuilder();
                for (BrowserColumn column : loadColumns(table)) {
                    text.append(column.name).append(" · ").append(column.type)
                            .append(column.primaryKey ? " · PRIMARY KEY" : "")
                            .append(column.notNull ? " · NOT NULL" : "")
                            .append(column.generated ? " · GENERATED" : "")
                            .append(column.defaultValue == null || column.defaultValue.isEmpty()
                                    ? "" : " · DEFAULT " + column.defaultValue)
                            .append('\n');
                }
                runOnUiThread(() -> {
                    TextView view = Ui.text(this, text.toString(), 13, Ui.TEXT, false);
                    view.setTextIsSelectable(true); view.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
                    new AlertDialog.Builder(this).setTitle("Колонки · " + table).setView(view)
                            .setPositiveButton("Закрыть", null).show();
                });
            } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
        });
    }

    private void showSchema() {
        executor.submit(() -> {
            try {
                String schema = manager.schema(project, database).toString(2);
                runOnUiThread(() -> {
                    TextView view = Ui.text(this, schema, 12, Ui.TEXT, false);
                    view.setTextIsSelectable(true);
                    ScrollView scroll = new ScrollView(this); scroll.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0); scroll.addView(view);
                    new AlertDialog.Builder(this).setTitle("Схема").setView(scroll).setPositiveButton("Закрыть", null).show();
                });
            } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
        });
    }

    private List<String> loadTableNames() throws Exception {
        if (database.embedded()) return manager.tables(database);
        String sql = DatabaseManager.MARIADB.equals(database.engine)
                ? "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + sqlString(database.name) + "' ORDER BY TABLE_NAME"
                : "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name";
        JSONObject result = manager.execute(project, database, sql);
        List<String> tables = new ArrayList<>();
        JSONArray rows = result.optJSONArray("rows");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row != null && row.length() > 0) tables.add(row.optString(0));
        }
        return tables;
    }

    private JSONObject loadRows(String table) throws Exception {
        if (database.embedded()) return manager.rows(database, table, 100, 0, null);
        return manager.execute(project, database, "SELECT * FROM " + quote(table) + " LIMIT 100");
    }

    private String columnSql(String table) {
        String escaped = sqlString(table);
        if (DatabaseManager.MARIADB.equals(database.engine)) {
            return "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_KEY,EXTRA "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + sqlString(database.name)
                    + "' AND TABLE_NAME='" + escaped + "' ORDER BY ORDINAL_POSITION";
        }
        return "SELECT c.column_name,c.data_type,c.is_nullable,c.column_default," 
                + "CASE WHEN tc.constraint_type='PRIMARY KEY' THEN 'PRI' ELSE '' END AS column_key," 
                + "CASE WHEN c.is_identity='YES' OR c.column_default LIKE 'nextval(%' THEN 'generated' ELSE '' END AS extra "
                + "FROM information_schema.columns c LEFT JOIN information_schema.key_column_usage kcu "
                + "ON c.table_schema=kcu.table_schema AND c.table_name=kcu.table_name AND c.column_name=kcu.column_name "
                + "LEFT JOIN information_schema.table_constraints tc ON kcu.constraint_name=tc.constraint_name "
                + "AND kcu.table_schema=tc.table_schema AND tc.constraint_type='PRIMARY KEY' "
                + "WHERE c.table_schema='public' AND c.table_name='" + escaped + "' ORDER BY c.ordinal_position";
    }

    private List<BrowserColumn> loadColumns(String table) throws Exception {
        List<BrowserColumn> result = new ArrayList<>();
        if (database.embedded()) {
            for (DatabaseManager.ColumnInfo column : manager.columns(database, table)) {
                boolean generated = column.primaryKey && isIntegerType(column.type);
                result.add(new BrowserColumn(column.name, column.type, column.primaryKey,
                        column.notNull, column.defaultValue, generated));
            }
            return result;
        }
        JSONObject query = manager.execute(project, database, columnSql(table));
        JSONArray rows = query.optJSONArray("rows");
        if (rows == null) return result;
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 1) continue;
            String name = row.optString(0);
            String type = row.optString(1);
            boolean notNull = "NO".equalsIgnoreCase(row.optString(2));
            String defaultValue = normalizeNull(row.opt(3));
            boolean primary = "PRI".equalsIgnoreCase(row.optString(4));
            String extra = row.optString(5).toLowerCase();
            boolean generated = extra.contains("auto_increment") || extra.contains("generated")
                    || (defaultValue != null && defaultValue.toLowerCase().startsWith("nextval("));
            result.add(new BrowserColumn(name, type, primary, notNull, defaultValue, generated));
        }
        return result;
    }

    private BrowserColumn primaryColumn() {
        for (BrowserColumn column : currentColumns) if (column.primaryKey) return column;
        return null;
    }

    private static int indexOf(JSONArray columns, String name) {
        if (columns == null || name == null) return -1;
        for (int i = 0; i < columns.length(); i++) if (name.equalsIgnoreCase(columns.optString(i))) return i;
        return -1;
    }

    private static String normalizeNull(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        String text = String.valueOf(value);
        return text.isEmpty() || "NULL".equalsIgnoreCase(text) ? null : text;
    }

    private static boolean isIntegerType(String type) {
        String normalized = type == null ? "" : type.toLowerCase();
        return normalized.contains("int") || normalized.contains("serial");
    }

    private static String join(List<String> values) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) output.append(',');
            output.append(value);
        }
        return output.toString();
    }

    private static String sqlLiteral(Object value) {
        if (value == null || value == JSONObject.NULL) return "NULL";
        String text = String.valueOf(value);
        return "'" + text.replace("'", "''") + "'";
    }

    private String quote(String identifier) {
        return DatabaseManager.MARIADB.equals(database.engine)
                ? "`" + identifier.replace("`", "``") + "`"
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String sqlString(String value) { return value.replace("'", "''"); }

    private void exportExternalCsv(String table, OutputStream output) throws Exception {
        JSONObject result = loadRows(table);
        java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8));
        writeCsv(writer, result.optJSONArray("columns"));
        JSONArray rows = result.optJSONArray("rows");
        if (rows != null) for (int i = 0; i < rows.length(); i++) writeCsv(writer, rows.optJSONArray(i));
        writer.flush();
    }

    private static void writeCsv(java.io.BufferedWriter writer, JSONArray values) throws Exception {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) writer.write(',');
            Object value = values.opt(i);
            String text = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
            writer.write('\"'); writer.write(text.replace("\"", "\"\"")); writer.write('\"');
        }
        writer.write('\n');
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == EXPORT_CSV && currentTable != null) {
            executor.submit(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new java.io.IOException("Не удалось открыть файл для записи");
                    if (database.embedded()) manager.exportCsv(database, currentTable, output);
                    else exportExternalCsv(currentTable, output);
                    runOnUiThread(() -> Ui.toast(this, "CSV экспортирован"));
                } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
            });
        } else if (requestCode == IMPORT_SQL) {
            executor.submit(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("Не удалось открыть SQL-файл");
                    manager.importSql(project, database, input);
                    runOnUiThread(this::showTables);
                } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка импорта", error)); }
            });
        }
    }
}
