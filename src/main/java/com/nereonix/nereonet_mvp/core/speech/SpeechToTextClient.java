package com.nereonix.nereonet_mvp.core.speech;

import java.util.List;

/**
 * Speech to text, called by the server only (VoiceCountService), never from the browser.
 * AssemblyAiSpeechClient is the real one; FakeSpeechClient answers in dev (app.assemblyai.fake) and in the tests.
 */
public interface SpeechToTextClient {

    /**
     * The English text of one recording. audio: the recording as the browser made it (WebM/Opus, MP4/AAC, Ogg...);
     * contentType: its media type, as sent; keyterms: words the recording is likely to contain (the vessel's item
     * names), to help the provider spell them; may be empty. The audio is kept nowhere: the provider's copy is
     * deleted once the text is back. Never null: an empty text when nothing was heard.
     * SpeechToTextException when the provider fails or takes longer than its timeout.
     */
    String transcribe(byte[] audio, String contentType, List<String> keyterms);
}
