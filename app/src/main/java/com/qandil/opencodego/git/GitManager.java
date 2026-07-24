package com.qandil.opencodego.git;

import android.content.Context;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Git CLI adapter backed by the verified ARM64 runtime pack. */
public final class GitManager {
    private final RuntimeManager runtimes;
    private final ProcessSupervisor processes;

    public GitManager(Context context) {
        runtimes = RuntimeManager.get(context);
        processes = ProcessSupervisor.get(context);
    }

    public boolean available() { return runtimes.installed("git"); }

    public JSONObject status(Project project) throws Exception {
        String output = run(project, 30, "status", "--porcelain=v2", "--branch");
        JSONObject result = new JSONObject().put("raw", output);
        JSONArray changes = new JSONArray();
        String branch = "";
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("# branch.head ")) branch = line.substring(14).trim();
            else if (!line.trim().isEmpty() && !line.startsWith("#")) changes.put(line);
        }
        return result.put("branch", branch).put("changes", changes);
    }

    public String init(Project project) throws Exception { return run(project, 30, "init"); }
    public String addAll(Project project) throws Exception { return run(project, 60, "add", "--all"); }

    public String commit(Project project, String message) throws Exception {
        if (message == null || message.trim().isEmpty()) throw new IllegalArgumentException("Commit message is empty");
        return run(project, 120, "commit", "-m", message.trim());
    }

    public String branch(Project project, String name) throws Exception {
        validateRef(name); return run(project, 30, "branch", name);
    }

    public String checkout(Project project, String name, boolean create) throws Exception {
        validateRef(name); return create ? run(project, 60, "checkout", "-b", name) : run(project, 60, "checkout", name);
    }

    public String pull(Project project, String remote, String branch) throws Exception {
        validateRemote(remote); validateRef(branch); return run(project, 180, "pull", remote, branch);
    }

    public String push(Project project, String remote, String branch, boolean setUpstream) throws Exception {
        validateRemote(remote); validateRef(branch);
        return setUpstream ? run(project, 180, "push", "-u", remote, branch) : run(project, 180, "push", remote, branch);
    }

    public String setRemote(Project project, String name, String url) throws Exception {
        validateRemote(name);
        if (url == null || !(url.startsWith("https://") || url.startsWith("ssh://") || url.matches("[^@]+@[^:]+:.+"))) {
            throw new IllegalArgumentException("Unsupported remote URL");
        }
        try { return run(project, 30, "remote", "set-url", name, url); }
        catch (Exception missing) { return run(project, 30, "remote", "add", name, url); }
    }

    public String log(Project project, int count) throws Exception {
        int safe = Math.max(1, Math.min(100, count));
        return run(project, 30, "log", "--oneline", "--decorate", "--graph", "-n", String.valueOf(safe));
    }

    public String diff(Project project, boolean staged) throws Exception {
        return staged ? run(project, 60, "diff", "--cached", "--no-ext-diff")
                : run(project, 60, "diff", "--no-ext-diff");
    }

    public String cloneInto(Project project, String url) throws Exception {
        if (url == null || !(url.startsWith("https://") || url.startsWith("ssh://") || url.matches("[^@]+@[^:]+:.+"))) {
            throw new IllegalArgumentException("Unsupported clone URL");
        }
        File parent = project.root.getParentFile();
        if (parent == null) throw new IllegalStateException("Project parent missing");
        return runIn(project.id, parent, 600, "clone", url, project.root.getName());
    }

    public String run(Project project, int timeoutSeconds, String... arguments) throws Exception {
        return runIn(project.id, project.root, timeoutSeconds, arguments);
    }

    private String runIn(String projectId, File directory, int timeoutSeconds, String... arguments) throws Exception {
        File git = runtimes.executable("git");
        if (git == null) throw new IllegalStateException("Git runtime is not installed");
        List<String> command = new ArrayList<>();
        command.add(git.getAbsolutePath());
        command.add("-c"); command.add("credential.helper=");
        command.add("-c"); command.add("core.askPass=");
        command.addAll(Arrays.asList(arguments));
        ProcessSupervisor.Result result = processes.run(projectId, "git " + arguments[0], command,
                directory, Collections.singletonMap("GIT_TERMINAL_PROMPT", "0"), timeoutSeconds);
        if (result.timedOut) throw new IllegalStateException("Git command timed out\n" + result.output);
        if (result.exitCode != 0) throw new IllegalStateException(result.output);
        return result.output;
    }

    private static void validateRef(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._/-]{1,200}") || value.contains("..") || value.endsWith("/")) {
            throw new IllegalArgumentException("Invalid Git ref");
        }
    }

    private static void validateRemote(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid remote name");
    }
}
