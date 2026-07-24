package com.qandil.opencodego.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic provider fallback and task-aware ordering. */
public final class ProviderRouter {
    public List<Provider> candidates(Provider selected, List<Provider> enabled, String task, String role) {
        Set<Provider> unique = new LinkedHashSet<>();
        if (selected != null && usable(selected)) unique.add(selected);
        List<Provider> remaining = new ArrayList<>();
        if (enabled != null) for (Provider provider : enabled) if (usable(provider) && provider != selected) remaining.add(provider);
        final String lower = ((task == null ? "" : task) + " " + (role == null ? "" : role)).toLowerCase(Locale.ROOT);
        remaining.sort(Comparator.comparingInt((Provider provider) -> score(provider, lower)).reversed());
        unique.addAll(remaining);
        return new ArrayList<>(unique);
    }

    public boolean retryable(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("http 408") || message.contains("http 409") || message.contains("http 425")
                || message.contains("http 429") || message.contains("http 500") || message.contains("http 502")
                || message.contains("http 503") || message.contains("http 504") || message.contains("timeout")
                || message.contains("timed out") || message.contains("connection reset")
                || message.contains("connection refused") || message.contains("temporarily unavailable");
    }

    private static boolean usable(Provider provider) {
        return provider != null && provider.enabled && provider.baseUrl != null && !provider.baseUrl.isEmpty()
                && (provider.local() || (provider.apiKey != null && !provider.apiKey.isEmpty()));
    }

    private static int score(Provider provider, String task) {
        String value = (provider.name + " " + provider.model + " " + provider.id).toLowerCase(Locale.ROOT);
        int score = provider.local() ? 15 : 0;
        if (task.matches(".*(код|code|debug|android|php|node|python|sql|database|server).*")) {
            if (value.matches(".*(code|coder|claude|gpt|gemini|deepseek|qwen|glm|kimi).*")) score += 30;
        }
        if (task.matches(".*(vision|image|скрин|изображ).*")) {
            if (value.matches(".*(vision|vl|gemini|gpt-4o|gpt-5).*")) score += 35;
        }
        if (task.matches(".*(plan|reason|архитект|security|review).*")) {
            if (value.matches(".*(reason|thinking|claude|gpt|gemini|deepseek).*")) score += 25;
        }
        if (value.contains("free")) score += 3;
        return score;
    }
}
