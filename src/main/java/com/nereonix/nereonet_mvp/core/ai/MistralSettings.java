package com.nereonix.nereonet_mvp.core.ai;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Mistral settings (P57), app.mistral.*:
 *   base-url  default https://api.mistral.ai (no trailing slash)
 *   api-key   MISTRAL_API_KEY, from the environment or the local .env (gitignored). Never logged, never in a page, an
 *             audit entry or an error message.
 *   model     default mistral-small-2603 (Mistral Small 4, pinned rather than the -latest alias, as Mistral advises,
 *             so the behaviour never changes unnoticed)
 *   timeout   how long one answer may take, default 30s
 *   fake      true: FakeCountExtractor answers instead of Mistral (dev screens and tests), with no key needed.
 */
@Component
public class MistralSettings {

    public static final String DEFAULT_BASE_URL = "https://api.mistral.ai";
    public static final String DEFAULT_MODEL = "mistral-small-2603";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final boolean fake;

    public MistralSettings(@Value("${app.mistral.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                           @Value("${app.mistral.api-key:}") String apiKey,
                           @Value("${app.mistral.model:" + DEFAULT_MODEL + "}") String model,
                           @Value("${app.mistral.timeout:30s}") Duration timeout,
                           @Value("${app.mistral.fake:false}") boolean fake) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        this.fake = fake;
    }

    /** A key is set, or the fake answers. */
    public boolean configured() {
        return fake || !apiKey.isEmpty();
    }

    public String baseUrl() {
        return baseUrl;
    }

    String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public Duration timeout() {
        return timeout;
    }

    public boolean fake() {
        return fake;
    }

    // Never the key.
    @Override
    public String toString() {
        return "MistralSettings[baseUrl=" + baseUrl + ", model=" + model + ", key "
                + (apiKey.isEmpty() ? "not set" : "set") + ", timeout=" + timeout + (fake ? ", fake" : "") + "]";
    }
}
