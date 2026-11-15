package com.nereonix.nereonet_mvp.core.speech;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.nereonix.nereonet_mvp.core.speech.SpeechToTextException.Reason;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real speech-to-text client, AssemblyAI's pre-recorded API (v2), as its documentation describes it:
 *   1. POST /v2/upload, the raw bytes (application/octet-stream): answers {"upload_url": ...}.
 *   2. POST /v2/transcript {"audio_url", "language_code": "en", "keyterms_prompt": [the item names]}: answers the
 *      transcript with its "id" and "status". The speech model is AssemblyAI's default (speech_models not sent).
 *   3. GET /v2/transcript/{id} every 3 s (the interval of AssemblyAI's own examples) until "status" is "completed"
 *      (the text is "text") or "error", or app.assemblyai.timeout has passed since step 1.
 *   4. DELETE /v2/transcript/{id}, always once a transcript exists, whatever happened: AssemblyAI then deletes the
 *      transcript and the uploaded file at once. Nothing of the recording stays with AssemblyAI or with us.
 * Every call carries "Authorization: &lt;key&gt;" (AssemblyAI's format, no "Bearer"). Key terms: at most 200
 * (Universal-2's limit, the lower of the two models AssemblyAI may route to), each at most 6 words. Logs say which step
 * ended how, never the key, the audio, the key terms or the text. Active unless app.assemblyai.fake is true.
 */
@Component
@ConditionalOnProperty(name = "app.assemblyai.fake", havingValue = "false", matchIfMissing = true)
public class AssemblyAiSpeechClient implements SpeechToTextClient {

    private static final Logger log = LoggerFactory.getLogger(AssemblyAiSpeechClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final int MAX_KEYTERMS = 200;
    static final int MAX_KEYTERM_WORDS = 6;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    // An answer is a small JSON object; anything bigger is not one we read.
    private static final int MAX_ANSWER_BYTES = 2_000_000;

    private final AssemblyAiSettings settings;
    private final Duration pollInterval;
    private final RestClient http;

    @Autowired
    public AssemblyAiSpeechClient(AssemblyAiSettings settings) {
        this(settings, POLL_INTERVAL);
    }

    AssemblyAiSpeechClient(AssemblyAiSettings settings, Duration pollInterval) {
        this.settings = settings;
        this.pollInterval = pollInterval;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(settings.timeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String transcribe(byte[] audio, String contentType, List<String> keyterms) {
        if (settings.apiKey().isEmpty()) {
            throw new SpeechToTextException(Reason.PROVIDER_ERROR, "AssemblyAI is not configured");
        }
        if (audio == null || audio.length == 0) {
            throw new SpeechToTextException(Reason.PROVIDER_ERROR, "No audio");
        }
        Instant deadline = Instant.now().plus(settings.timeout());
        String transcriptId = null;
        try {
            JsonNode uploaded = call(HttpMethod.POST, "/v2/upload", MediaType.APPLICATION_OCTET_STREAM, audio,
                    "upload");
            String uploadUrl = text(uploaded, "upload_url");
            if (uploadUrl == null) {
                throw new SpeechToTextException(Reason.PROVIDER_ERROR, "AssemblyAI upload: no upload_url");
            }
            JsonNode transcript = call(HttpMethod.POST, "/v2/transcript", MediaType.APPLICATION_JSON,
                    request(uploadUrl, keyterms), "submit");
            transcriptId = text(transcript, "id");
            if (transcriptId == null) {
                throw new SpeechToTextException(Reason.PROVIDER_ERROR, "AssemblyAI submit: no id");
            }
            while (true) {
                String status = text(transcript, "status");
                if ("completed".equals(status)) {
                    String text = text(transcript, "text");
                    return text == null ? "" : text;
                }
                if ("error".equals(status)) {
                    throw new SpeechToTextException(Reason.PROVIDER_ERROR, "AssemblyAI transcript: status error");
                }
                if (Instant.now().plus(pollInterval).isAfter(deadline)) {
                    throw new SpeechToTextException(Reason.TIMEOUT, "AssemblyAI transcript: no text in "
                            + settings.timeout().toSeconds() + " s");
                }
                pause();
                transcript = call(HttpMethod.GET, "/v2/transcript/" + transcriptId, null, null, "poll");
            }
        } catch (SpeechToTextException e) {
            log.warn("AssemblyAI: {} ({})", e.reason(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            Reason reason = timedOut(e) ? Reason.TIMEOUT : Reason.PROVIDER_ERROR;
            log.warn("AssemblyAI: {} ({})", reason, e.getClass().getSimpleName());
            throw new SpeechToTextException(reason, "AssemblyAI: " + e.getClass().getSimpleName());
        } finally {
            if (transcriptId != null) {
                delete(transcriptId);
            }
        }
    }

    /** The body of step 2. */
    String request(String uploadUrl, List<String> keyterms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio_url", uploadUrl);
        body.put("language_code", settings.languageCode());
        List<String> terms = keyterms(keyterms);
        if (!terms.isEmpty()) {
            body.put("keyterms_prompt", terms);
        }
        return MAPPER.writeValueAsString(body);
    }

    /** The key terms AssemblyAI accepts: not blank, at most 6 words, each once (ignoring case), at most 200. */
    static List<String> keyterms(List<String> names) {
        List<String> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (names == null) {
            return terms;
        }
        for (String name : names) {
            if (terms.size() == MAX_KEYTERMS) {
                break;
            }
            String term = name == null ? "" : name.strip().replaceAll("\\s+", " ");
            if (term.isEmpty() || term.split(" ").length > MAX_KEYTERM_WORDS
                    || !seen.add(term.toLowerCase(Locale.ROOT))) {
                continue;
            }
            terms.add(term);
        }
        return terms;
    }

    private JsonNode call(HttpMethod method, String path, MediaType type, Object body, String step) {
        RestClient.RequestBodySpec request = http.method(method)
                .uri(settings.baseUrl() + path)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", settings.apiKey());
        if (body != null) {
            request.contentType(type).body(body);
        }
        return request.exchange((req, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new SpeechToTextException(Reason.PROVIDER_ERROR,
                        "AssemblyAI " + step + ": HTTP " + response.getStatusCode().value());
            }
            JsonNode node = parse(response.getBody());
            if (node == null || !node.isObject()) {
                throw new SpeechToTextException(Reason.PROVIDER_ERROR, "AssemblyAI " + step + ": not JSON");
            }
            return node;
        });
    }

    // Best effort: a failed delete is logged, never thrown (the text, or the first failure, matters more).
    private void delete(String transcriptId) {
        try {
            http.delete().uri(settings.baseUrl() + "/v2/transcript/" + transcriptId)
                    .header("Authorization", settings.apiKey())
                    .exchange((req, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("AssemblyAI delete: HTTP {}", response.getStatusCode().value());
                        }
                        return null;
                    });
        } catch (RuntimeException e) {
            log.warn("AssemblyAI delete: {}", e.getClass().getSimpleName());
        }
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechToTextException(Reason.TIMEOUT, "AssemblyAI: interrupted");
        }
    }

    private static JsonNode parse(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_ANSWER_BYTES + 1);
        if (bytes.length > MAX_ANSWER_BYTES) {
            return null;
        }
        try {
            return MAPPER.readTree(bytes);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
