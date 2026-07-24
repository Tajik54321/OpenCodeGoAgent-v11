package com.qandil.opencodego.integration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/** Client for an OpenCode `opencode serve` instance. */
public final class OpenCodeServerClient {
    public static final class Profile {
        public String name = "OpenCode Server";
        public String baseUrl = "http://127.0.0.1:4096";
        public String username = "opencode";
        public String password = "";
        public String directory = "";
    }

    private final Profile profile;

    public OpenCodeServerClient(Profile profile) {
        this.profile = profile;
    }

    public JSONObject health() throws Exception {
        return getObject("/global/health");
    }

    public Object openApiDocument() throws Exception {
        return request("GET", "/doc", null).json();
    }

    public JSONArray projects() throws Exception {
        return request("GET", "/project", null).array();
    }

    public JSONObject currentProject() throws Exception {
        return getObject("/project/current");
    }

    public JSONObject path() throws Exception {
        return getObject("/path");
    }

    public JSONObject vcs() throws Exception {
        return getObject("/vcs");
    }

    public JSONObject config() throws Exception {
        return getObject("/config");
    }

    public JSONObject providers() throws Exception {
        return getObject("/config/providers");
    }

    public JSONArray sessions() throws Exception {
        return request("GET", "/session", null).array();
    }

    public JSONObject createSession(String title) throws Exception {
        JSONObject body = new JSONObject();
        if (title != null && !title.trim().isEmpty()) body.put("title", title.trim());
        return request("POST", "/session", body).object();
    }

    public Object messages(String sessionId) throws Exception {
        return request("GET", "/session/" + encodeSegment(sessionId) + "/message", null).json();
    }

    public Object prompt(String sessionId, String text, String agent, String providerId, String modelId) throws Exception {
        JSONObject body = new JSONObject().put("text", text == null ? "" : text);
        if (agent != null && !agent.isEmpty()) body.put("agent", agent);
        if (providerId != null && !providerId.isEmpty() && modelId != null && !modelId.isEmpty()) {
            body.put("model", new JSONObject().put("providerID", providerId).put("modelID", modelId));
        }
        return request("POST", "/session/" + encodeSegment(sessionId) + "/message", body).json();
    }

    public void promptAsync(String sessionId, String text, String agent) throws Exception {
        JSONObject body = new JSONObject().put("text", text == null ? "" : text);
        if (agent != null && !agent.isEmpty()) body.put("agent", agent);
        request("POST", "/session/" + encodeSegment(sessionId) + "/prompt_async", body);
    }

    public Object shell(String sessionId, String command) throws Exception {
        return request("POST", "/session/" + encodeSegment(sessionId) + "/shell",
                new JSONObject().put("command", command == null ? "" : command)).json();
    }

    public Object generic(String method, String path, JSONObject body) throws Exception {
        return request(method, path, body).json();
    }

    private JSONObject getObject(String path) throws Exception {
        return request("GET", path, null).object();
    }

    private HttpJson.Response request(String method, String path, JSONObject body) throws Exception {
        Map<String, String> headers = profile.directory == null || profile.directory.isEmpty()
                ? Collections.emptyMap()
                : Collections.singletonMap("x-opencode-directory", profile.directory);
        return HttpJson.request(method, HttpJson.joinUrl(profile.baseUrl, path),
                body == null ? null : body.toString(), headers, profile.username, profile.password);
    }

    private static String encodeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid OpenCode identifier");
        }
        return value;
    }
}
