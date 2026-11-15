package com.nereonix.nereonet_mvp.core.ai;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Claude settings, app.claude.* (ClaudeCountExtractor):
 *   base-url     default https://api.anthropic.com (no trailing slash)
 *   api-key      ANTHROPIC_API_KEY, from the environment or the local .env (gitignored). Never logged, never in a page,
 *                an audit entry or an error message.
 *   model        default claude-haiku-5-5 (Claude Haiku 5.5)
 *   timeout      how long one answer may take, default 30s
 *   max-retries  how many times the SDK tries again after a 429, a 5xx, no connection or a timeout, default 1
 *   fake         true: FakeCountExtractor answers instead of Claude (dev screens and tests), with no key needed.
 */
@Component
public class ClaudeSettings {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxRetries;
    private final boolean fake;

    @Autowired
    public ClaudeSettings(@Value("${app.claude.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                          @Value("${app.claude.api-key:}") String apiKey,
                          @Value("${app.claude.model:" + DEFAULT_MODEL + "}") String model,
                          @Value("${app.claude.timeout:30s}") Duration timeout,
                          @Value("${app.claude.max-retries:1}") int maxRetries,
                          @Value("${app.claude.fake:false}") boolean fake) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        this.maxRetries = Math.max(0, maxRetries);
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

    public int maxRetries() {
        return maxRetries;
    }

    public boolean fake() {
        return fake;
    }

    // Never the key.
    @Override
    public String toString() {
        return "ClaudeSettings[baseUrl=" + baseUrl + ", model=" + model + ", key " + (apiKey.isEmpty() ? "not set" : "set")
                + ", timeout=" + timeout + ", maxRetries=" + maxRetries + (fake ? ", fake" : "") + "]";
    }
}
