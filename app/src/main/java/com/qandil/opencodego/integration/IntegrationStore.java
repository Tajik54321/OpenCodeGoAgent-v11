package com.qandil.opencodego.integration;

import android.content.Context;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.security.SecureStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stores non-secret integration metadata on disk and secrets in Android Keystore storage. */
public final class IntegrationStore {
    public static final class OpenCodeProfile {
        public String id;
        public String name;
        public String baseUrl;
        public String username;
        public String password;
        public String directory;
        public boolean enabled;

        public OpenCodeProfile() {
            id = UUID.randomUUID().toString();
            name = "OpenCode Server";
            baseUrl = "http://127.0.0.1:4096";
            username = "opencode";
            password = "";
            directory = "";
            enabled = true;
        }

        JSONObject json() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id);
                value.put("name", name);
                value.put("baseUrl", baseUrl);
                value.put("username", username);
                value.put("directory", directory);
                value.put("enabled", enabled);
                return value;
            } catch (Exception error) {
                throw new IllegalStateException("Unable to create OpenCode profile JSON", error);
            }
        }
    }

    public static final class McpProfile {
        public String id;
        public String name;
        public String url;
        public String headersJson;
        public boolean enabled;

        public McpProfile() {
            id = UUID.randomUUID().toString();
            name = "MCP Server";
            url = "http://127.0.0.1:3000/mcp";
            headersJson = "{}";
            enabled = true;
        }

        JSONObject json() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id);
                value.put("name", name);
                value.put("url", url);
                value.put("headers", headersJson);
                value.put("enabled", enabled);
                return value;
            } catch (Exception error) {
                throw new IllegalStateException("Unable to create MCP profile JSON", error);
            }
        }
    }

    private final File file;
    private final SecureStore secure;

    public IntegrationStore(Context context) {
        file = new File(context.getFilesDir(), "integrations.json");
        secure = new SecureStore(context);
    }

    public synchronized List<OpenCodeProfile> openCodeProfiles() {
        List<OpenCodeProfile> result = new ArrayList<>();
        JSONArray array = root().optJSONArray("opencode");
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            OpenCodeProfile profile = new OpenCodeProfile();
            profile.id = value.optString("id", profile.id);
            profile.name = value.optString("name", profile.name);
            profile.baseUrl = value.optString("baseUrl", profile.baseUrl);
            profile.username = value.optString("username", profile.username);
            profile.directory = value.optString("directory", "");
            profile.enabled = value.optBoolean("enabled", true);
            profile.password = secure.get("integration.opencodeserver." + profile.id, "");
            result.add(profile);
        }
        return result;
    }

    public synchronized List<McpProfile> mcpProfiles() {
        List<McpProfile> result = new ArrayList<>();
        JSONArray array = root().optJSONArray("mcp");
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            McpProfile profile = new McpProfile();
            profile.id = value.optString("id", profile.id);
            profile.name = value.optString("name", profile.name);
            profile.url = value.optString("url", profile.url);
            profile.headersJson = secure.get("integration.mcp.headers." + profile.id,
                    value.optString("headers", "{}"));
            profile.enabled = value.optBoolean("enabled", true);
            result.add(profile);
        }
        return result;
    }

    public synchronized void saveOpenCode(OpenCodeProfile profile) {
        List<OpenCodeProfile> profiles = openCodeProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(profile.id)) {
            profiles.set(i, profile); replaced = true; break;
        }
        if (!replaced) profiles.add(profile);
        try {
            secure.put("integration.opencodeserver." + profile.id, profile.password == null ? "" : profile.password);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encrypt OpenCode Server password", error);
        }
        JSONObject root = root();
        JSONArray array = new JSONArray();
        for (OpenCodeProfile item : profiles) array.put(item.json());
        put(root, "opencode", array);
        write(root);
    }

    public synchronized void saveMcp(McpProfile profile) {
        List<McpProfile> profiles = mcpProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(profile.id)) {
            profiles.set(i, profile); replaced = true; break;
        }
        if (!replaced) profiles.add(profile);
        try {
            secure.put("integration.mcp.headers." + profile.id,
                    profile.headersJson == null ? "{}" : profile.headersJson);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encrypt MCP headers", error);
        }
        JSONObject root = root();
        JSONArray array = new JSONArray();
        for (McpProfile item : profiles) array.put(item.json());
        put(root, "mcp", array);
        write(root);
    }

    public synchronized void deleteOpenCode(String id) {
        JSONArray array = new JSONArray();
        for (OpenCodeProfile profile : openCodeProfiles()) if (!profile.id.equals(id)) array.put(profile.json());
        secure.remove("integration.opencodeserver." + id);
        JSONObject root = put(root(), "opencode", array);
        write(root);
    }

    public synchronized void deleteMcp(String id) {
        JSONArray array = new JSONArray();
        for (McpProfile profile : mcpProfiles()) if (!profile.id.equals(id)) array.put(profile.json());
        secure.remove("integration.mcp.headers." + id);
        JSONObject root = put(root(), "mcp", array);
        write(root);
    }

    private static JSONObject put(JSONObject root, String key, Object value) {
        try {
            root.put(key, value);
            return root;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create integration JSON", error);
        }
    }

    private JSONObject root() {
        try { return file.isFile() ? new JSONObject(ProjectManager.read(file)) : new JSONObject(); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private void write(JSONObject root) {
        try { ProjectManager.write(file, root.toString(2)); }
        catch (Exception error) { throw new IllegalStateException("Unable to save integrations", error); }
    }
}
