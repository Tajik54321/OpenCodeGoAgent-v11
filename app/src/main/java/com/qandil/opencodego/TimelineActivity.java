package com.qandil.opencodego;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.audit.AuditLog;
import com.qandil.opencodego.history.CheckpointManager;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.ui.Ui;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TimelineActivity extends Activity {
    private Project project;
    private LinearLayout content;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        project = ProjectManager.get(this).find(getIntent().getStringExtra("projectId"));
        if (project == null) { finish(); return; }
        build();
        render();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.button(this, "←", false); back.setOnClickListener(view -> finish());
        TextView title = Ui.text(this, "Timeline · " + project.name, 17, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        TextView clear = Ui.dangerButton(this, "Очистить"); clear.setOnClickListener(view -> confirmClear());
        bar.addView(back); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); bar.addView(clear);
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        content.removeAllViews();
        CheckpointManager checkpointManager = new CheckpointManager(this);
        List<JSONObject> checkpoints = checkpointManager.list(project, 50);
        List<JSONObject> events = AuditLog.get(this).tail(project.id, 500);
        if (checkpoints.isEmpty() && events.isEmpty()) {
            content.addView(Ui.text(this, "Журнал и контрольные точки пока пусты", 14, Ui.MUTED, false));
            return;
        }
        if (!checkpoints.isEmpty()) {
            content.addView(Ui.text(this, "Контрольные точки", 20, Ui.TEXT, true));
            content.addView(Ui.text(this,
                    "Создаются автоматически перед изменением, перемещением и удалением файлов ИИ.",
                    12, Ui.MUTED, false));
            content.addView(Ui.space(this, 10));
            for (JSONObject checkpoint : checkpoints) {
                LinearLayout card = Ui.card(this);
                LinearLayout row = Ui.row(this);
                LinearLayout labels = new LinearLayout(this);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.addView(Ui.text(this, checkpoint.optString("path"), 14, Ui.TEXT, true));
                labels.addView(Ui.text(this,
                        checkpoint.optString("action") + " · " + formatTime(checkpoint.optLong("time")),
                        11, Ui.MUTED, false));
                row.addView(labels, Ui.weight(1));
                TextView restore = Ui.button(this, "Вернуть", false);
                restore.setOnClickListener(view -> confirmRestore(checkpoint.optString("id"), checkpoint.optString("path")));
                row.addView(restore);
                card.addView(row);
                content.addView(card);
                content.addView(Ui.space(this, 7));
            }
            content.addView(Ui.space(this, 12));
        }
        if (!events.isEmpty()) {
            content.addView(Ui.text(this, "Журнал действий", 20, Ui.TEXT, true));
            content.addView(Ui.space(this, 10));
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            JSONObject event = events.get(i);
            LinearLayout card = Ui.card(this);
            LinearLayout header = Ui.row(this);
            boolean success = event.optBoolean("success", false);
            header.addView(Ui.text(this,
                    event.optString("category").toUpperCase(Locale.ROOT) + " · " + event.optString("action"),
                    13, success ? Ui.ACCENT : Ui.DANGER, true), Ui.weight(1));
            header.addView(Ui.text(this, formatTime(event.optLong("time")), 11, Ui.MUTED, false));
            card.addView(header);
            card.addView(Ui.space(this, 7));
            TextView summary = Ui.text(this, event.optString("summary"), 12, Ui.TEXT, false);
            summary.setTextIsSelectable(true);
            card.addView(summary);
            content.addView(card);
            content.addView(Ui.space(this, 8));
        }
    }

    private String formatTime(long timestamp) {
        return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date(timestamp));
    }

    private void confirmRestore(String id, String path) {
        new AlertDialog.Builder(this).setTitle("Восстановить контрольную точку?")
                .setMessage(path + " будет возвращён в состояние до изменения.")
                .setPositiveButton("Восстановить", (dialog, which) -> new Thread(() -> {
                    try {
                        new CheckpointManager(this).restore(project, id);
                        runOnUiThread(() -> { Ui.toast(this, "Восстановлено: " + path); render(); });
                    } catch (Exception error) {
                        runOnUiThread(() -> Ui.error(this, "Ошибка восстановления", error));
                    }
                }, "checkpoint-restore").start())
                .setNegativeButton("Отмена", null).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Очистить Timeline?")
                .setPositiveButton("Очистить", (dialog, which) -> {
                    AuditLog.get(this).clear(project.id); render();
                }).setNegativeButton("Отмена", null).show();
    }
}
