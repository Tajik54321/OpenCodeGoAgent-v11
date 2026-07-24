package com.qandil.opencodego.ai;

import org.json.JSONObject;

public final class Provider {
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String GEMINI = "gemini";

    public String id;
    public String name;
    public String type;
    public String baseUrl;
    public String model;
    public String apiKey;
    public String headersJson;
    public boolean enabled;

    public Provider(
            String id,
            String name,
            String type,
            String baseUrl,
            String model,
            String apiKey) {
        this(id, name, type, baseUrl, model, apiKey, "{}", true);
    }

    public Provider(
            String id,
            String name,
            String type,
            String baseUrl,
            String model,
            String apiKey,
            String headersJson,
            boolean enabled) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.headersJson = headersJson == null || headersJson.trim().isEmpty() ? "{}" : headersJson;
        this.enabled = enabled;
    }

    public JSONObject jsonWithoutSecret() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("type", type);
            json.put("baseUrl", baseUrl);
            json.put("model", model);
            json.put("headers", headersJson);
            json.put("enabled", enabled);
        } catch (Exception ignored) {
        }
        return json;
    }

    public static Provider from(JSONObject object, String apiKey) {
        return new Provider(
                object.optString("id"),
                object.optString("name"),
                object.optString("type", OPENAI),
                object.optString("baseUrl"),
                object.optString("model"),
                apiKey == null ? "" : apiKey,
                object.optString("headers", "{}"),
                object.optBoolean("enabled", true));
    }

    public boolean local() {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
                || url.startsWith("http://[::1]");
    }

    @Override public String toString() {
        String suffix = model == null || model.trim().isEmpty() ? " · модель не выбрана" : " · " + model;
        if (!local() && (apiKey == null || apiKey.isEmpty())) suffix += " · ключ не задан";
        return name + suffix;
    }
}
