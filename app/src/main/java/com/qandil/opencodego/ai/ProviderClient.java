package com.qandil.opencodego.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP adapters for OpenAI-compatible, Anthropic Messages and Gemini APIs. */
public final class ProviderClient {
    public static final class ToolCall {
        public final String id;
        public final String name;
        public final JSONObject arguments;

        ToolCall(String id, String name, JSONObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null ? new JSONObject() : arguments;
        }
    }

    public static final class Reply {
        public final String text;
        public final List<ToolCall> toolCalls;
        public final String finishReason;

        Reply(String text, List<ToolCall> toolCalls, String finishReason) {
            this.text = text == null ? "" : text;
            this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
            this.finishReason = finishReason == null ? "" : finishReason;
        }
    }

    public Reply send(Provider provider, JSONArray normalizedMessages, JSONArray openAiTools) throws Exception {
        validate(provider);
        if (Provider.ANTHROPIC.equals(provider.type)) {
            return anthropic(provider, normalizedMessages, openAiTools);
        }
        if (Provider.GEMINI.equals(provider.type)) {
            return gemini(provider, normalizedMessages, openAiTools);
        }
        return openAi(provider, normalizedMessages, openAiTools);
    }

    public List<String> listModels(Provider provider) throws Exception {
        if (provider.baseUrl == null || provider.baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Укажите Base URL");
        }
        if (Provider.GEMINI.equals(provider.type)) return listGeminiModels(provider);
        if (Provider.ANTHROPIC.equals(provider.type)) {
            // Anthropic-compatible gateways do not consistently expose a models endpoint.
            List<String> result = new ArrayList<>();
            if (provider.model != null && !provider.model.isEmpty()) result.add(provider.model);
            return result;
        }
        JSONObject response = requestJson("GET", trimSlash(provider.baseUrl) + "/models", provider, null, null);
        JSONArray data = response.optJSONArray("data");
        List<String> models = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item != null) {
                String id = item.optString("id", "");
                if (!id.isEmpty()) models.add(id);
            }
        }
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    public String test(Provider provider) throws Exception {
        List<String> models = listModels(provider);
        if (!models.isEmpty()) return "Соединение работает · моделей: " + models.size();
        if (provider.model == null || provider.model.isEmpty()) {
            return "Endpoint доступен, но список моделей не предоставлен";
        }
        JSONArray messages = new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "Reply with OK only."));
        Reply reply = send(provider, messages, new JSONArray());
        return "Соединение работает · " + (reply.text.isEmpty() ? "OK" : reply.text.trim());
    }

    private Reply openAi(Provider provider, JSONArray messages, JSONArray tools) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", provider.model)
                .put("messages", toOpenAiMessages(messages))
                .put("temperature", 0.2)
                .put("stream", false);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", "auto");
        }
        JSONObject response = requestJson(
                "POST", trimSlash(provider.baseUrl) + "/chat/completions", provider, body, null);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("Provider returned no choices");
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String text = textContent(message.opt("content"));
        List<ToolCall> calls = new ArrayList<>();
        JSONArray toolCalls = message.optJSONArray("tool_calls");
        if (toolCalls != null) for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject call = toolCalls.getJSONObject(i);
            JSONObject function = call.optJSONObject("function");
            if (function == null) continue;
            calls.add(new ToolCall(
                    call.optString("id", "call_" + i),
                    function.optString("name"),
                    parseArguments(function.opt("arguments"))));
        }
        return new Reply(text, calls, choice.optString("finish_reason", ""));
    }

    private Reply anthropic(Provider provider, JSONArray messages, JSONArray openAiTools) throws Exception {
        StringBuilder system = new StringBuilder();
        JSONArray anthropicMessages = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.optString("role");
            if ("system".equals(role)) {
                if (system.length() > 0) system.append('\n');
                system.append(message.optString("content"));
                continue;
            }
            if ("tool".equals(role)) {
                JSONArray blocks = new JSONArray().put(new JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", message.optString("tool_call_id"))
                        .put("content", message.optString("content")));
                anthropicMessages.put(new JSONObject().put("role", "user").put("content", blocks));
                continue;
            }
            JSONArray content = new JSONArray();
            String text = message.optString("content", "");
            if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls != null) for (int j = 0; j < calls.length(); j++) {
                JSONObject call = calls.getJSONObject(j);
                content.put(new JSONObject()
                        .put("type", "tool_use")
                        .put("id", call.optString("id", "call_" + j))
                        .put("name", call.optString("name"))
                        .put("input", call.optJSONObject("arguments") == null
                                ? new JSONObject() : call.optJSONObject("arguments")));
            }
            if (content.length() == 0) content.put(new JSONObject().put("type", "text").put("text", ""));
            anthropicMessages.put(new JSONObject()
                    .put("role", "assistant".equals(role) ? "assistant" : "user")
                    .put("content", content));
        }
        JSONObject body = new JSONObject()
                .put("model", provider.model)
                .put("max_tokens", 8192)
                .put("messages", anthropicMessages)
                .put("temperature", 0.2);
        if (system.length() > 0) body.put("system", system.toString());
        JSONArray tools = toAnthropicTools(openAiTools);
        if (tools.length() > 0) body.put("tools", tools);
        Map<String, String> additional = new HashMap<>();
        additional.put("anthropic-version", "2023-06-01");
        JSONObject response = requestJson(
                "POST", anthropicMessagesUrl(provider.baseUrl), provider, body, additional);
        JSONArray blocks = response.optJSONArray("content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            String type = block.optString("type");
            if ("text".equals(type)) text.append(block.optString("text"));
            else if ("tool_use".equals(type)) {
                calls.add(new ToolCall(
                        block.optString("id", "tool_" + i),
                        block.optString("name"),
                        block.optJSONObject("input")));
            }
        }
        return new Reply(text.toString(), calls, response.optString("stop_reason", ""));
    }

    private Reply gemini(Provider provider, JSONArray messages, JSONArray openAiTools) throws Exception {
        StringBuilder system = new StringBuilder();
        JSONArray contents = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.optString("role");
            if ("system".equals(role)) {
                if (system.length() > 0) system.append('\n');
                system.append(message.optString("content"));
                continue;
            }
            JSONArray parts = new JSONArray();
            if ("tool".equals(role)) {
                JSONObject response;
                try { response = new JSONObject(message.optString("content", "{}")); }
                catch (Exception ignored) { response = new JSONObject().put("result", message.optString("content")); }
                parts.put(new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", message.optString("name"))
                        .put("response", response)));
                contents.put(new JSONObject().put("role", "user").put("parts", parts));
                continue;
            }
            String text = message.optString("content", "");
            if (!text.isEmpty()) parts.put(new JSONObject().put("text", text));
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls != null) for (int j = 0; j < calls.length(); j++) {
                JSONObject call = calls.getJSONObject(j);
                parts.put(new JSONObject().put("functionCall", new JSONObject()
                        .put("name", call.optString("name"))
                        .put("args", call.optJSONObject("arguments") == null
                                ? new JSONObject() : call.optJSONObject("arguments"))));
            }
            if (parts.length() == 0) parts.put(new JSONObject().put("text", ""));
            contents.put(new JSONObject()
                    .put("role", "assistant".equals(role) ? "model" : "user")
                    .put("parts", parts));
        }
        JSONObject body = new JSONObject()
                .put("contents", contents)
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("maxOutputTokens", 8192));
        if (system.length() > 0) body.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", system.toString()))));
        JSONArray declarations = toGeminiDeclarations(openAiTools);
        if (declarations.length() > 0) body.put("tools", new JSONArray()
                .put(new JSONObject().put("functionDeclarations", declarations)));
        String url = trimSlash(provider.baseUrl) + "/v1beta/models/"
                + URLEncoder.encode(provider.model, "UTF-8") + ":generateContent?key="
                + URLEncoder.encode(provider.apiKey, "UTF-8");
        JSONObject response = requestJson("POST", url, provider, body, null);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            JSONObject feedback = response.optJSONObject("promptFeedback");
            throw new IOException("Gemini returned no candidate" + (feedback == null ? "" : ": " + feedback));
        }
        JSONObject candidate = candidates.getJSONObject(0);
        JSONObject content = candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.has("text")) text.append(part.optString("text"));
            JSONObject function = part.optJSONObject("functionCall");
            if (function != null) {
                calls.add(new ToolCall(
                        "gemini_" + i + "_" + System.currentTimeMillis(),
                        function.optString("name"),
                        function.optJSONObject("args")));
            }
        }
        return new Reply(text.toString(), calls, candidate.optString("finishReason", ""));
    }

    private List<String> listGeminiModels(Provider provider) throws Exception {
        String url = trimSlash(provider.baseUrl) + "/v1beta/models?key="
                + URLEncoder.encode(provider.apiKey, "UTF-8");
        JSONObject response = requestJson("GET", url, provider, null, null);
        List<String> result = new ArrayList<>();
        JSONArray models = response.optJSONArray("models");
        if (models != null) for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            String name = model.optString("name", "");
            if (name.startsWith("models/")) name = name.substring(7);
            JSONArray methods = model.optJSONArray("supportedGenerationMethods");
            boolean generate = methods == null;
            if (methods != null) for (int j = 0; j < methods.length(); j++) {
                if ("generateContent".equals(methods.optString(j))) generate = true;
            }
            if (generate && !name.isEmpty()) result.add(name);
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private JSONObject requestJson(
            String method,
            String url,
            Provider provider,
            JSONObject body,
            Map<String, String> additionalHeaders) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(180_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "OpenCodeGoAgent/10.1 Android");
            if (body != null) connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!Provider.GEMINI.equals(provider.type)
                    && provider.apiKey != null && !provider.apiKey.isEmpty()) {
                if (Provider.ANTHROPIC.equals(provider.type)) connection.setRequestProperty("x-api-key", provider.apiKey);
                else connection.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
            }
            for (Map.Entry<String, String> header : customHeaders(provider).entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (additionalHeaders != null) for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = read(input);
            if (status >= 400) throw new IOException("HTTP " + status + " · " + sanitizeError(text));
            if (text.trim().isEmpty()) return new JSONObject();
            try { return new JSONObject(text); }
            catch (Exception error) { throw new IOException("Provider returned invalid JSON: " + truncate(text, 800)); }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray toOpenAiMessages(JSONArray normalized) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < normalized.length(); i++) {
            JSONObject message = normalized.getJSONObject(i);
            String role = message.optString("role");
            JSONObject converted = new JSONObject().put("role", role);
            if ("tool".equals(role)) {
                converted.put("tool_call_id", message.optString("tool_call_id"));
                converted.put("content", message.optString("content"));
            } else {
                converted.put("content", message.optString("content", ""));
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    JSONArray openAiCalls = new JSONArray();
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject call = calls.getJSONObject(j);
                        openAiCalls.put(new JSONObject()
                                .put("id", call.optString("id", "call_" + j))
                                .put("type", "function")
                                .put("function", new JSONObject()
                                        .put("name", call.optString("name"))
                                        .put("arguments", (call.optJSONObject("arguments") == null
                                                ? new JSONObject() : call.optJSONObject("arguments")).toString())));
                    }
                    converted.put("tool_calls", openAiCalls);
                }
            }
            result.put(converted);
        }
        return result;
    }

    private static JSONArray toAnthropicTools(JSONArray openAiTools) throws Exception {
        JSONArray result = new JSONArray();
        if (openAiTools == null) return result;
        for (int i = 0; i < openAiTools.length(); i++) {
            JSONObject function = openAiTools.getJSONObject(i).optJSONObject("function");
            if (function == null) continue;
            result.put(new JSONObject()
                    .put("name", function.optString("name"))
                    .put("description", function.optString("description"))
                    .put("input_schema", function.optJSONObject("parameters") == null
                            ? new JSONObject().put("type", "object") : function.optJSONObject("parameters")));
        }
        return result;
    }

    private static JSONArray toGeminiDeclarations(JSONArray openAiTools) throws Exception {
        JSONArray result = new JSONArray();
        if (openAiTools == null) return result;
        for (int i = 0; i < openAiTools.length(); i++) {
            JSONObject function = openAiTools.getJSONObject(i).optJSONObject("function");
            if (function == null) continue;
            result.put(new JSONObject()
                    .put("name", function.optString("name"))
                    .put("description", function.optString("description"))
                    .put("parameters", sanitizeSchema(function.optJSONObject("parameters"))));
        }
        return result;
    }

    private static JSONObject sanitizeSchema(JSONObject input) throws Exception {
        if (input == null) return new JSONObject().put("type", "OBJECT");
        JSONObject output = new JSONObject();
        JSONArray names = input.names();
        if (names == null) return output;
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            if ("additionalProperties".equals(key) || "$schema".equals(key)) continue;
            Object value = input.get(key);
            if ("type".equals(key) && value instanceof String) {
                output.put(key, ((String) value).toUpperCase(Locale.ROOT));
            } else if (value instanceof JSONObject) output.put(key, sanitizeSchema((JSONObject) value));
            else if (value instanceof JSONArray) {
                JSONArray array = new JSONArray();
                JSONArray original = (JSONArray) value;
                for (int j = 0; j < original.length(); j++) {
                    Object item = original.get(j);
                    array.put(item instanceof JSONObject ? sanitizeSchema((JSONObject) item) : item);
                }
                output.put(key, array);
            } else output.put(key, value);
        }
        return output;
    }

    private static JSONObject parseArguments(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value == null || value == JSONObject.NULL) return new JSONObject();
        try { return new JSONObject(String.valueOf(value)); }
        catch (Exception ignored) { return new JSONObject().put("value", String.valueOf(value)); }
    }

    private static String textContent(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder result = new StringBuilder();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject part = array.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) result.append(part.optString("text"));
            }
            return result.toString();
        }
        return String.valueOf(value);
    }

    private static Map<String, String> customHeaders(Provider provider) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JSONObject object = new JSONObject(provider.headersJson == null ? "{}" : provider.headersJson);
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                if (!name.equalsIgnoreCase("authorization")
                        && !name.equalsIgnoreCase("x-api-key")
                        && !name.equalsIgnoreCase("host")
                        && !name.contains("\r") && !name.contains("\n")) {
                    String value = object.optString(name, "");
                    if (!value.contains("\r") && !value.contains("\n")) result.put(name, value);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String anthropicMessagesUrl(String baseUrl) {
        String base = trimSlash(baseUrl);
        if (base.endsWith("/v1")) return base + "/messages";
        return base + "/v1/messages";
    }

    private static void validate(Provider provider) {
        if (provider == null) throw new IllegalArgumentException("Провайдер не выбран");
        if (provider.baseUrl == null || provider.baseUrl.trim().isEmpty()) throw new IllegalArgumentException("Укажите Base URL");
        if (provider.model == null || provider.model.trim().isEmpty()) throw new IllegalArgumentException("Укажите модель");
        if (!provider.local() && (provider.apiKey == null || provider.apiKey.trim().isEmpty())) {
            throw new IllegalArgumentException("Укажите API-ключ");
        }
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) {
            output.write(buffer, 0, count);
            if (output.size() > 16 * 1024 * 1024) throw new IOException("Provider response is too large");
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String sanitizeError(String text) {
        if (text == null) return "";
        String sanitized = text.replaceAll("(?i)(api[_-]?key|authorization|token)\\s*[:=]\\s*[^,}\"]+", "$1:<redacted>");
        return truncate(sanitized.replaceAll("\\s+", " "), 2_000);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
