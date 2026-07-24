package com.qandil.opencodego;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.server.ServerManager;
import com.qandil.opencodego.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class EditorActivity extends Activity {
    private Project project;
    private EditText editor;
    private TextView pathView;
    private TextView statusView;
    private File currentFile;
    private String savedContent = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::confirmClose);
        }
        project = ProjectManager.get(this).find(getIntent().getStringExtra("projectId"));
        if (project == null) { finish(); return; }
        build();
        String path = getIntent().getStringExtra("path");
        if (path != null && !path.isEmpty()) {
            try { open(ProjectManager.get(this).safeResolve(project, path)); }
            catch (Exception error) { Ui.error(this, "Ошибка", error); chooseFile(); }
        } else chooseFile();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        TextView back = Ui.button(this, "←", false);
        back.setOnClickListener(view -> confirmClose());
        pathView = Ui.text(this, project.name, 13, Ui.TEXT, true);
        pathView.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
        TextView files = Ui.button(this, "Файлы", false);
        files.setOnClickListener(view -> chooseFile());
        TextView find = Ui.button(this, "Поиск", false);
        find.setOnClickListener(view -> searchDialog());
        TextView save = Ui.button(this, "Сохранить", true);
        save.setOnClickListener(view -> save());
        bar.addView(back);
        bar.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(files); bar.addView(Ui.horizontalSpace(this, 6));
        bar.addView(find); bar.addView(Ui.horizontalSpace(this, 6));
        bar.addView(save);
        root.addView(bar);

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        editor = Ui.input(this, "Выберите или создайте файл", true);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setMinWidth(getResources().getDisplayMetrics().widthPixels);
        editor.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 30));
        horizontal.addView(editor, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(horizontal, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = Ui.row(this);
        footer.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 10));
        statusView = Ui.text(this, "Нет файла", 11, Ui.MUTED, false);
        footer.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView preview = Ui.button(this, "Preview", false);
        preview.setOnClickListener(view -> preview());
        footer.addView(preview);
        root.addView(footer);
        setContentView(root);

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateStatus(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void chooseFile() {
        List<File> files = new ArrayList<>();
        walk(project.root, files);
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) names[i] = relative(files.get(i));
        new AlertDialog.Builder(this).setTitle("Файлы проекта")
                .setItems(names, (dialog, which) -> maybeOpen(files.get(which)))
                .setPositiveButton("Новый файл", (dialog, which) -> newFileDialog())
                .setNegativeButton("Закрыть", null).show();
    }

    private void maybeOpen(File file) {
        if (!dirty()) { open(file); return; }
        new AlertDialog.Builder(this).setTitle("Есть несохранённые изменения")
                .setMessage("Сохранить текущий файл перед открытием другого?")
                .setPositiveButton("Сохранить", (dialog, which) -> { save(); open(file); })
                .setNegativeButton("Не сохранять", (dialog, which) -> open(file))
                .setNeutralButton("Отмена", null).show();
    }

    private void open(File file) {
        try {
            currentFile = file;
            savedContent = ProjectManager.read(file);
            editor.setText(savedContent);
            editor.setSelection(0);
            pathView.setText(relative(file));
            updateStatus();
        } catch (Exception error) { Ui.error(this, "Не удалось открыть файл", error); }
    }

    private void save() {
        if (currentFile == null) { Ui.toast(this, "Файл не выбран"); return; }
        try {
            savedContent = editor.getText().toString();
            ProjectManager.write(currentFile, savedContent);
            updateStatus();
            Ui.toast(this, "Сохранено");
        } catch (Exception error) { Ui.error(this, "Не удалось сохранить", error); }
    }

    private void newFileDialog() {
        EditText path = Ui.input(this, "path/to/file.txt", false);
        new AlertDialog.Builder(this).setTitle("Новый файл").setView(path)
                .setPositiveButton("Создать", (dialog, which) -> {
                    try {
                        File file = ProjectManager.get(this).safeResolve(project, path.getText().toString());
                        if (file.exists()) throw new IllegalStateException("Файл уже существует");
                        ProjectManager.write(file, "");
                        open(file);
                    } catch (Exception error) { Ui.error(this, "Ошибка", error); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void searchDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        EditText find = Ui.input(this, "Найти", false);
        EditText replace = Ui.input(this, "Заменить на", false);
        form.addView(find); form.addView(Ui.space(this, 8)); form.addView(replace);
        new AlertDialog.Builder(this).setTitle("Поиск и замена").setView(form)
                .setPositiveButton("Найти далее", (dialog, which) -> findNext(find.getText().toString()))
                .setNeutralButton("Заменить все", (dialog, which) -> {
                    String needle = find.getText().toString();
                    if (needle.isEmpty()) return;
                    String original = editor.getText().toString();
                    int count = count(original, needle);
                    editor.setText(original.replace(needle, replace.getText().toString()));
                    Ui.toast(this, "Заменено: " + count);
                }).setNegativeButton("Закрыть", null).show();
    }

    private void findNext(String needle) {
        if (needle.isEmpty()) return;
        String text = editor.getText().toString();
        int start = Math.max(0, editor.getSelectionEnd());
        int index = text.indexOf(needle, start);
        if (index < 0) index = text.indexOf(needle);
        if (index < 0) Ui.toast(this, "Не найдено");
        else { editor.requestFocus(); editor.setSelection(index, index + needle.length()); }
    }

    private void preview() {
        if (currentFile == null) return;
        String lower = currentFile.getName().toLowerCase(Locale.ROOT);
        ServerManager.ServerHandle handle = ServerManager.get(this).get(project.id);
        if (handle != null && ServerManager.get(this).isRunning(project.id)) {
            String url = handle.url + relative(currentFile).replace(" ", "%20");
            startActivity(new Intent(this, PreviewActivity.class)
                    .putExtra("title", currentFile.getName()).putExtra("url", url));
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            startActivity(new Intent(this, PreviewActivity.class)
                    .putExtra("title", currentFile.getName()).putExtra("url", "file://" + currentFile.getAbsolutePath()));
        } else Ui.toast(this, "Запустите сервер проекта для preview");
    }

    private void confirmClose() {
        if (!dirty()) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("Сохранить изменения?")
                .setPositiveButton("Сохранить", (dialog, which) -> { save(); finish(); })
                .setNegativeButton("Не сохранять", (dialog, which) -> finish())
                .setNeutralButton("Отмена", null).show();
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { confirmClose(); }

    private void updateStatus() {
        String value = editor.getText().toString();
        int lines = value.isEmpty() ? 0 : 1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
        statusView.setText(lines + " строк · " + value.length() + " символов" + (dirty() ? " · изменён" : ""));
    }

    private boolean dirty() { return currentFile != null && !editor.getText().toString().equals(savedContent); }

    private void walk(File directory, List<File> output) {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (file.getName().equals("project.json") || file.getName().equals(".git")
                    || file.getName().equals("node_modules") || file.getName().equals("vendor")) continue;
            if (file.isDirectory()) walk(file, output);
            else if (file.length() <= 4L * 1024L * 1024L && !binary(file)) output.add(file);
            if (output.size() >= 2_000) return;
        }
    }

    private static boolean binary(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(png|jpe?g|gif|webp|ico|pdf|zip|apk|so|db|sqlite|mp3|mp4|woff2?|ttf)$");
    }

    private String relative(File file) {
        return project.root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static int count(String text, String value) {
        if (value.isEmpty()) return 0;
        int result = 0, index = 0;
        while ((index = text.indexOf(value, index)) >= 0) { result++; index += value.length(); }
        return result;
    }
}
