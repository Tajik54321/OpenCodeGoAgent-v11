package com.qandil.opencodego;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import com.qandil.opencodego.ui.Ui;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TerminalActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Project project;
    private TextView output;
    private EditText input;
    private TextView run;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        project = ProjectManager.get(this).find(getIntent().getStringExtra("projectId"));
        if (project == null) { finish(); return; }
        build();
        append("OpenCode Go Project Terminal\n$ cd " + project.root.getAbsolutePath() + "\n");
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
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.button(this, "←", false); back.setOnClickListener(view -> finish());
        TextView title = Ui.text(this, "Терминал · " + project.name, 16, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        TextView clear = Ui.button(this, "Очистить", false); clear.setOnClickListener(view -> output.setText(""));
        bar.addView(back); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); bar.addView(clear);
        root.addView(bar);

        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        LinearLayout quick = Ui.row(this);
        for (String command : new String[]{"ls -la", "pwd", "git status", "php -v", "node -v", "python --version", "composer --version", "npm --version"}) {
            TextView chip = Ui.button(this, command, false);
            chip.setOnClickListener(view -> { input.setText(command); execute(); });
            quick.addView(chip); quick.addView(Ui.horizontalSpace(this, 7));
        }
        quickScroll.addView(quick);
        root.addView(quickScroll);

        ScrollView scroll = new ScrollView(this);
        output = Ui.text(this, "", 12, Ui.TEXT, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 16));
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout commandBar = Ui.row(this);
        commandBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12));
        input = Ui.input(this, "Команда shell", false);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { execute(); return true; }
            return false;
        });
        run = Ui.button(this, "Run", true); run.setOnClickListener(view -> execute());
        commandBar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        commandBar.addView(Ui.horizontalSpace(this, 8));
        commandBar.addView(run);
        root.addView(commandBar);
        setContentView(root);
    }

    private void execute() {
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        input.setText("");
        run.setEnabled(false);
        append("\n$ " + command + "\n");
        executor.submit(() -> {
            try {
                ProcessSupervisor.Result result = ProcessSupervisor.get(this).run(
                        project.id,
                        "Interactive terminal",
                        Arrays.asList("/system/bin/sh", "-lc", command),
                        project.root,
                        null,
                        600);
                runOnUiThread(() -> {
                    append(result.output + "\n[exit " + result.exitCode
                            + (result.timedOut ? ", timeout" : "") + "]\n");
                    run.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    append("ERROR: " + error.getMessage() + "\n");
                    run.setEnabled(true);
                });
            }
        });
    }

    private void append(String text) {
        output.append(text == null ? "" : text);
        if (output.length() > 500_000) output.setText(output.getText().subSequence(output.length() - 400_000, output.length()));
    }
}
