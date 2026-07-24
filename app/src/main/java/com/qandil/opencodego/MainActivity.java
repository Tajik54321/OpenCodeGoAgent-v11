package com.qandil.opencodego;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.qandil.opencodego.ai.AgentEngine;
import com.qandil.opencodego.ai.AgentCoordinator;
import com.qandil.opencodego.ai.AgentProfile;
import com.qandil.opencodego.ai.ConversationStore;
import com.qandil.opencodego.ai.PermissionStore;
import com.qandil.opencodego.ai.Provider;
import com.qandil.opencodego.ai.ProviderClient;
import com.qandil.opencodego.ai.ProviderStore;
import com.qandil.opencodego.database.DatabaseManager;
import com.qandil.opencodego.database.DatabaseServerManager;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.server.ServerForegroundService;
import com.qandil.opencodego.server.ServerManager;
import com.qandil.opencodego.ui.Ui;
import com.qandil.opencodego.util.ZipUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT_PROJECT_ZIP = 100;
    private static final int EXPORT_PROJECT_ZIP = 101;
    private static final int IMPORT_RUNTIME = 102;
    private static final int EXPORT_DATABASE_BACKUP = 103;
    private static final int RESTORE_DATABASE_BACKUP = 104;
    private static final int EXPORT_DATABASE_SQL = 105;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private LinearLayout content;
    private LinearLayout navigation;
    private Project selectedProject;
    private Provider selectedProvider;
    private Project pendingProject;
    private String pendingRuntime;
    private DatabaseManager.DbInfo pendingDatabase;
    private String currentPage = "agent";
    private String selectedAgentRole = AgentProfile.BUILD;

    private ProjectManager projects;
    private ProviderStore providers;
    private RuntimeManager runtimes;
    private DatabaseManager databases;
    private ConversationStore conversations;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 90);
        }
        projects = ProjectManager.get(this);
        providers = ProviderStore.get(this);
        runtimes = RuntimeManager.get(this);
        databases = DatabaseManager.get(this);
        conversations = new ConversationStore(this);
        projects.ensureStarterProject();
        providers.ensureCatalog();
        buildShell();
        showAgent();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 8));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(Ui.text(this, "OpenCode Go Agent", 21, Ui.TEXT, true));
        titleBox.addView(Ui.text(this, "SERVER STUDIO · ANDROID", 11, Ui.ACCENT, true));
        top.addView(titleBox, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1));
        TextView emergency = Ui.dangerButton(this, "STOP");
        emergency.setOnClickListener(view -> {
            ServerManager.get(this).stopAll();
            Intent intent = new Intent(this, ServerForegroundService.class)
                    .setAction(ServerForegroundService.ACTION_STOP_ALL);
            startService(intent);
            Ui.toast(this, "Все серверы остановлены");
            refresh();
        });
        top.addView(emergency);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 10));
        navigation.setBackgroundColor(Ui.SURFACE);
        navScroll.addView(navigation, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addNav("agent", "Агент", this::showAgent);
        addNav("projects", "Проекты", this::showProjects);
        addNav("servers", "Серверы", this::showServers);
        addNav("databases", "Базы", this::showDatabases);
        addNav("providers", "Провайдеры", this::showProviders);
        addNav("more", "Ещё", this::showMore);
        root.addView(navScroll);
        setContentView(root);
    }

    private void addNav(String page, String label, Runnable action) {
        TextView view = Ui.text(this, label, 13, Ui.MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13));
        view.setOnClickListener(clicked -> {
            currentPage = page;
            action.run();
        });
        view.setTag(page);
        navigation.addView(view);
    }

    private void highlightNavigation() {
        for (int i = 0; i < navigation.getChildCount(); i++) {
            View child = navigation.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            boolean active = currentPage.equals(child.getTag());
            ((TextView) child).setTextColor(active ? Ui.ACCENT : Ui.MUTED);
            child.setBackground(active ? Ui.bg(Ui.SURFACE_2, 14, this) : null);
        }
    }

    private void clear(String title, String subtitle) {
        content.removeAllViews();
        highlightNavigation();
        content.addView(Ui.text(this, title, 28, Ui.TEXT, true));
        content.addView(Ui.space(this, 4));
        content.addView(Ui.text(this, subtitle, 14, Ui.MUTED, false));
        content.addView(Ui.space(this, 18));
    }

    private Project project() {
        List<Project> list = projects.list();
        if (selectedProject == null && !list.isEmpty()) selectedProject = list.get(0);
        if (selectedProject != null) {
            Project latest = projects.find(selectedProject.id);
            if (latest != null) selectedProject = latest;
            else selectedProject = list.isEmpty() ? null : list.get(0);
        }
        return selectedProject;
    }

    private Provider provider() {
        List<Provider> list = providers.enabled();
        if (selectedProvider == null && !list.isEmpty()) selectedProvider = list.get(0);
        if (selectedProvider != null) {
            Provider latest = providers.find(selectedProvider.id);
            if (latest != null && latest.enabled) selectedProvider = latest;
            else selectedProvider = list.isEmpty() ? null : list.get(0);
        }
        return selectedProvider;
    }

    private Spinner spinner(List<?> values, Object selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<Object> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<Object>(values));
        spinner.setAdapter(adapter);
        if (selected != null) spinner.setSelection(Math.max(0, values.indexOf(selected)));
        spinner.setBackground(Ui.outlined(Ui.SURFACE_2, Ui.BORDER, 14, this));
        spinner.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 7));
        return spinner;
    }

    private void showAgent() {
        currentPage = "agent";
        clear("AI Agent", "Код, терминал, серверы и базы в одной управляемой сессии");
        Project activeProject = project();
        Provider activeProvider = provider();
        if (activeProject == null) {
            emptyCard("Нет проекта", "Создайте или импортируйте проект во вкладке «Проекты».");
            return;
        }
        if (activeProvider == null) {
            emptyCard("Нет включённого провайдера", "Включите провайдер в Provider Hub.");
            return;
        }

        LinearLayout config = Ui.card(this);
        config.addView(Ui.text(this, "Рабочая сессия", 18, Ui.TEXT, true));
        config.addView(Ui.space(this, 10));
        config.addView(Ui.text(this, "Проект", 12, Ui.MUTED, true));
        Spinner projectSpinner = spinner(projects.list(), activeProject);
        projectSpinner.setOnItemSelectedListener(new ItemSelected(value -> selectedProject = (Project) value));
        config.addView(projectSpinner);
        config.addView(Ui.space(this, 10));
        config.addView(Ui.text(this, "Модель / провайдер", 12, Ui.MUTED, true));
        Spinner providerSpinner = spinner(providers.enabled(), activeProvider);
        providerSpinner.setOnItemSelectedListener(new ItemSelected(value -> selectedProvider = (Provider) value));
        config.addView(providerSpinner);
        config.addView(Ui.space(this, 10));
        config.addView(Ui.text(this, "Роль агента", 12, Ui.MUTED, true));
        List<AgentProfile> agentProfiles = Arrays.asList(
                AgentProfile.of(AgentProfile.BUILD), AgentProfile.of(AgentProfile.PLAN),
                AgentProfile.of(AgentProfile.DEBUG), AgentProfile.of(AgentProfile.REVIEW),
                AgentProfile.of(AgentProfile.SECURITY), AgentProfile.of(AgentProfile.DATABASE),
                AgentProfile.of(AgentProfile.SERVER), AgentProfile.of(AgentProfile.RELEASE));
        AgentProfile activeProfile = AgentProfile.of(selectedAgentRole);
        for (AgentProfile item : agentProfiles) if (item.id.equals(selectedAgentRole)) activeProfile = item;
        Spinner agentSpinner = spinner(agentProfiles, activeProfile);
        agentSpinner.setOnItemSelectedListener(new ItemSelected(value -> selectedAgentRole = ((AgentProfile) value).id));
        config.addView(agentSpinner);
        content.addView(config);
        content.addView(Ui.space(this, 12));

        LinearLayout permissionCard = Ui.card(this);
        permissionCard.addView(Ui.text(this, "Доступ ИИ", 18, Ui.TEXT, true));
        permissionCard.addView(Ui.text(this,
                "Разрешения действуют только для выбранного проекта. Удерживайте переключатель для временного доступа.",
                12, Ui.MUTED, false));
        permissionCard.addView(Ui.space(this, 8));
        PermissionStore permissionStore = new PermissionStore(this);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.READ_FILES, "Читать файлы", true);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.WRITE_FILES, "Создавать и изменять файлы", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.SERVER, "Управлять сервером и логами", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.EXEC_COMMAND, "Выполнять команды", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.DB_READ, "Читать базы данных", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.DB_WRITE, "Изменять базы данных", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.READ_SECRETS, "Читать .env и секретные файлы", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.NETWORK, "Отправлять контекст облачному провайдеру", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.GIT, "Управлять Git-репозиторием", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.REMOTE, "SSH/SFTP/FTP и удалённое развёртывание", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.INTEGRATIONS, "OpenCode Server и MCP-инструменты", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.BUILD, "Запускать локальные и облачные сборки", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.REDIS, "Читать и изменять Redis", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.SCHEDULE, "Создавать и запускать cron-задачи", false);
        addPermissionSwitch(permissionCard, permissionStore, activeProject,
                PermissionStore.DESTRUCTIVE, "Удаление, DROP и разрушительные команды", false);
        TextView revoke = Ui.dangerButton(this, "Отозвать все разрешения");
        revoke.setOnClickListener(view -> {
            permissionStore.revokeAll(activeProject.id);
            showAgent();
        });
        permissionCard.addView(Ui.space(this, 8));
        permissionCard.addView(revoke);
        content.addView(permissionCard);
        content.addView(Ui.space(this, 12));

        List<JSONObject> history = conversations.tail(activeProject.id, 8);
        if (!history.isEmpty()) {
            LinearLayout historyCard = Ui.card(this);
            LinearLayout historyHeader = Ui.row(this);
            historyHeader.addView(Ui.text(this, "Последние сообщения", 18, Ui.TEXT, true), Ui.weight(1));
            TextView clear = Ui.button(this, "Очистить", false);
            clear.setOnClickListener(view -> {
                conversations.clear(activeProject.id);
                showAgent();
            });
            historyHeader.addView(clear);
            historyCard.addView(historyHeader);
            for (JSONObject item : history) {
                String role = item.optString("role", "assistant");
                int color = "user".equals(role) ? Ui.INFO : Ui.ACCENT;
                historyCard.addView(Ui.space(this, 9));
                historyCard.addView(Ui.text(this,
                        ("user".equals(role) ? "ВЫ" : "АГЕНТ") + " · "
                                + item.optString("content"), 13, color, false));
            }
            content.addView(historyCard);
            content.addView(Ui.space(this, 12));
        }

        LinearLayout chat = Ui.card(this);
        TextView output = Ui.text(this, "Агент готов.", 14, Ui.TEXT, false);
        output.setTextIsSelectable(true);
        EditText input = Ui.input(this,
                "Например: изучи проект, исправь запуск, создай базу и проверь сайт…", true);
        input.setMinLines(4);
        Switch orchestrate = new Switch(this);
        orchestrate.setText("Planner → Builder → Reviewer");
        orchestrate.setTextColor(Ui.TEXT);
        orchestrate.setChecked(false);
        TextView send = Ui.button(this, "Выполнить задачу", true);
        chat.addView(output);
        chat.addView(Ui.space(this, 12));
        chat.addView(input);
        chat.addView(Ui.space(this, 8));
        chat.addView(orchestrate);
        chat.addView(Ui.space(this, 10));
        chat.addView(send);
        send.setOnClickListener(view -> {
            Project taskProject = project();
            Provider taskProvider = provider();
            String task = input.getText().toString().trim();
            if (taskProject == null || taskProvider == null || task.isEmpty()) return;
            if (!taskProvider.local() && taskProvider.apiKey.isEmpty()) {
                Ui.toast(this, "Сначала сохраните API-ключ провайдера");
                return;
            }
            if (taskProvider.model.isEmpty()) {
                Ui.toast(this, "Сначала выберите или укажите модель");
                return;
            }
            final boolean useCoordinator = orchestrate.isChecked();
            final String taskRole = selectedAgentRole;
            send.setEnabled(false);
            output.setText("Агент анализирует проект и выполняет разрешённые действия…");
            conversations.append(taskProject.id, "user", task, taskProvider.id);
            executor.submit(() -> {
                try {
                    String answer = useCoordinator
                            ? new AgentCoordinator(this).execute(taskProject, taskProvider, task, true)
                            : new AgentEngine(this).runWithRole(taskProject, taskProvider, task, taskRole);
                    conversations.append(taskProject.id, "assistant", answer, taskProvider.id);
                    runOnUiThread(() -> {
                        output.setText(answer);
                        input.setText("");
                        send.setEnabled(true);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        output.setText("Ошибка: " + error.getMessage());
                        send.setEnabled(true);
                    });
                }
            });
        });
        content.addView(chat);
    }

    private void addPermissionSwitch(
            LinearLayout parent,
            PermissionStore store,
            Project project,
            String permission,
            String label,
            boolean defaultValue) {
        if (defaultValue && store.expiresAt(project.id, permission) == 0L) store.set(project.id, permission, true);
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextColor(Ui.TEXT);
        view.setChecked(store.allowed(project.id, permission));
        view.setOnCheckedChangeListener((button, checked) -> store.set(project.id, permission, checked));
        view.setOnLongClickListener(clicked -> {
            permissionDurationDialog(store, project, permission, label);
            return true;
        });
        parent.addView(view);
    }

    private void permissionDurationDialog(
            PermissionStore store,
            Project project,
            String permission,
            String label) {
        String[] options = {"15 минут", "1 час", "24 часа", "Постоянно", "Отозвать"};
        new AlertDialog.Builder(this).setTitle(label)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) store.allowFor(project.id, permission, 15L * 60L * 1000L);
                    else if (which == 1) store.allowFor(project.id, permission, 60L * 60L * 1000L);
                    else if (which == 2) store.allowFor(project.id, permission, 24L * 60L * 60L * 1000L);
                    else if (which == 3) store.set(project.id, permission, true);
                    else store.set(project.id, permission, false);
                    showAgent();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void showProjects() {
        currentPage = "projects";
        clear("Проекты", "Static, PHP, Node.js, Python, Nginx и Apache с отдельными настройками");
        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = Ui.row(this);
        for (String[] item : new String[][]{
                {"+ Static", "static"}, {"+ PHP", "php"}, {"+ Node", "node"}, {"+ Python", "python"},
                {"+ Nginx", "nginx"}, {"+ Apache", "apache"}}) {
            TextView button = Ui.button(this, item[0], "static".equals(item[1]));
            button.setOnClickListener(view -> createProjectDialog(item[1]));
            actions.addView(button);
            actions.addView(Ui.horizontalSpace(this, 8));
        }
        TextView importZip = Ui.button(this, "Импорт ZIP", false);
        importZip.setOnClickListener(view -> openDocument("application/zip", IMPORT_PROJECT_ZIP));
        actions.addView(importZip);
        actionsScroll.addView(actions);
        content.addView(actionsScroll);
        content.addView(Ui.space(this, 14));

        for (Project item : projects.list()) {
            LinearLayout card = Ui.card(this);
            LinearLayout header = Ui.row(this);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(Ui.text(this, item.name, 19, Ui.TEXT, true));
            labels.addView(Ui.text(this,
                    item.type.toUpperCase(Locale.ROOT) + " · " + item.entryPoint,
                    12, Ui.MUTED, false));
            header.addView(labels, Ui.weight(1));
            header.addView(Ui.chip(this, formatBytes(folderSize(item.root)), Ui.ACCENT));
            card.addView(header);
            card.addView(Ui.space(this, 8));
            card.addView(Ui.text(this, item.root.getAbsolutePath(), 11, Ui.MUTED, false));
            card.addView(Ui.space(this, 12));

            LinearLayout row1 = Ui.row(this);
            TextView files = Ui.button(this, "Файлы", true);
            files.setOnClickListener(view -> startActivity(new Intent(this, FileManagerActivity.class)
                    .putExtra("projectId", item.id)));
            TextView editor = Ui.button(this, "Редактор", false);
            editor.setOnClickListener(view -> startActivity(new Intent(this, EditorActivity.class)
                    .putExtra("projectId", item.id)));
            TextView terminal = Ui.button(this, "Терминал", false);
            terminal.setOnClickListener(view -> startActivity(new Intent(this, TerminalActivity.class)
                    .putExtra("projectId", item.id)));
            row1.addView(files, Ui.weight(1)); row1.addView(Ui.horizontalSpace(this, 7));
            row1.addView(editor, Ui.weight(1)); row1.addView(Ui.horizontalSpace(this, 7));
            row1.addView(terminal, Ui.weight(1));
            card.addView(row1);
            card.addView(Ui.space(this, 8));

            LinearLayout row2 = Ui.row(this);
            TextView settings = Ui.button(this, "Настройки", false);
            settings.setOnClickListener(view -> projectSettingsDialog(item));
            TextView export = Ui.button(this, "Экспорт ZIP", false);
            export.setOnClickListener(view -> {
                pendingProject = item;
                createDocument("application/zip", item.id + ".zip", EXPORT_PROJECT_ZIP);
            });
            TextView delete = Ui.dangerButton(this, "Удалить");
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Удалить проект?")
                    .setMessage(item.name + " и все локальные базы будут удалены.")
                    .setPositiveButton("Удалить", (dialog, which) -> executor.submit(() -> {
                        try {
                            ServerManager.get(this).stop(item.id);
                            projects.delete(item);
                            runOnUiThread(this::showProjects);
                        } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                    }))
                    .setNegativeButton("Отмена", null).show());
            row2.addView(settings, Ui.weight(1)); row2.addView(Ui.horizontalSpace(this, 7));
            row2.addView(export, Ui.weight(1)); row2.addView(Ui.horizontalSpace(this, 7));
            row2.addView(delete, Ui.weight(1));
            card.addView(row2);
            content.addView(card);
            content.addView(Ui.space(this, 10));
        }
    }

    private void createProjectDialog(String type) {
        EditText name = Ui.input(this, "Название проекта", false);
        new AlertDialog.Builder(this)
                .setTitle("Новый " + type + " проект")
                .setView(name)
                .setPositiveButton("Создать", (dialog, which) -> {
                    try {
                        selectedProject = projects.create(name.getText().toString(), type);
                        showProjects();
                    } catch (Exception error) { Ui.error(this, "Не удалось создать проект", error); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void projectSettingsDialog(Project item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), 0);
        EditText name = Ui.input(this, "Название", false); name.setText(item.name);
        EditText entry = Ui.input(this, "Entry point", false); entry.setText(item.entryPoint);
        EditText port = Ui.input(this, "Порт", false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText(String.valueOf(item.preferredPort));
        Spinner type = spinner(Arrays.asList("static", "php", "node", "python", "nginx", "apache"), item.type);
        Switch lan = new Switch(this); lan.setText("Разрешить LAN по умолчанию"); lan.setChecked(item.lanEnabled);
        form.addView(Ui.labeledInput(this, "Название", name)); form.addView(Ui.space(this, 9));
        form.addView(Ui.text(this, "Тип", 12, Ui.MUTED, true)); form.addView(type); form.addView(Ui.space(this, 9));
        form.addView(Ui.labeledInput(this, "Entry point", entry)); form.addView(Ui.space(this, 9));
        form.addView(Ui.labeledInput(this, "Порт", port)); form.addView(Ui.space(this, 9));
        form.addView(lan);
        new AlertDialog.Builder(this).setTitle("Настройки проекта").setView(form)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    try {
                        int parsedPort = Integer.parseInt(port.getText().toString());
                        selectedProject = projects.updateSettings(item, name.getText().toString(),
                                String.valueOf(type.getSelectedItem()), entry.getText().toString(), parsedPort, lan.isChecked());
                        showProjects();
                    } catch (Exception error) { Ui.error(this, "Ошибка настроек", error); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void showServers() {
        currentPage = "servers";
        clear("Server Center", "Runtime Packs, процессы, localhost, LAN и журналы");
        LinearLayout runtimeCard = Ui.card(this);
        runtimeCard.addView(Ui.text(this, "ARM64 Runtime Packs", 18, Ui.TEXT, true));
        runtimeCard.addView(Ui.text(this,
                runtimes.writableExecutionAllowed()
                        ? "Sideload-режим: проверенные Android Bionic ARM64 runtime packs можно импортировать и запускать."
                        : "Modern-режим: Android запрещает запуск загруженных бинарников; runtime должен быть встроен в APK как liboc_*.so.",
                12, runtimes.writableExecutionAllowed() ? Ui.MUTED : Ui.WARNING, false));
        runtimeCard.addView(Ui.space(this, 8));
        for (RuntimeManager.RuntimeInfo info : runtimes.list()) {
            LinearLayout row = Ui.row(this);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(Ui.text(this, info.name.toUpperCase(Locale.ROOT), 14, Ui.TEXT, true));
            labels.addView(Ui.text(this,
                    info.installed ? info.version + " · " + info.abi
                            + (info.problem.isEmpty() ? "" : " · " + info.problem) : info.problem,
                    11, info.installed ? Ui.ACCENT : Ui.WARNING, false));
            row.addView(labels, Ui.weight(1));
            boolean embeddedRuntime = info.directory.equals(runtimes.nativeLibraryDirectory());
            if (info.installed && !embeddedRuntime) {
                TextView remove = Ui.dangerButton(this, "Удалить");
                remove.setOnClickListener(view -> new AlertDialog.Builder(this)
                        .setTitle("Удалить runtime " + info.name + "?")
                        .setPositiveButton("Удалить", (dialog, which) -> executor.submit(() -> {
                            try { runtimes.remove(info.name); runOnUiThread(this::showServers); }
                            catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                        })).setNegativeButton("Отмена", null).show());
                row.addView(remove);
            } else if (info.installed) {
                row.addView(Ui.chip(this, "В APK", Ui.ACCENT));
            } else if (runtimes.writableExecutionAllowed()) {
                TextView install = Ui.button(this, "Импорт", false);
                install.setOnClickListener(view -> {
                    pendingRuntime = info.name;
                    openDocument("application/zip", IMPORT_RUNTIME);
                });
                row.addView(install);
            } else {
                row.addView(Ui.chip(this, "Нужна сборка APK", Ui.WARNING));
            }
            runtimeCard.addView(row);
            runtimeCard.addView(Ui.space(this, 7));
        }
        content.addView(runtimeCard);
        content.addView(Ui.space(this, 12));

        for (Project item : projects.list()) {
            ServerManager manager = ServerManager.get(this);
            ServerManager.ServerHandle handle = manager.get(item.id);
            boolean running = manager.isRunning(item.id);
            LinearLayout card = Ui.card(this);
            LinearLayout header = Ui.row(this);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(Ui.text(this, item.name, 19, Ui.TEXT, true));
            labels.addView(Ui.text(this,
                    running ? "● " + handle.engine.toUpperCase(Locale.ROOT) + " · " + handle.url : "○ Остановлен",
                    12, running ? Ui.ACCENT : Ui.MUTED, true));
            header.addView(labels, Ui.weight(1));
            header.addView(Ui.chip(this, item.type.toUpperCase(Locale.ROOT), Ui.INFO));
            card.addView(header);
            if (handle != null && !handle.warning.isEmpty()) {
                card.addView(Ui.space(this, 7));
                card.addView(Ui.text(this, handle.warning, 12, Ui.WARNING, false));
            }
            card.addView(Ui.space(this, 12));
            LinearLayout actions = Ui.row(this);
            TextView toggle = Ui.button(this, running ? "Остановить" : "Запустить", !running);
            toggle.setOnClickListener(view -> {
                Intent intent = new Intent(this, ServerForegroundService.class)
                        .putExtra("projectId", item.id);
                if (running) intent.setAction(ServerForegroundService.ACTION_STOP);
                else intent.setAction(ServerForegroundService.ACTION_START)
                        .putExtra("port", item.preferredPort).putExtra("lan", item.lanEnabled);
                if (!running && Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                else startService(intent);
                content.postDelayed(this::showServers, 600);
            });
            TextView preview = Ui.button(this, "Открыть", false);
            preview.setEnabled(running);
            preview.setAlpha(running ? 1f : .45f);
            preview.setOnClickListener(view -> {
                ServerManager.ServerHandle current = manager.get(item.id);
                if (current != null) startActivity(new Intent(this, PreviewActivity.class)
                        .putExtra("title", item.name).putExtra("url", current.url));
            });
            TextView logs = Ui.button(this, "Логи", false);
            logs.setOnClickListener(view -> showLogs(item));
            actions.addView(toggle, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
            actions.addView(preview, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
            actions.addView(logs, Ui.weight(1));
            card.addView(actions);
            content.addView(card);
            content.addView(Ui.space(this, 10));
        }
    }

    private void showLogs(Project item) {
        List<String> lines = ServerManager.get(this).logs(item.id);
        TextView output = Ui.text(this, lines.isEmpty() ? "Лог пуст" : String.join("\n", lines), 12, Ui.TEXT, false);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        scroll.addView(output);
        new AlertDialog.Builder(this).setTitle("Логи · " + item.name).setView(scroll)
                .setPositiveButton("Закрыть", null).show();
    }

    private void showDatabases() {
        currentPage = "databases";
        clear("Database Center", "Создание, SQL, таблицы, backup, restore и доступ ИИ");
        Project active = project();
        if (active == null) {
            emptyCard("Нет проекта", "Сначала создайте проект.");
            return;
        }
        LinearLayout selectorCard = Ui.card(this);
        selectorCard.addView(Ui.text(this, "Проект", 12, Ui.MUTED, true));
        Spinner projectSpinner = spinner(projects.list(), active);
        projectSpinner.setOnItemSelectedListener(new ItemSelected(value -> {
            Project chosen = (Project) value;
            if (selectedProject == null || !selectedProject.id.equals(chosen.id)) {
                selectedProject = chosen;
                content.post(this::showDatabases);
            }
        }));
        selectorCard.addView(projectSpinner);
        selectorCard.addView(Ui.space(this, 10));
        TextView create = Ui.button(this, "Создать базу данных", true);
        create.setOnClickListener(view -> createDatabaseDialog(active));
        selectorCard.addView(create);
        content.addView(selectorCard);
        content.addView(Ui.space(this, 12));

        List<DatabaseManager.DbInfo> list = databases.list(active);
        if (list.isEmpty()) {
            emptyCard("Баз пока нет", "SQLite работает сразу. MariaDB и PostgreSQL используют runtime packs.");
            return;
        }
        for (DatabaseManager.DbInfo info : list) {
            LinearLayout card = Ui.card(this);
            LinearLayout header = Ui.row(this);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(Ui.text(this, info.name, 19, Ui.TEXT, true));
            labels.addView(Ui.text(this,
                    info.embedded() ? "SQLite · " + formatBytes(info.file.length())
                            : info.engine.toUpperCase(Locale.ROOT) + " · " + info.host + ":" + info.port,
                    12, Ui.MUTED, false));
            header.addView(labels, Ui.weight(1));
            header.addView(Ui.chip(this, info.engine.toUpperCase(Locale.ROOT),
                    info.embedded() ? Ui.ACCENT : Ui.WARNING));
            card.addView(header);
            card.addView(Ui.space(this, 9));
            LinearLayout row1 = Ui.row(this);
            TextView browse = Ui.button(this, "Таблицы", true);
            browse.setEnabled(true); browse.setAlpha(1f);
            browse.setOnClickListener(view -> startActivity(new Intent(this, DatabaseBrowserActivity.class)
                    .putExtra("projectId", active.id).putExtra("databaseId", info.id)));
            TextView sql = Ui.button(this, "SQL", false);
            sql.setOnClickListener(view -> startActivity(new Intent(this, SqlConsoleActivity.class)
                    .putExtra("projectId", active.id).putExtra("databaseId", info.id)));
            TextView credentials = Ui.button(this, "Доступ", false);
            credentials.setOnClickListener(view -> credentialsDialog(info));
            row1.addView(browse, Ui.weight(1)); row1.addView(Ui.horizontalSpace(this, 7));
            row1.addView(sql, Ui.weight(1)); row1.addView(Ui.horizontalSpace(this, 7));
            row1.addView(credentials, Ui.weight(1));
            card.addView(row1);
            if (!info.embedded()) {
                card.addView(Ui.space(this, 8));
                LinearLayout serverRow = Ui.row(this);
                DatabaseServerManager databaseServers = DatabaseServerManager.get(this);
                boolean dbRunning = databaseServers.running(info.id);
                TextView toggleDb = Ui.button(this, dbRunning ? "Остановить DB" : "Запустить DB", !dbRunning);
                toggleDb.setOnClickListener(view -> executor.submit(() -> {
                    try {
                        if (databaseServers.running(info.id)) databaseServers.stop(info.id);
                        else databaseServers.start(active, info);
                        Intent keepAlive = new Intent(this, ServerForegroundService.class)
                                .setAction(databaseServers.any()
                                        ? ServerForegroundService.ACTION_KEEP_ALIVE
                                        : ServerForegroundService.ACTION_REFRESH);
                        if (Build.VERSION.SDK_INT >= 26 && databaseServers.any()) startForegroundService(keepAlive);
                        else startService(keepAlive);
                        runOnUiThread(this::showDatabases);
                    } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка DB Server", error)); }
                }));
                TextView dbLogs = Ui.button(this, "DB логи", false);
                dbLogs.setOnClickListener(view -> showDatabaseLogs(info));
                serverRow.addView(toggleDb, Ui.weight(1));
                serverRow.addView(Ui.horizontalSpace(this, 7));
                serverRow.addView(dbLogs, Ui.weight(1));
                card.addView(serverRow);
            }
            card.addView(Ui.space(this, 8));
            LinearLayout row2 = Ui.row(this);
            TextView backup = Ui.button(this, "Backup", false);
            backup.setEnabled(true); backup.setAlpha(1f);
            backup.setOnClickListener(view -> {
                pendingDatabase = info;
                createDocument(info.embedded() ? "application/octet-stream" : "application/sql",
                        info.name + (info.embedded() ? "-backup.db" : "-backup.sql"), EXPORT_DATABASE_BACKUP);
            });
            TextView restore = Ui.button(this, "Restore", false);
            restore.setEnabled(true); restore.setAlpha(1f);
            restore.setOnClickListener(view -> {
                pendingDatabase = info;
                openDocument(info.embedded() ? "application/octet-stream" : "*/*", RESTORE_DATABASE_BACKUP);
            });
            TextView exportSql = Ui.button(this, "SQL export", false);
            exportSql.setEnabled(true); exportSql.setAlpha(1f);
            exportSql.setOnClickListener(view -> {
                pendingDatabase = info;
                createDocument("application/sql", info.name + ".sql", EXPORT_DATABASE_SQL);
            });
            TextView delete = Ui.dangerButton(this, "Удалить");
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Удалить базу?").setMessage(info.name)
                    .setPositiveButton("Удалить", (dialog, which) -> executor.submit(() -> {
                        try { databases.delete(active, info); runOnUiThread(this::showDatabases); }
                        catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
                    })).setNegativeButton("Отмена", null).show());
            row2.addView(backup, Ui.weight(1)); row2.addView(Ui.horizontalSpace(this, 6));
            row2.addView(restore, Ui.weight(1)); row2.addView(Ui.horizontalSpace(this, 6));
            row2.addView(exportSql, Ui.weight(1)); row2.addView(Ui.horizontalSpace(this, 6));
            row2.addView(delete, Ui.weight(1));
            card.addView(row2);
            content.addView(card);
            content.addView(Ui.space(this, 10));
        }
    }

    private void createDatabaseDialog(Project active) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        EditText name = Ui.input(this, "Название базы", false);
        Spinner engine = spinner(Arrays.asList("sqlite", "mariadb", "postgres"), "sqlite");
        form.addView(Ui.labeledInput(this, "Название", name));
        form.addView(Ui.space(this, 10));
        form.addView(Ui.text(this, "Движок", 12, Ui.MUTED, true));
        form.addView(engine);
        new AlertDialog.Builder(this).setTitle("Новая база данных").setView(form)
                .setPositiveButton("Создать", (dialog, which) -> executor.submit(() -> {
                    try {
                        DatabaseManager.DbInfo created = databases.create(
                                active, name.getText().toString(), String.valueOf(engine.getSelectedItem()), "127.0.0.1", 0);
                        runOnUiThread(() -> {
                            credentialsDialog(created);
                            showDatabases();
                        });
                    } catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка создания", error)); }
                })).setNegativeButton("Отмена", null).show();
    }

    private void credentialsDialog(DatabaseManager.DbInfo info) {
        String text = "ENGINE=" + info.engine + "\n"
                + "DB_HOST=" + info.host + "\n"
                + "DB_PORT=" + info.port + "\n"
                + "DB_DATABASE=" + info.name + "\n"
                + "DB_USERNAME=" + info.user + "\n"
                + "DB_PASSWORD=" + info.password
                + (info.embedded() ? "\nDB_PATH=" + info.file.getAbsolutePath() : "");
        TextView value = Ui.text(this, text, 13, Ui.TEXT, false);
        value.setTextIsSelectable(true);
        value.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        new AlertDialog.Builder(this).setTitle("Доступ · " + info.name).setView(value)
                .setPositiveButton("Копировать", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Database credentials", text));
                    Ui.toast(this, "Скопировано");
                }).setNegativeButton("Закрыть", null).show();
    }

    private void showDatabaseLogs(DatabaseManager.DbInfo info) {
        List<String> lines = DatabaseServerManager.get(this).logs(info.id);
        TextView output = Ui.text(this, lines.isEmpty() ? "Лог пуст" : String.join("\n", lines), 12, Ui.TEXT, false);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        scroll.addView(output);
        new AlertDialog.Builder(this).setTitle("DB логи · " + info.name).setView(scroll)
                .setPositiveButton("Закрыть", null).show();
    }

    private void showProviders() {
        currentPage = "providers";
        clear("Provider Hub", "OpenCode Go, облачные, локальные и любые совместимые endpoints");
        TextView add = Ui.button(this, "Добавить Custom Provider", true);
        add.setOnClickListener(view -> providerDialog(new Provider(
                "custom-" + System.currentTimeMillis(), "Custom Provider", Provider.OPENAI, "", "", "")));
        content.addView(add);
        content.addView(Ui.space(this, 12));
        for (Provider item : providers.list()) {
            LinearLayout card = Ui.card(this);
            LinearLayout header = Ui.row(this);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(Ui.text(this, item.name, 18, Ui.TEXT, true));
            labels.addView(Ui.text(this,
                    item.type.toUpperCase(Locale.ROOT) + " · "
                            + (item.model.isEmpty() ? "модель не выбрана" : item.model),
                    12, Ui.MUTED, false));
            header.addView(labels, Ui.weight(1));
            Switch enabled = new Switch(this);
            enabled.setChecked(item.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                item.enabled = checked;
                providers.upsert(item);
            });
            header.addView(enabled);
            card.addView(header);
            card.addView(Ui.space(this, 6));
            card.addView(Ui.text(this,
                    item.baseUrl.isEmpty() ? "Base URL не указан" : item.baseUrl,
                    11, item.baseUrl.isEmpty() ? Ui.WARNING : Ui.MUTED, false));
            card.addView(Ui.space(this, 11));
            LinearLayout actions = Ui.row(this);
            TextView edit = Ui.button(this, "Настроить", true);
            edit.setOnClickListener(view -> providerDialog(item));
            TextView models = Ui.button(this, "Модели", false);
            models.setOnClickListener(view -> loadModels(item));
            TextView test = Ui.button(this, "Тест", false);
            test.setOnClickListener(view -> testProvider(item));
            actions.addView(edit, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
            actions.addView(models, Ui.weight(1)); actions.addView(Ui.horizontalSpace(this, 7));
            actions.addView(test, Ui.weight(1));
            card.addView(actions);
            content.addView(card);
            content.addView(Ui.space(this, 10));
        }
    }

    private void providerDialog(Provider source) {
        Provider item = new Provider(source.id, source.name, source.type, source.baseUrl,
                source.model, source.apiKey, source.headersJson, source.enabled);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 16));
        EditText id = Ui.input(this, "provider-id", false); id.setText(item.id);
        EditText name = Ui.input(this, "Название", false); name.setText(item.name);
        Spinner type = spinner(Arrays.asList(Provider.OPENAI, Provider.ANTHROPIC, Provider.GEMINI), item.type);
        EditText base = Ui.input(this, "https://…/v1", false); base.setText(item.baseUrl);
        EditText model = Ui.input(this, "model-id", false); model.setText(item.model);
        EditText key = Ui.input(this, "API key", false); key.setText(item.apiKey);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText headers = Ui.input(this, "{\"Header\":\"Value\"}", true); headers.setText(item.headersJson);
        form.addView(Ui.labeledInput(this, "ID", id)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Название", name)); form.addView(Ui.space(this, 8));
        form.addView(Ui.text(this, "Протокол", 12, Ui.MUTED, true)); form.addView(type); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Base URL", base)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Model ID", model)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "API key — хранится зашифрованно", key)); form.addView(Ui.space(this, 8));
        form.addView(Ui.labeledInput(this, "Дополнительные HTTP-заголовки JSON", headers));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Настройка провайдера").setView(scroll)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    try {
                        new JSONObject(headers.getText().toString().trim().isEmpty() ? "{}" : headers.getText().toString());
                        item.id = id.getText().toString(); item.name = name.getText().toString();
                        item.type = String.valueOf(type.getSelectedItem()); item.baseUrl = base.getText().toString();
                        item.model = model.getText().toString(); item.apiKey = key.getText().toString();
                        item.headersJson = headers.getText().toString();
                        providers.upsert(item); selectedProvider = item; showProviders();
                    } catch (Exception error) { Ui.error(this, "Провайдер не сохранён", error); }
                }).setNegativeButton("Отмена", null)
                .setNeutralButton("Удалить", (dialog, which) -> {
                    if (!source.id.startsWith("custom-") && !"custom".equals(source.id)) {
                        Ui.toast(this, "Встроенный preset можно отключить, но не удалять");
                    } else { providers.delete(source.id); showProviders(); }
                }).show();
    }

    private void loadModels(Provider item) {
        progressDialog("Получение моделей…", () -> {
            List<String> models = new ProviderClient().listModels(item);
            runOnUiThread(() -> {
                if (models.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle("Модели")
                            .setMessage("Этот endpoint не предоставляет список моделей. Укажите Model ID вручную.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                String[] values = models.toArray(new String[0]);
                new AlertDialog.Builder(this).setTitle("Выберите модель")
                        .setItems(values, (dialog, which) -> {
                            item.model = values[which]; providers.upsert(item); showProviders();
                        }).setNegativeButton("Закрыть", null).show();
            });
        });
    }

    private void testProvider(Provider item) {
        progressDialog("Проверка соединения…", () -> {
            String result = new ProviderClient().test(item);
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(item.name)
                    .setMessage(result).setPositiveButton("OK", null).show());
        });
    }

    private void showMore() {
        currentPage = "more";
        clear("Инструменты", "Терминал, Timeline, безопасность и обслуживание");
        Project active = project();
        LinearLayout card = Ui.card(this);
        TextView terminal = Ui.button(this, "Открыть терминал проекта", true);
        terminal.setEnabled(active != null); terminal.setAlpha(active == null ? .45f : 1f);
        terminal.setOnClickListener(view -> startActivity(new Intent(this, TerminalActivity.class)
                .putExtra("projectId", active.id)));
        TextView timeline = Ui.button(this, "Timeline и журнал действий", false);
        timeline.setEnabled(active != null); timeline.setAlpha(active == null ? .45f : 1f);
        timeline.setOnClickListener(view -> startActivity(new Intent(this, TimelineActivity.class)
                .putExtra("projectId", active.id)));
        TextView integrations = Ui.button(this, "Integration Center · OpenCode / MCP / Git / Deploy", true);
        integrations.setEnabled(active != null); integrations.setAlpha(active == null ? .45f : 1f);
        integrations.setOnClickListener(view -> startActivity(new Intent(this, IntegrationCenterActivity.class)
                .putExtra("projectId", active.id)));
        TextView resetProviders = Ui.button(this, "Восстановить каталог провайдеров", false);
        resetProviders.setOnClickListener(view -> {
            providers.resetCatalogPreservingKeys();
            Ui.toast(this, "Каталог восстановлен, ключи сохранены");
        });
        card.addView(integrations); card.addView(Ui.space(this, 8));
        card.addView(terminal); card.addView(Ui.space(this, 8));
        card.addView(timeline); card.addView(Ui.space(this, 8));
        card.addView(resetProviders);
        content.addView(card);
        content.addView(Ui.space(this, 12));

        LinearLayout status = Ui.card(this);
        status.addView(Ui.text(this, "Состояние приложения", 18, Ui.TEXT, true));
        status.addView(Ui.space(this, 8));
        status.addView(Ui.text(this,
                "Package: " + getPackageName() + "\n"
                        + "Проектов: " + projects.list().size() + "\n"
                        + "Провайдеров: " + providers.list().size() + "\n"
                        + "Runtime root: " + runtimes.root().getAbsolutePath() + "\n"
                        + "Projects root: " + new File(getFilesDir(), "projects").getAbsolutePath(),
                12, Ui.MUTED, false));
        status.addView(Ui.space(this, 10));
        TextView stop = Ui.dangerButton(this, "Аварийно остановить всё");
        stop.setOnClickListener(view -> {
            ServerManager.get(this).stopAll();
            Ui.toast(this, "Процессы остановлены");
        });
        status.addView(stop);
        content.addView(status);
    }

    private void emptyCard(String title, String text) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, title, 18, Ui.TEXT, true));
        card.addView(Ui.space(this, 5));
        card.addView(Ui.text(this, text, 13, Ui.MUTED, false));
        content.addView(card);
    }

    private void refresh() {
        switch (currentPage) {
            case "projects": showProjects(); break;
            case "servers": showServers(); break;
            case "databases": showDatabases(); break;
            case "providers": showProviders(); break;
            case "more": showMore(); break;
            default: showAgent();
        }
    }

    private void progressDialog(String message, ThrowingRunnable task) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(message).setCancelable(false).create();
        progress.show();
        executor.submit(() -> {
            try { task.run(); }
            catch (Exception error) { runOnUiThread(() -> Ui.error(this, "Ошибка", error)); }
            finally { runOnUiThread(progress::dismiss); }
        });
    }

    private void openDocument(String mime, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType(mime).addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, requestCode);
    }

    private void createDocument(String mime, String name, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, requestCode);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT_PROJECT_ZIP) importProject(uri);
        else if (requestCode == EXPORT_PROJECT_ZIP) exportProject(uri);
        else if (requestCode == IMPORT_RUNTIME) importRuntime(uri);
        else if (requestCode == EXPORT_DATABASE_BACKUP) exportDatabaseBackup(uri);
        else if (requestCode == RESTORE_DATABASE_BACKUP) restoreDatabase(uri);
        else if (requestCode == EXPORT_DATABASE_SQL) exportDatabaseSql(uri);
    }

    private void importProject(Uri uri) {
        progressDialog("Импорт проекта…", () -> {
            String displayName = displayName(uri);
            String name = displayName.replaceFirst("(?i)\\.zip$", "");
            Project imported = projects.create(name, "static");
            projects.clearForImport(imported);
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Не удалось открыть ZIP");
                ZipUtil.extract(input, imported.root);
            }
            String detected = ProjectManager.detectType(imported.root);
            imported = projects.updateSettings(imported, name, detected,
                    defaultEntry(detected), defaultPort(detected), false);
            selectedProject = imported;
            runOnUiThread(this::showProjects);
        });
    }

    private void exportProject(Uri uri) {
        Project target = pendingProject;
        if (target == null) return;
        progressDialog("Экспорт проекта…", () -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Не удалось открыть файл");
                ZipUtil.packContents(target.root, output);
            }
            runOnUiThread(() -> Ui.toast(this, "ZIP экспортирован"));
        });
    }

    private void importRuntime(Uri uri) {
        String runtime = pendingRuntime;
        if (runtime == null) return;
        progressDialog("Установка runtime " + runtime + "…", () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Не удалось открыть runtime pack");
                runtimes.importPack(input, runtime);
            }
            runOnUiThread(this::showServers);
        });
    }

    private void exportDatabaseBackup(Uri uri) {
        Project active = project();
        DatabaseManager.DbInfo info = pendingDatabase;
        if (active == null || info == null) return;
        progressDialog("Создание backup…", () -> {
            File backup = databases.backup(active, info);
            try (InputStream input = new java.io.FileInputStream(backup);
                 OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Не удалось открыть файл");
                copy(input, output);
            }
            runOnUiThread(() -> Ui.toast(this, "Backup сохранён"));
        });
    }

    private void restoreDatabase(Uri uri) {
        Project active = project();
        DatabaseManager.DbInfo info = pendingDatabase;
        if (active == null || info == null) return;
        new AlertDialog.Builder(this).setTitle("Восстановить базу?")
                .setMessage("Перед восстановлением автоматически создаётся backup текущей базы.")
                .setPositiveButton("Восстановить", (dialog, which) -> progressDialog("Restore…", () -> {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IllegalStateException("Не удалось открыть backup");
                        databases.restore(active, info, input);
                    }
                    runOnUiThread(this::showDatabases);
                })).setNegativeButton("Отмена", null).show();
    }

    private void exportDatabaseSql(Uri uri) {
        Project active = project();
        DatabaseManager.DbInfo info = pendingDatabase;
        if (active == null || info == null) return;
        progressDialog("Экспорт SQL…", () -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Не удалось открыть SQL файл");
                databases.exportSql(active, info, output);
            }
            runOnUiThread(() -> Ui.toast(this, "SQL экспортирован"));
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        String segment = uri.getLastPathSegment();
        return segment == null ? "Imported Project" : segment;
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
    }

    private static long folderSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long size = 0L;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) size += folderSize(child);
        return size;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int index = -1;
        while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }

    private static String defaultEntry(String type) {
        if ("php".equals(type)) return "index.php";
        if ("node".equals(type)) return "server.js";
        if ("python".equals(type)) return "app.py";
        return "index.html";
    }

    private static int defaultPort(String type) {
        if ("node".equals(type)) return 3000;
        if ("python".equals(type)) return 8000;
        return 8080;
    }

    private interface ThrowingRunnable { void run() throws Exception; }
    private interface SelectionListener { void selected(Object value); }

    private static final class ItemSelected implements AdapterView.OnItemSelectedListener {
        private final SelectionListener listener;
        ItemSelected(SelectionListener listener) { this.listener = listener; }
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            listener.selected(parent.getItemAtPosition(position));
        }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}
