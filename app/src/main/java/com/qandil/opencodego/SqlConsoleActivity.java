package com.qandil.opencodego;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.database.DatabaseManager;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.ui.Ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SqlConsoleActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Project project;
    private DatabaseManager.DbInfo database;
    private EditText sql;
    private TextView output;
    private TextView run;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        project = ProjectManager.get(this).find(getIntent().getStringExtra("projectId"));
        DatabaseManager manager = DatabaseManager.get(this);
        database = project == null ? null : manager.find(project, getIntent().getStringExtra("databaseId"));
        if (project == null || database == null) { finish(); return; }
        build();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        LinearLayout header = Ui.row(this);
        TextView back = Ui.button(this, "←", false); back.setOnClickListener(view -> finish());
        TextView title = Ui.text(this, "SQL · " + database.name, 19, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        header.addView(back); header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        root.addView(Ui.space(this, 10));
        sql = Ui.input(this, "SQL query", true);
        sql.setTypeface(Typeface.MONOSPACE);
        sql.setMinLines(7);
        sql.setText("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name;");
        root.addView(sql);
        root.addView(Ui.space(this, 8));
        LinearLayout actions = Ui.row(this);
        run = Ui.button(this, "Выполнить", true); run.setOnClickListener(view -> execute());
        TextView clear = Ui.button(this, "Очистить", false); clear.setOnClickListener(view -> output.setText(""));
        TextView format = Ui.button(this, "Шаблоны", false); format.setOnClickListener(view -> templates());
        actions.addView(run, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
        actions.addView(format, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
        actions.addView(clear, Ui.weight(1));
        root.addView(actions);
        root.addView(Ui.space(this, 10));
        output = Ui.text(this, "", 12, Ui.TEXT, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setGravity(Gravity.TOP | Gravity.START);
        ScrollView scroll = new ScrollView(this); scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void execute() {
        String query = sql.getText().toString().trim();
        if (query.isEmpty()) return;
        run.setEnabled(false); output.setText("Выполняется…");
        executor.submit(() -> {
            try {
                String result = DatabaseManager.get(this).execute(project, database, query).toString(2);
                runOnUiThread(() -> { output.setText(result); run.setEnabled(true); });
            } catch (Exception error) {
                runOnUiThread(() -> { output.setText("Ошибка: " + error.getMessage()); run.setEnabled(true); });
            }
        });
    }

    private void templates() {
        String[] labels = {"Все таблицы", "Создать users", "Индексы", "Foreign keys", "Integrity check", "Query plan"};
        String[] queries = {
                "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name;",
                "CREATE TABLE users (\n  id INTEGER PRIMARY KEY AUTOINCREMENT,\n  name TEXT NOT NULL,\n  email TEXT UNIQUE,\n  created_at TEXT DEFAULT CURRENT_TIMESTAMP\n);",
                "SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index' ORDER BY tbl_name, name;",
                "PRAGMA foreign_key_list('users');",
                "PRAGMA integrity_check;",
                "EXPLAIN QUERY PLAN SELECT * FROM users WHERE email = 'user@example.com';"
        };
        new android.app.AlertDialog.Builder(this).setTitle("SQL шаблоны")
                .setItems(labels, (dialog, which) -> sql.setText(queries[which]))
                .setNegativeButton("Закрыть", null).show();
    }
}
