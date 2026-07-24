package com.qandil.opencodego;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.ui.Ui;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class FileManagerActivity extends Activity {
    private Project project;
    private File currentDirectory;
    private LinearLayout content;
    private TextView pathView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
project = ProjectManager.get(this).find(getIntent().getStringExtra("projectId"));
        if (project == null) { finish(); return; }
        currentDirectory = project.root;
        build();
        render();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.button(this, "←", false);
        back.setOnClickListener(view -> goBack());
        pathView = Ui.text(this, project.name, 15, Ui.TEXT, true);
        pathView.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        TextView add = Ui.button(this, "+", true);
        add.setOnClickListener(view -> createMenu());
        bar.addView(back);
        bar.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(add);
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
        pathView.setText(relative(currentDirectory).isEmpty() ? project.name : relative(currentDirectory));
        File[] files = currentDirectory.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparing(File::isFile)
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (!currentDirectory.equals(project.root)) {
            TextView parent = Ui.button(this, "↥  ..", false);
            parent.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            parent.setOnClickListener(view -> { currentDirectory = currentDirectory.getParentFile(); render(); });
            content.addView(parent);
            content.addView(Ui.space(this, 8));
        }
        for (File file : files) {
            if (file.getName().equals("project.json")) continue;
            LinearLayout card = Ui.card(this);
            LinearLayout row = Ui.row(this);
            TextView item = Ui.text(this,
                    (file.isDirectory() ? "▰  " : "▱  ") + file.getName(), 15, Ui.TEXT, true);
            item.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            TextView meta = Ui.text(this,
                    file.isDirectory() ? "ПАПКА" : format(file.length()), 11,
                    file.isDirectory() ? Ui.ACCENT : Ui.MUTED, true);
            row.addView(meta);
            card.addView(row);
            card.setOnClickListener(view -> {
                if (file.isDirectory()) { currentDirectory = file; render(); }
                else openFile(file);
            });
            card.setOnLongClickListener(view -> { actions(file); return true; });
            content.addView(card);
            content.addView(Ui.space(this, 8));
        }
        if (files.length == 0) content.addView(Ui.text(this, "Каталог пуст", 14, Ui.MUTED, false));
    }

    private void goBack() {
        if (currentDirectory.equals(project.root)) finish();
        else { currentDirectory = currentDirectory.getParentFile(); render(); }
    }

    @Override public void onBackPressed() { goBack(); }

    private void openFile(File file) {
        startActivity(new Intent(this, EditorActivity.class)
                .putExtra("projectId", project.id)
                .putExtra("path", relative(file)));
    }

    private void createMenu() {
        new AlertDialog.Builder(this).setTitle("Создать")
                .setItems(new String[]{"Файл", "Папку"}, (dialog, which) -> createDialog(which == 1))
                .setNegativeButton("Отмена", null).show();
    }

    private void createDialog(boolean directory) {
        EditText name = Ui.input(this, directory ? "Новая папка" : "file.txt", false);
        new AlertDialog.Builder(this).setTitle(directory ? "Новая папка" : "Новый файл")
                .setView(name).setPositiveButton("Создать", (dialog, which) -> {
                    try {
                        File target = safeChild(name.getText().toString());
                        if (target.exists()) throw new IllegalStateException("Такой путь уже существует");
                        if (directory) {
                            if (!target.mkdirs()) throw new IllegalStateException("Не удалось создать папку");
                        } else ProjectManager.write(target, "");
                        render();
                    } catch (Exception error) { Ui.error(this, "Ошибка", error); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void actions(File file) {
        new AlertDialog.Builder(this).setTitle(file.getName())
                .setItems(new String[]{"Открыть", "Переименовать", "Копировать", "Удалить"},
                        (dialog, which) -> {
                            if (which == 0) { if (file.isDirectory()) { currentDirectory = file; render(); } else openFile(file); }
                            else if (which == 1) rename(file);
                            else if (which == 2) copy(file);
                            else delete(file);
                        }).setNegativeButton("Закрыть", null).show();
    }

    private void rename(File file) {
        EditText name = Ui.input(this, "Новое имя", false); name.setText(file.getName());
        new AlertDialog.Builder(this).setTitle("Переименовать").setView(name)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    try {
                        File target = safeChild(name.getText().toString());
                        Files.move(file.toPath(), target.toPath());
                        render();
                    } catch (Exception error) { Ui.error(this, "Ошибка", error); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void copy(File file) {
        EditText name = Ui.input(this, "Имя копии", false); name.setText("copy-" + file.getName());
        new AlertDialog.Builder(this).setTitle("Создать копию").setView(name)
                .setPositiveButton("Копировать", (dialog, which) -> {
                    try {
                        File target = safeChild(name.getText().toString());
                        copyRecursive(file, target);
                        render();
                    } catch (Exception error) { Ui.error(this, "Ошибка", error); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void delete(File file) {
        new AlertDialog.Builder(this).setTitle("Удалить?").setMessage(relative(file))
                .setPositiveButton("Удалить", (dialog, which) -> {
                    try { ProjectManager.deleteRecursively(file); render(); }
                    catch (Exception error) { Ui.error(this, "Ошибка", error); }
                }).setNegativeButton("Отмена", null).show();
    }

    private File safeChild(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Имя пусто");
        File target = new File(currentDirectory, name.trim());
        return ProjectManager.safeResolve(project.root, relative(target));
    }

    private String relative(File file) {
        return project.root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static void copyRecursive(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.mkdirs() && !target.isDirectory()) throw new IllegalStateException("Не удалось создать папку");
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copyRecursive(child, new File(target, child.getName()));
        } else Files.copy(source.toPath(), target.toPath());
    }

    private static String format(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes; String[] units = {"KB", "MB", "GB"}; int index = -1;
        while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }
}
