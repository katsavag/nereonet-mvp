package com.nereonix.nereonet_mvp.core.speech;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The AssemblyAI settings, app.assemblyai.*:
 *   base-url       default https://api.assemblyai.com (no trailing slash; https://api.eu.assemblyai.com keeps the
 *                  audio and text in the EU)
 *   api-key        ASSEMBLYAI_API_KEY, from the environment or the local .env (gitignored). Never logged, never in a
 *                  page, an audit entry or an error message.
 *   timeout        how long one recording may take, upload to text, default 60s
 *   language-code  the language asked for, default en
 *   fake           true: FakeSpeechClient answers instead of AssemblyAI (dev screens and tests), with no key needed.
 * Counting by voice is offered only when configured() (and the Mistral settings are configured too).
 */
@Component
public class AssemblyAiSettings {

    public static final String DEFAULT_BASE_URL = "https://api.assemblyai.com";

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final String languageCode;
    private final boolean fake;

    public AssemblyAiSettings(@Value("${app.assemblyai.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                              @Value("${app.assemblyai.api-key:}") String apiKey,
                              @Value("${app.assemblyai.timeout:60s}") Duration timeout,
                              @Value("${app.assemblyai.language-code:en}") String languageCode,
                              @Value("${app.assemblyai.fake:false}") boolean fake) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(60) : timeout;
        this.languageCode = languageCode == null || languageCode.isBlank() ? "en" : languageCode.strip();
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

    public Duration timeout() {
        return timeout;
    }

    public String languageCode() {
        return languageCode;
    }

    public boolean fake() {
        return fake;
    }

    // Never the key.
    @Override
    public String toString() {
        return "AssemblyAiSettings[baseUrl=" + baseUrl + ", key " + (apiKey.isEmpty() ? "not set" : "set")
                + ", timeout=" + timeout + (fake ? ", fake" : "") + "]";
    }
}
