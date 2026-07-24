package com.qandil.opencodego;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qandil.opencodego.build.BuildCenter;
import com.qandil.opencodego.cron.CronManager;
import com.qandil.opencodego.git.GitManager;
import com.qandil.opencodego.integration.IntegrationStore;
import com.qandil.opencodego.integration.McpClient;
import com.qandil.opencodego.integration.OpenCodeServerClient;
import com.qandil.opencodego.lsp.LspManager;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.redis.RedisManager;
import com.qandil.opencodego.remote.RemoteManager;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.security.SecureStore;
import com.qandil.opencodego.server.ServerForegroundService;
import android.content.Intent;
import android.os.Build;
import com.qandil.opencodego.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Unified OpenCode/MCP/Git/remote/Cron/Redis/LSP/build administration UI. */
public final class IntegrationCenterActivity extends Activity {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private LinearLayout content;
    private Project project;
    private IntegrationStore integrations;
    private SecureStore secure;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        integrations = new IntegrationStore(this);
        secure = new SecureStore(this);
        String projectId = getIntent().getStringExtra("projectId");
        project = ProjectManager.get(this).find(projectId);
        build();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 30));
        content.setBackgroundColor(Ui.BG);
        content.addView(Ui.text(this, "Integration Center", 28, Ui.TEXT, true));
        content.addView(Ui.text(this,
                project == null ? "OpenCode, MCP и внешние сервисы" : project.name + " · внешние инструменты проекта",
                13, Ui.MUTED, false));
        content.addView(Ui.space(this, 16));
        openCodeCard();
        content.addView(Ui.space(this, 12));
        mcpCard();
        if (project != null) {
            content.addView(Ui.space(this, 12)); gitCard();
            content.addView(Ui.space(this, 12)); remoteCard();
            content.addView(Ui.space(this, 12)); cronCard();
            content.addView(Ui.space(this, 12)); redisCard();
            content.addView(Ui.space(this, 12)); lspCard();
            content.addView(Ui.space(this, 12)); buildCard();
        }
        content.addView(Ui.space(this, 12)); runtimeCard();
        scroll.addView(content);
        setContentView(scroll);
    }

    private void openCodeCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "OpenCode Server", 19, Ui.TEXT, true));
        card.addView(Ui.text(this, "Подключение к opencode serve через официальный HTTP/OpenAPI интерфейс", 12, Ui.MUTED, false));
        List<IntegrationStore.OpenCodeProfile> profiles = integrations.openCodeProfiles();
        if (profiles.isEmpty()) {
            TextView add = Ui.button(this, "Добавить OpenCode Server", true);
            add.setOnClickListener(view -> editOpenCode(new IntegrationStore.OpenCodeProfile()));
            card.addView(Ui.space(this, 10)); card.addView(add);
        } else for (IntegrationStore.OpenCodeProfile profile : profiles) {
            card.addView(Ui.space(this, 10));
            LinearLayout row = Ui.card(this);
            row.addView(Ui.text(this, profile.name, 16, Ui.TEXT, true));
            row.addView(Ui.text(this, profile.baseUrl + (profile.directory.isEmpty() ? "" : "\n" + profile.directory), 12, Ui.MUTED, false));
            LinearLayout actions = Ui.row(this);
            TextView test = Ui.button(this, "Health", true);
            test.setOnClickListener(v -> async("Проверка OpenCode…", () -> {
                OpenCodeServerClient client = new OpenCodeServerClient(toClientProfile(profile));
                showJson("OpenCode Health", client.health());
            }));
            TextView inspect = Ui.button(this, "Проекты / сессии", false);
            inspect.setOnClickListener(v -> async("Загрузка OpenCode…", () -> {
                OpenCodeServerClient client = new OpenCodeServerClient(toClientProfile(profile));
                JSONObject result = new JSONObject().put("health", client.health())
                        .put("currentProject", client.currentProject()).put("projects", client.projects())
                        .put("sessions", client.sessions()).put("vcs", client.vcs());
                showJson("OpenCode Server", result);
            }));
            TextView edit = Ui.button(this, "Изменить", false);
            edit.setOnClickListener(v -> editOpenCode(profile));
            actions.addView(test, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6));
            actions.addView(inspect, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6));
            actions.addView(edit, Ui.weight(1));
            row.addView(Ui.space(this, 8)); row.addView(actions);
            card.addView(row);
        }
        content.addView(card);
    }

    private void editOpenCode(IntegrationStore.OpenCodeProfile profile) {
        LinearLayout form = form();
        EditText name = Ui.input(this, "OpenCode Server", false); name.setText(profile.name);
        EditText url = Ui.input(this, "http://127.0.0.1:4096", false); url.setText(profile.baseUrl);
        EditText user = Ui.input(this, "opencode", false); user.setText(profile.username);
        EditText password = Ui.input(this, "Пароль", false); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setText(profile.password);
        EditText directory = Ui.input(this, "/path/to/project", false); directory.setText(profile.directory);
        form.addView(Ui.labeledInput(this, "Название", name)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Base URL", url)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Пользователь Basic Auth", user)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Пароль", password)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Удалённая директория проекта", directory));
        new AlertDialog.Builder(this).setTitle("OpenCode Server").setView(form)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    profile.name = name.getText().toString().trim(); profile.baseUrl = url.getText().toString().trim();
                    profile.username = user.getText().toString().trim(); profile.password = password.getText().toString();
                    profile.directory = directory.getText().toString().trim(); integrations.saveOpenCode(profile); rebuild();
                }).setNegativeButton("Отмена", null)
                .setNeutralButton("Удалить", (dialog, which) -> { integrations.deleteOpenCode(profile.id); rebuild(); }).show();
    }

    private void mcpCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "MCP Servers", 19, Ui.TEXT, true));
        card.addView(Ui.text(this, "Streamable HTTP: tools, resources и prompts", 12, Ui.MUTED, false));
        List<IntegrationStore.McpProfile> profiles = integrations.mcpProfiles();
        TextView add = Ui.button(this, "Добавить MCP", profiles.isEmpty());
        add.setOnClickListener(v -> editMcp(new IntegrationStore.McpProfile()));
        card.addView(Ui.space(this, 10)); card.addView(add);
        for (IntegrationStore.McpProfile profile : profiles) {
            LinearLayout row = Ui.card(this);
            row.addView(Ui.text(this, profile.name, 16, Ui.TEXT, true));
            row.addView(Ui.text(this, profile.url, 12, Ui.MUTED, false));
            LinearLayout actions = Ui.row(this);
            TextView tools = Ui.button(this, "Проверить / tools", true);
            tools.setOnClickListener(v -> async("Подключение MCP…", () -> {
                McpClient client = new McpClient(profile.url, McpClient.parseHeaders(profile.headersJson));
                JSONObject result = new JSONObject().put("initialize", client.initialize())
                        .put("tools", client.listTools()).put("resources", client.listResources())
                        .put("prompts", client.listPrompts());
                showJson("MCP · " + profile.name, result);
            }));
            TextView edit = Ui.button(this, "Изменить", false); edit.setOnClickListener(v -> editMcp(profile));
            actions.addView(tools, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6)); actions.addView(edit, Ui.weight(1));
            row.addView(Ui.space(this, 8)); row.addView(actions); card.addView(Ui.space(this, 8)); card.addView(row);
        }
        content.addView(card);
    }

    private void editMcp(IntegrationStore.McpProfile profile) {
        LinearLayout form = form();
        EditText name = Ui.input(this, "MCP Server", false); name.setText(profile.name);
        EditText url = Ui.input(this, "http://127.0.0.1:3000/mcp", false); url.setText(profile.url);
        EditText headers = Ui.input(this, "{\"Authorization\":\"Bearer ...\"}", true); headers.setText(profile.headersJson);
        form.addView(Ui.labeledInput(this, "Название", name)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "MCP URL", url)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Зашифрованные HTTP-заголовки (JSON)", headers));
        new AlertDialog.Builder(this).setTitle("MCP Server").setView(form)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    new JSONObject(headers.getText().toString().trim().isEmpty() ? "{}" : headers.getText().toString());
                    profile.name = name.getText().toString().trim(); profile.url = url.getText().toString().trim();
                    profile.headersJson = headers.getText().toString().trim(); integrations.saveMcp(profile); rebuild();
                }).setNegativeButton("Отмена", null)
                .setNeutralButton("Удалить", (dialog, which) -> { integrations.deleteMcp(profile.id); rebuild(); }).show();
    }

    private void gitCard() {
        GitManager git = new GitManager(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Git Center", 19, Ui.TEXT, true));
        card.addView(Ui.text(this, git.available() ? "Git runtime подключён" : "Нужен Git ARM64 runtime pack", 12,
                git.available() ? Ui.ACCENT : Ui.WARNING, true));
        EditText message = Ui.input(this, "Сообщение коммита", false);
        card.addView(Ui.space(this, 8)); card.addView(message);
        LinearLayout actions = Ui.row(this);
        TextView status = Ui.button(this, "Status / log", true);
        status.setOnClickListener(v -> async("Git status…", () -> showJson("Git", new JSONObject()
                .put("status", git.status(project)).put("log", git.log(project, 30)))));
        TextView commit = Ui.button(this, "Add + Commit", false);
        commit.setOnClickListener(v -> async("Git commit…", () -> {
            git.addAll(project); showText("Git commit", git.commit(project, message.getText().toString()));
        }));
        TextView diff = Ui.button(this, "Diff", false);
        diff.setOnClickListener(v -> async("Git diff…", () -> showText("Git diff", git.diff(project, false))));
        actions.addView(status, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6));
        actions.addView(commit, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6)); actions.addView(diff, Ui.weight(1));
        card.addView(Ui.space(this, 8)); card.addView(actions); content.addView(card);
    }

    private void remoteCard() {
        RemoteManager remote = new RemoteManager(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Remote Deploy", 19, Ui.TEXT, true));
        card.addView(Ui.text(this, remote.capabilities().toString(), 12, Ui.MUTED, false));
        EditText host = Ui.input(this, "server.example.com", false);
        EditText user = Ui.input(this, "deploy", false);
        EditText path = Ui.input(this, "/var/www/project", false);
        EditText command = Ui.input(this, "php artisan migrate --force", false);
        card.addView(Ui.space(this, 8)); card.addView(Ui.labeledInput(this, "Host", host));
        card.addView(Ui.space(this, 6)); card.addView(Ui.labeledInput(this, "User", user));
        card.addView(Ui.space(this, 6)); card.addView(Ui.labeledInput(this, "Remote path", path));
        card.addView(Ui.space(this, 6)); card.addView(Ui.labeledInput(this, "SSH command", command));
        LinearLayout actions = Ui.row(this);
        TextView ssh = Ui.button(this, "SSH", true);
        ssh.setOnClickListener(v -> async("SSH…", () -> showText("SSH", remote.ssh(project, host.getText().toString(), 22,
                user.getText().toString(), command.getText().toString()))));
        TextView deploy = Ui.button(this, "Rsync deploy", false);
        deploy.setOnClickListener(v -> async("Rsync…", () -> showText("Rsync", remote.syncRsync(project,
                host.getText().toString(), 22, user.getText().toString(), path.getText().toString(), false))));
        actions.addView(ssh, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6)); actions.addView(deploy, Ui.weight(1));
        card.addView(Ui.space(this, 8)); card.addView(actions); content.addView(card);
    }

    private void cronCard() {
        CronManager cron = CronManager.get(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Cron Center", 19, Ui.TEXT, true));
        List<CronManager.Task> tasks = cron.list(project.id);
        card.addView(Ui.text(this, "Задач: " + tasks.size(), 12, Ui.MUTED, false));
        EditText name = Ui.input(this, "Backup / migration", false);
        EditText command = Ui.input(this, "/absolute/runtime/bin/php artisan schedule:run", false);
        EditText minutes = Ui.input(this, "60", false);
        card.addView(Ui.space(this, 8)); card.addView(name); card.addView(Ui.space(this, 6)); card.addView(command);
        card.addView(Ui.space(this, 6)); card.addView(minutes);
        TextView add = Ui.button(this, "Добавить периодическую задачу", true);
        add.setOnClickListener(v -> {
            CronManager.Task task = new CronManager.Task(); task.projectId = project.id;
            task.name = name.getText().toString().trim(); task.command = CronManager.parseCommandLine(command.getText().toString());
            try { task.intervalMinutes = Integer.parseInt(minutes.getText().toString()); } catch (Exception ignored) { task.intervalMinutes = 60; }
            cron.save(task);
            Intent keepAlive = new Intent(this, ServerForegroundService.class)
                    .setAction(ServerForegroundService.ACTION_KEEP_ALIVE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(keepAlive); else startService(keepAlive);
            Ui.toast(this, "Cron-задача сохранена"); rebuild();
        });
        card.addView(Ui.space(this, 8)); card.addView(add);
        for (CronManager.Task task : tasks) {
            TextView item = Ui.button(this, task.name + " · next " + new java.util.Date(task.nextRunAt), false);
            item.setOnClickListener(v -> async("Cron task…", () -> { cron.runNow(task.id); showText(task.name, task.lastResult); }));
            card.addView(Ui.space(this, 6)); card.addView(item);
        }
        content.addView(card);
    }

    private void redisCard() {
        RedisManager redis = new RedisManager(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Redis Center", 19, Ui.TEXT, true));
        EditText password = Ui.input(this, "Redis password (optional)", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(secure.get("redis." + project.id + ".password", ""));
        card.addView(Ui.space(this, 8)); card.addView(password);
        LinearLayout actions = Ui.row(this);
        TextView start = Ui.button(this, "Запустить Redis", true);
        start.setOnClickListener(v -> async("Redis start…", () -> {
            String secret = password.getText().toString();
            secure.put("redis." + project.id + ".password", secret);
            redis.start(project, 6379, secret); showText("Redis", "Запущен на 127.0.0.1:6379");
        }));
        TextView inspect = Ui.button(this, "INFO / keys", false);
        inspect.setOnClickListener(v -> async("Redis inspect…", () -> showJson("Redis", new JSONObject()
                .put("info", redis.info(project, 6379, password.getText().toString()))
                .put("keys", redis.keys(project, 6379, password.getText().toString(), "*", 100)))));
        actions.addView(start, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 6)); actions.addView(inspect, Ui.weight(1));
        card.addView(Ui.space(this, 8)); card.addView(actions); content.addView(card);
    }

    private void lspCard() {
        LspManager lsp = new LspManager(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Language Servers", 19, Ui.TEXT, true));
        card.addView(Ui.text(this, "TypeScript/JavaScript, Python, PHP и Java через LSP stdio runtime packs", 12, Ui.MUTED, false));
        String[] languages = new String[]{"typescript", "python", "php", "java"};
        LinearLayout actions = Ui.row(this);
        for (String language : languages) {
            TextView button = Ui.button(this, language, "typescript".equals(language));
            button.setOnClickListener(v -> async("LSP " + language + "…", () -> {
                LspManager.Session session = lsp.start(project, language);
                showText("LSP " + language, "initialized · process=" + session.process.isAlive());
            }));
            actions.addView(button, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 4));
        }
        card.addView(Ui.space(this, 8)); card.addView(actions); content.addView(card);
    }

    private void buildCard() {
        BuildCenter builds = new BuildCenter(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Build Center", 19, Ui.TEXT, true));
        card.addView(Ui.text(this, builds.inspect(project).toString(), 12, Ui.MUTED, false));
        EditText variant = Ui.input(this, "Debug", false); variant.setText("Debug");
        card.addView(Ui.space(this, 8)); card.addView(variant);
        TextView local = Ui.button(this, "Локальная Gradle-сборка", true);
        local.setOnClickListener(v -> async("Gradle build…", () -> {
            com.qandil.opencodego.terminal.ProcessSupervisor.Result result = builds.localAndroidBuild(project,
                    variant.getText().toString(), false, 1800);
            showText("Gradle", "exit=" + result.exitCode + " timeout=" + result.timedOut + "\n" + result.output);
        }));
        card.addView(Ui.space(this, 8)); card.addView(local);
        TextView github = Ui.button(this, "Настроить GitHub Actions", false);
        github.setOnClickListener(v -> githubBuildDialog(builds));
        card.addView(Ui.space(this, 6)); card.addView(github); content.addView(card);
    }

    private void githubBuildDialog(BuildCenter builds) {
        LinearLayout form = form();
        EditText repo = Ui.input(this, "owner/repository", false);
        EditText workflow = Ui.input(this, "build-android.yml", false); workflow.setText("build-android.yml");
        EditText ref = Ui.input(this, "main", false); ref.setText("main");
        EditText token = Ui.input(this, "GitHub token", false); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(secure.get("github.actions.token", ""));
        form.addView(repo); form.addView(Ui.space(this, 6)); form.addView(workflow); form.addView(Ui.space(this, 6));
        form.addView(ref); form.addView(Ui.space(this, 6)); form.addView(token);
        new AlertDialog.Builder(this).setTitle("GitHub Actions build").setView(form)
                .setPositiveButton("Запустить", (dialog, which) -> async("Workflow dispatch…", () -> {
                    secure.put("github.actions.token", token.getText().toString());
                    JSONObject result = builds.dispatchGitHub(repo.getText().toString().trim(), workflow.getText().toString().trim(),
                            ref.getText().toString().trim(), new JSONObject(), token.getText().toString());
                    result.put("runs", builds.workflowRuns(repo.getText().toString().trim(), workflow.getText().toString().trim(),
                            ref.getText().toString().trim(), token.getText().toString()));
                    showJson("GitHub Actions", result);
                })).setNegativeButton("Отмена", null).show();
    }

    private void runtimeCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Runtime capabilities", 19, Ui.TEXT, true));
        JSONArray values = new JSONArray();
        for (RuntimeManager.RuntimeInfo info : RuntimeManager.get(this).list()) {
            values.put(new JSONObject().put("name", info.name).put("installed", info.installed)
                    .put("version", info.version).put("problem", info.problem));
        }
        card.addView(Ui.text(this, values.toString(2), 11, Ui.MUTED, false));
        content.addView(card);
    }

    private OpenCodeServerClient.Profile toClientProfile(IntegrationStore.OpenCodeProfile value) {
        OpenCodeServerClient.Profile profile = new OpenCodeServerClient.Profile();
        profile.name = value.name; profile.baseUrl = value.baseUrl; profile.username = value.username;
        profile.password = value.password; profile.directory = value.directory; return profile;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8)); return form;
    }

    private void rebuild() { content.removeAllViews(); build(); }

    private interface Throwing { void run() throws Exception; }
    private void async(String message, Throwing task) {
        AlertDialog progress = new AlertDialog.Builder(this).setMessage(message).setCancelable(false).create();
        progress.show(); executor.submit(() -> {
            try { task.run(); } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
            finally { runOnUiThread(progress::dismiss); }
        });
    }

    private void showJson(String title, Object value) {
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(title)
                .setMessage(value instanceof JSONObject ? ((JSONObject) value).toString(2)
                        : value instanceof JSONArray ? ((JSONArray) value).toString(2) : String.valueOf(value))
                .setPositiveButton("OK", null).show());
    }
    private void showText(String title, String value) {
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(title).setMessage(value)
                .setPositiveButton("OK", null).show());
    }
}
