package com.nereonix.nereonet_mvp.core.speech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Answers instead of AssemblyAI when app.assemblyai.fake is true (P57): dev screens without a key, and the tests.
 * Whatever the recording, the text is SENTENCE, which names three items of the demo vessel (Tomatoes, Chicken breast
 * by one of its words, Eggs) and one it does not have (herring). Tests set their own text or failure with answer() /
 * fail() and read calls().
 */
@Component
@ConditionalOnProperty(name = "app.assemblyai.fake", havingValue = "true")
public class FakeSpeechClient implements SpeechToTextClient {

    public static final String SENTENCE =
            "Five kilos of tomatoes, eight kilos of chicken, thirty eggs, three cans of herring.";

    /** One call: the size of the recording, its type and the key terms sent. */
    public record Call(int bytes, String contentType, List<String> keyterms) {
    }

    private volatile String text = SENTENCE;
    private volatile SpeechToTextException failure;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String transcribe(byte[] audio, String contentType, List<String> keyterms) {
        calls.add(new Call(audio == null ? 0 : audio.length, contentType,
                keyterms == null ? List.of() : List.copyOf(keyterms)));
        if (failure != null) {
            throw failure;
        }
        return text;
    }

    /** From now on, every recording is this text (tests). */
    public void answer(String text) {
        this.text = text;
        this.failure = null;
    }

    /** From now on, every recording fails so (tests). */
    public void fail(SpeechToTextException.Reason reason) {
        this.failure = new SpeechToTextException(reason, "Fake " + reason);
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Back to SENTENCE, no calls. */
    public void reset() {
        text = SENTENCE;
        failure = null;
        calls.clear();
    }
}
