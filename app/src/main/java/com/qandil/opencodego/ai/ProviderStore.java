package com.qandil.opencodego.ai;

import android.content.Context;
import android.content.SharedPreferences;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.security.SecureStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Provider metadata on disk, API secrets in Android Keystore encrypted storage. */
public final class ProviderStore {
    private static ProviderStore instance;
    private final File catalogFile;
    private final SecureStore secureStore;
    private final Context context;

    private ProviderStore(Context context) {
        this.context = context.getApplicationContext();
        catalogFile = new File(context.getFilesDir(), "providers.json");
        secureStore = new SecureStore(context);
        migrateLegacy();
    }

    public static synchronized ProviderStore get(Context context) {
        if (instance == null) instance = new ProviderStore(context.getApplicationContext());
        return instance;
    }

    public synchronized void ensureCatalog() {
        if (catalogFile.isFile()) return;
        save(defaultCatalog());
    }

    public synchronized List<Provider> list() {
        ensureCatalog();
        List<Provider> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(ProjectManager.read(catalogFile));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String id = object.optString("id");
                result.add(Provider.from(object, secureStore.get(secretKey(id), "")));
            }
        } catch (Exception ignored) {}
        return result;
    }

    public synchronized List<Provider> enabled() {
        List<Provider> result = new ArrayList<>();
        for (Provider provider : list()) if (provider.enabled) result.add(provider);
        return result;
    }

    public synchronized Provider find(String id) {
        if (id == null) return null;
        for (Provider provider : list()) if (id.equals(provider.id)) return provider;
        return null;
    }

    public synchronized void save(List<Provider> providers) {
        JSONArray array = new JSONArray();
        for (Provider provider : providers) {
            normalize(provider);
            array.put(provider.jsonWithoutSecret());
            try {
                if (provider.apiKey == null || provider.apiKey.isEmpty()) secureStore.remove(secretKey(provider.id));
                else secureStore.put(secretKey(provider.id), provider.apiKey);
            } catch (Exception error) {
                throw new IllegalStateException("Не удалось зашифровать API-ключ", error);
            }
        }
        try { ProjectManager.write(catalogFile, array.toString(2)); }
        catch (Exception error) { throw new IllegalStateException("Не удалось сохранить провайдеров", error); }
    }

    public synchronized void upsert(Provider provider) {
        normalize(provider);
        List<Provider> providers = list();
        boolean replaced = false;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).id.equals(provider.id)) {
                providers.set(i, provider);
                replaced = true;
                break;
            }
        }
        if (!replaced) providers.add(provider);
        save(providers);
    }

    public synchronized void delete(String id) {
        List<Provider> providers = list();
        List<Provider> updated = new ArrayList<>();
        for (Provider provider : providers) if (!provider.id.equals(id)) updated.add(provider);
        secureStore.remove(secretKey(id));
        save(updated);
    }

    public synchronized void resetCatalogPreservingKeys() {
        List<Provider> defaults = defaultCatalog();
        List<Provider> current = list();
        for (Provider provider : defaults) {
            for (Provider old : current) {
                if (provider.id.equals(old.id)) {
                    provider.apiKey = old.apiKey;
                    if (!old.model.isEmpty()) provider.model = old.model;
                    break;
                }
            }
        }
        save(defaults);
    }

    private List<Provider> defaultCatalog() {
        List<Provider> result = new ArrayList<>();
        add(result, "opencode-go", "OpenCode Go", Provider.OPENAI, "https://opencode.ai/zen/go/v1", "");
        add(result, "opencode-go-anthropic", "OpenCode Go · Anthropic API", Provider.ANTHROPIC, "https://opencode.ai/zen/go", "");
        add(result, "openai", "OpenAI", Provider.OPENAI, "https://api.openai.com/v1", "");
        add(result, "anthropic", "Anthropic Claude", Provider.ANTHROPIC, "https://api.anthropic.com", "");
        add(result, "gemini", "Google Gemini", Provider.GEMINI, "https://generativelanguage.googleapis.com", "");
        add(result, "openrouter", "OpenRouter", Provider.OPENAI, "https://openrouter.ai/api/v1", "");
        add(result, "groq", "Groq", Provider.OPENAI, "https://api.groq.com/openai/v1", "");
        add(result, "cerebras", "Cerebras", Provider.OPENAI, "https://api.cerebras.ai/v1", "");
        add(result, "deepseek", "DeepSeek", Provider.OPENAI, "https://api.deepseek.com", "");
        add(result, "mistral", "Mistral", Provider.OPENAI, "https://api.mistral.ai/v1", "");
        add(result, "xai", "xAI", Provider.OPENAI, "https://api.x.ai/v1", "");
        add(result, "moonshot", "Moonshot / Kimi", Provider.OPENAI, "https://api.moonshot.ai/v1", "");
        add(result, "minimax", "MiniMax", Provider.ANTHROPIC, "https://api.minimax.io/anthropic", "");
        add(result, "zai", "Z.AI / GLM", Provider.OPENAI, "https://api.z.ai/api/paas/v4", "");
        add(result, "together", "Together AI", Provider.OPENAI, "https://api.together.xyz/v1", "");
        add(result, "fireworks", "Fireworks AI", Provider.OPENAI, "https://api.fireworks.ai/inference/v1", "");
        add(result, "nvidia", "NVIDIA NIM", Provider.OPENAI, "https://integrate.api.nvidia.com/v1", "");
        add(result, "github-models", "GitHub Models", Provider.OPENAI, "https://models.inference.ai.azure.com", "");
        add(result, "cloudflare-workers", "Cloudflare Workers AI", Provider.OPENAI, "", "");
        add(result, "azure-openai", "Azure OpenAI", Provider.OPENAI, "", "");
        add(result, "bedrock-gateway", "Amazon Bedrock Gateway", Provider.OPENAI, "", "");
        add(result, "vertex-gateway", "Google Vertex AI Gateway", Provider.OPENAI, "", "");
        add(result, "vercel-gateway", "Vercel AI Gateway", Provider.OPENAI, "https://ai-gateway.vercel.sh/v1", "");
        add(result, "cloudflare-gateway", "Cloudflare AI Gateway", Provider.OPENAI, "", "");
        add(result, "litellm", "LiteLLM", Provider.OPENAI, "http://127.0.0.1:4000/v1", "");
        add(result, "ollama", "Ollama", Provider.OPENAI, "http://127.0.0.1:11434/v1", "");
        add(result, "lmstudio", "LM Studio", Provider.OPENAI, "http://127.0.0.1:1234/v1", "");
        add(result, "localai", "LocalAI", Provider.OPENAI, "http://127.0.0.1:8081/v1", "");
        add(result, "vllm", "vLLM", Provider.OPENAI, "http://127.0.0.1:8000/v1", "");
        add(result, "custom", "Custom OpenAI-compatible", Provider.OPENAI, "", "");
        return result;
    }

    private static void add(
            List<Provider> providers,
            String id,
            String name,
            String type,
            String baseUrl,
            String model) {
        providers.add(new Provider(id, name, type, baseUrl, model, ""));
    }

    private static void normalize(Provider provider) {
        provider.id = provider.id == null ? "provider" : provider.id.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-").replaceAll("(^-+|-+$)", "");
        if (provider.id.isEmpty()) provider.id = "provider-" + System.currentTimeMillis();
        provider.name = provider.name == null || provider.name.trim().isEmpty() ? provider.id : provider.name.trim();
        provider.type = provider.type == null ? Provider.OPENAI : provider.type.toLowerCase(Locale.ROOT).trim();
        if (!Provider.OPENAI.equals(provider.type)
                && !Provider.ANTHROPIC.equals(provider.type)
                && !Provider.GEMINI.equals(provider.type)) provider.type = Provider.OPENAI;
        provider.baseUrl = provider.baseUrl == null ? "" : trimSlash(provider.baseUrl.trim());
        provider.model = provider.model == null ? "" : provider.model.trim();
        provider.apiKey = provider.apiKey == null ? "" : provider.apiKey.trim();
        provider.headersJson = provider.headersJson == null || provider.headersJson.trim().isEmpty() ? "{}" : provider.headersJson.trim();
    }

    private void migrateLegacy() {
        if (catalogFile.exists()) return;
        try {
            SharedPreferences legacy = context.getSharedPreferences("providers", Context.MODE_PRIVATE);
            String raw = legacy.getString("items", "");
            if (raw.isEmpty()) return;
            JSONArray old = new JSONArray(raw);
            List<Provider> providers = new ArrayList<>();
            for (int i = 0; i < old.length(); i++) {
                JSONObject object = old.getJSONObject(i);
                String key = object.optString("apiKey", "");
                object.remove("apiKey");
                providers.add(Provider.from(object, key));
            }
            save(providers);
            legacy.edit().clear().apply();
        } catch (Exception ignored) {}
    }

    private static String secretKey(String id) { return "provider:" + id + ":apiKey"; }
    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
