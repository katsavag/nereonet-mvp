package com.nereonix.nereonet_mvp.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * P16: office screens are English and Greek, so every key of messages.properties is also in messages_el.properties,
 * except the crew-only and email keys listed in {@link #ENGLISH_ONLY}, which are English only today.
 */
class MessageKeysTest {

    private static final Path ENGLISH = Path.of("src/main/resources/messages.properties");
    private static final Path GREEK = Path.of("src/main/resources/messages_el.properties");

    /** English only on purpose. A key added here must exist in English and be missing in Greek (checked below). */
    private static final Set<String> ENGLISH_ONLY = Set.of(
            // Emails and the daily certificate reminder (crew and office emails, crew notification).
            "certificate.reminder.crew.email",
            "certificate.reminder.crew.subject",
            "certificate.reminder.crew.text",
            "certificate.reminder.office.email",
            "certificate.reminder.office.subject",
            "invitation.email.subject",
            "invitation.email.text",
            // Crew (ship) screens: the library as the crew see it, their navigation and the ship home page.
            // (Download and the file size are on the office's read view too since P22, so they are in Greek.)
            "library.acknowledge",
            "library.acknowledged",
            "library.empty.crew",
            "library.notification.newRequired",
            "library.notification.reminder",
            "library.required.acknowledged",
            "library.required.toAcknowledge",
            "nav.group.ship",
            "nav.home",
            "nav.library",
            "home.title",
            "home.notYou",
            "home.toRead.heading",
            "home.toRead.kind.LIBRARY",
            "home.toRead.published",
            "home.toRead.empty",
            "home.links.library",
            "home.crewAcknowledgements.heading",
            "home.crewAcknowledgements.empty",
            "home.certificates.heading",
            "home.certificates.expires",
            "home.certificates.number",
            "home.certificates.empty",
            "home.certificates.contact");

    @Test
    void everyOfficeKeyIsInGreek() throws IOException {
        Set<String> missing = new TreeSet<>(keys(ENGLISH));
        missing.removeAll(keys(GREEK));
        missing.removeAll(ENGLISH_ONLY);
        assertThat(missing).as("keys in messages.properties but not in messages_el.properties (add the Greek text, "
                + "or add the key to ENGLISH_ONLY if it is a crew or email key)").isEmpty();
    }

    @Test
    void theEnglishOnlyListHoldsOnlyKeysThatAreEnglishOnly() throws IOException {
        assertThat(keys(ENGLISH)).as("ENGLISH_ONLY keys that no longer exist").containsAll(ENGLISH_ONLY);
        Set<String> translated = new TreeSet<>(ENGLISH_ONLY);
        translated.retainAll(keys(GREEK));
        assertThat(translated).as("ENGLISH_ONLY keys that now have Greek text: remove them from the list").isEmpty();
    }

    private static Set<String> keys(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties.stringPropertyNames();
    }
}
