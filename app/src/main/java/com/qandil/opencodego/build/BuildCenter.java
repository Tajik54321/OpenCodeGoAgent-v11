package com.qandil.opencodego.build;

import android.content.Context;
import com.qandil.opencodego.integration.HttpJson;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local Gradle and GitHub Actions Android build orchestration. */
public final class BuildCenter {
    private final RuntimeManager runtimes;
    private final ProcessSupervisor processes;

    public BuildCenter(Context context) {
        runtimes = RuntimeManager.get(context);
        processes = ProcessSupervisor.get(context);
    }

    public JSONObject inspect(Project project) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gradleWrapper", new File(project.root, "gradlew").isFile());
        values.put("settings", new File(project.root, "settings.gradle.kts").isFile()
                || new File(project.root, "settings.gradle").isFile());
        values.put("androidProject", new File(project.root, "app/build.gradle.kts").isFile()
                || new File(project.root, "app/build.gradle").isFile());
        values.put("jdkRuntime", runtimes.installed("jdk"));
        values.put("gradleRuntime", runtimes.installed("gradle"));
        return new JSONObject(values);
    }

    public ProcessSupervisor.Result localAndroidBuild(Project project, String variant, boolean bundle, int timeoutSeconds) throws Exception {
        String safeVariant = normalizeVariant(variant);
        File wrapper = new File(project.root, "gradlew");
        List<String> command = new ArrayList<>();
        if (wrapper.isFile() && wrapper.canExecute()) command.add(wrapper.getAbsolutePath());
        else {
            File gradle = runtimes.executableAny("gradle", "gradle");
            if (gradle == null) throw new IllegalStateException("Gradle runtime or executable gradlew is required");
            command.add(gradle.getAbsolutePath());
        }
        command.add("--no-daemon");
        command.add("--stacktrace");
        command.add((bundle ? "bundle" : "assemble") + safeVariant);
        Map<String, String> environment = new LinkedHashMap<>();
        File java = runtimes.executableAny("jdk", "java");
        if (java != null) {
            File bin = java.getParentFile();
            if (bin != null && bin.getParentFile() != null) environment.put("JAVA_HOME", bin.getParentFile().getAbsolutePath());
        }
        return processes.run(project.id, "Android build " + safeVariant, command, project.root,
                environment, Math.max(60, timeoutSeconds));
    }

    public JSONObject dispatchGitHub(String repository, String workflow, String ref,
                                     JSONObject inputs, String token) throws Exception {
        validateRepo(repository);
        if (workflow == null || !workflow.matches("[A-Za-z0-9._/-]{1,200}")) throw new IllegalArgumentException("Invalid workflow");
        JSONObject body = new JSONObject().put("ref", ref == null || ref.isEmpty() ? "main" : ref);
        if (inputs != null) body.put("inputs", inputs);
        HttpJson.Response response = HttpJson.request("POST",
                "https://api.github.com/repos/" + repository + "/actions/workflows/" + workflow + "/dispatches",
                body.toString(), githubHeaders(token), "", "");
        return new JSONObject().put("status", response.code).put("dispatched", true);
    }

    public JSONArray workflowRuns(String repository, String workflow, String branch, String token) throws Exception {
        validateRepo(repository);
        String path = "https://api.github.com/repos/" + repository + "/actions/workflows/" + workflow + "/runs?per_page=20";
        if (branch != null && !branch.isEmpty()) path += "&branch=" + urlQuery(branch);
        JSONObject response = HttpJson.get(path, githubHeaders(token), "", "").object();
        JSONArray runs = response.optJSONArray("workflow_runs");
        return runs == null ? new JSONArray() : runs;
    }

    public JSONArray artifacts(String repository, long runId, String token) throws Exception {
        validateRepo(repository);
        JSONObject response = HttpJson.get("https://api.github.com/repos/" + repository
                + "/actions/runs/" + runId + "/artifacts?per_page=100", githubHeaders(token), "", "").object();
        JSONArray artifacts = response.optJSONArray("artifacts");
        return artifacts == null ? new JSONArray() : artifacts;
    }

    public JSONObject cancelRun(String repository, long runId, String token) throws Exception {
        validateRepo(repository);
        HttpJson.Response response = HttpJson.request("POST", "https://api.github.com/repos/" + repository
                + "/actions/runs/" + runId + "/cancel", "{}", githubHeaders(token), "", "");
        return new JSONObject().put("status", response.code).put("cancelled", true);
    }

    public String artifactDownloadUrl(String repository, long artifactId) {
        validateRepo(repository);
        return "https://api.github.com/repos/" + repository + "/actions/artifacts/" + artifactId + "/zip";
    }

    private static Map<String, String> githubHeaders(String token) {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("GitHub token is required");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private static String normalizeVariant(String value) {
        String variant = value == null || value.trim().isEmpty() ? "Debug" : value.trim();
        if (!variant.matches("[A-Za-z0-9]{1,80}")) throw new IllegalArgumentException("Invalid build variant");
        return Character.toUpperCase(variant.charAt(0)) + variant.substring(1);
    }
    private static void validateRepo(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Repository must be owner/name");
    }
    private static String urlQuery(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
}
