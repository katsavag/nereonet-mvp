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
            // Crew (ship) screens: the library as the crew see it, their navigation, the ship home page and their announcements.
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
            "home.toRead.kind.ANNOUNCEMENT",
            "home.toRead.date.LIBRARY",
            "home.toRead.date.ANNOUNCEMENT",
            "home.toRead.empty",
            "home.links.library",
            "home.links.announcements",
            "home.crewAcknowledgements.heading",
            "home.crewAcknowledgements.empty",
            "home.certificates.heading",
            "home.certificates.expires",
            "home.certificates.number",
            "home.certificates.empty",
            "home.certificates.contact",
            // The crew's announcements and their notification (P27).
            "announcement.notification",
            "announcement.ship.title",
            "announcement.ship.hint",
            "announcement.ship.empty.title",
            "announcement.ship.empty.text",
            "announcement.ship.toAcknowledge",
            "announcement.ship.acknowledged",
            "announcement.ship.from",
            "announcement.ship.fromOffice",
            "announcement.ship.acknowledge",
            "announcement.ship.acknowledgedAt",
            "announcement.ship.acknowledgedMessage",
            // My profile and My crew (P31). The field names, "Not given", the photo's alt text, the office's Profile
            // card and the audit actions are on office screens too, so they are in Greek and not listed here.
            "nav.profile",
            "nav.myCrew",
            "nav.more",
            "more.title",
            "more.signedInAs",
            "profile.title",
            "profile.visibility",
            "profile.fields",
            "profile.photo.set",
            "profile.saved",
            "profile.languages.yours",
            "profile.languages.count",
            "profile.languages.order",
            "profile.languages.none",
            "profile.languages.add.heading",
            "profile.languages.search",
            "profile.languages.find",
            "profile.languages.add",
            "profile.languages.added",
            "profile.languages.full",
            "profile.languages.noResults",
            "profile.languages.resultCount",
            "profile.languages.remove",
            "profile.languages.up",
            "profile.languages.down",
            "profile.languages.saveHint",
            "profile.languages.error.tooMany",
            "profile.languages.error.unknown",
            "profile.nationality.label",
            "profile.nationality.error.unknown",
            "profile.bio.label",
            "profile.bio.hint",
            "profile.bio.error.tooLong",
            "profile.photo.label",
            "profile.photo.hint",
            "profile.photo.current",
            "profile.photo.none",
            "profile.photo.preview",
            "profile.photo.resizing",
            "profile.photo.error.required",
            "profile.photo.error.tooLarge",
            "profile.photo.error.type",
            "profile.photo.delete",
            "profile.photo.delete.confirm.title",
            "profile.photo.delete.confirm.body",
            "profile.photo.delete.confirm.submit",
            "profile.photo.deleted",
            "mycrew.heading",
            "mycrew.you",
            "mycrew.only.title",
            "mycrew.only.text",
            "mycrew.nothingYet",
            "mycrew.back",
            // The Master's approvals and the request notification (P33). "New item" is on the office's request page
            // too, so it is in Greek.
            "nav.approvals",
            "nav.count.approvals",
            // P37: the crew's Announcements counter.
            "nav.count.announcements",
            "approval.title",
            "approval.hint.waiting",
            "approval.hint.decided",
            "approval.tabs",
            "approval.tab.waiting",
            "approval.tab.decided",
            "approval.empty.waiting.title",
            "approval.empty.waiting.text",
            "approval.empty.decided.title",
            "approval.empty.decided.text",
            "approval.personRole",
            "approval.lineCount",
            "approval.requestedBy",
            "approval.requestedAt",
            "approval.status",
            "approval.decided",
            "approval.reason",
            "approval.lines",
            "approval.unit.none",
            "request.status.PENDING",
            "request.status.APPROVED",
            "request.status.REJECTED",
            "request.status.CANCELLED",
            "request.notification.submitted",
            // Approving and declining a request, and the requester's notification (P34). The audit actions are on
            // office screens too, so they are in Greek.
            "approval.approve",
            "approval.decline",
            "approval.decline.heading",
            "approval.decline.hint",
            "approval.decline.error.reasonRequired",
            "approval.decline.error.reasonTooLong",
            "approval.approved",
            "approval.declined",
            "approval.notPending",
            "request.notification.approved",
            "request.notification.declined",
            // The Master's ship order and past orders (P35). The office's notification text is saved in English, as
            // every notification; its type and the audit action are on office screens, so they are in Greek.
            "nav.order",
            "nav.count.order",
            "order.title",
            "order.hint",
            "order.empty.title",
            "order.empty.text",
            "order.lineCount",
            "order.askedBy",
            "order.line.actions",
            "order.quantity.label",
            "order.quantity.save",
            "order.quantity.saved",
            "order.quantity.error",
            "order.line.remove",
            "order.line.remove.label",
            "order.line.removed",
            "order.notOpen",
            "order.nothingToSend",
            "order.send.heading",
            "order.send.hint",
            "order.send",
            "order.requestedPort",
            "order.requestedPort.hint",
            "order.neededBy",
            "order.officeNote",
            "order.send.error.portRequired",
            "order.send.error.neededByRequired",
            "order.send.error.neededByPast",
            "order.send.error.tooLong",
            "order.sent",
            "order.notification.sent",
            "order.notification.confirmed",
            "order.status.OPEN",
            "order.status.SENT",
            "order.status.CONFIRMED",
            "order.status.CLOSED",
            "order.status",
            "order.sentBy",
            "order.number",
            "order.requestedAt",
            "order.confirmedAt",
            "order.confirmedPort",
            "order.deliveryDate",
            "order.past.title",
            "order.past.hint",
            "order.past.empty.title",
            "order.past.empty.text");

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
