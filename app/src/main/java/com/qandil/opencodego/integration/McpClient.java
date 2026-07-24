package com.qandil.opencodego.integration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** MCP 2025-03-26 Streamable HTTP JSON-RPC client. */
public final class McpClient {
    private final String url;
    private final Map<String, String> headers;
    private String sessionId = "";
    private long requestId = 1;

    public McpClient(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Collections.emptyMap() : new LinkedHashMap<>(headers);
    }

    public JSONObject initialize() throws Exception {
        JSONObject params = new JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "OpenCode Go Agent").put("version", "11.0.0"));
        JSONObject response = call("initialize", params);
        notify("notifications/initialized", new JSONObject());
        return response;
    }

    public JSONObject listTools() throws Exception {
        return call("tools/list", new JSONObject());
    }

    public JSONObject callTool(String name, JSONObject arguments) throws Exception {
        return call("tools/call", new JSONObject().put("name", name)
                .put("arguments", arguments == null ? new JSONObject() : arguments));
    }

    public JSONObject listResources() throws Exception {
        return call("resources/list", new JSONObject());
    }

    public JSONObject readResource(String uri) throws Exception {
        return call("resources/read", new JSONObject().put("uri", uri));
    }

    public JSONObject listPrompts() throws Exception {
        return call("prompts/list", new JSONObject());
    }

    public JSONObject getPrompt(String name, JSONObject arguments) throws Exception {
        return call("prompts/get", new JSONObject().put("name", name)
                .put("arguments", arguments == null ? new JSONObject() : arguments));
    }

    public JSONObject ping() throws Exception { return call("ping", new JSONObject()); }

    private JSONObject call(String method, JSONObject params) throws Exception {
        long id = requestId++;
        JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("method", method).put("params", params == null ? new JSONObject() : params);
        HttpJson.Response response = HttpJson.request("POST", url, request.toString(), requestHeaders(), "", "");
        captureSession(response);
        String contentType = response.contentType.toLowerCase();
        String body = response.body.trim();
        JSONObject object;
        if (contentType.contains("text/event-stream") || body.startsWith("event:")) {
            object = lastSseJson(body);
        } else object = new JSONObject(body);
        if (object.has("error")) throw new IllegalStateException("MCP error: " + object.opt("error"));
        JSONObject result = object.optJSONObject("result");
        return result == null ? object : result;
    }

    private void notify(String method, JSONObject params) throws Exception {
        JSONObject request = new JSONObject().put("jsonrpc", "2.0")
                .put("method", method).put("params", params == null ? new JSONObject() : params);
        HttpJson.Response response = HttpJson.request("POST", url, request.toString(), requestHeaders(), "", "");
        captureSession(response);
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> result = new LinkedHashMap<>(headers);
        result.put("Accept", "application/json, text/event-stream");
        if (!sessionId.isEmpty()) result.put("Mcp-Session-Id", sessionId);
        return result;
    }

    private void captureSession(HttpJson.Response response) {
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            if ("mcp-session-id".equalsIgnoreCase(entry.getKey())) sessionId = entry.getValue();
        }
    }

    private static JSONObject lastSseJson(String body) {
        JSONObject last = null;
        for (String line : body.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                last = new JSONObject(data);
            } catch (Exception error) {
                throw new IllegalStateException("Invalid MCP SSE JSON", error);
            }
        }
        if (last == null) throw new IllegalStateException("MCP SSE response contains no JSON data");
        return last;
    }

    public static Map<String, String> parseHeaders(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return result;
        try {
            JSONObject object = new JSONObject(json);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, object.optString(key, ""));
            }
            return result;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid MCP headers JSON", error);
        }
    }
}
