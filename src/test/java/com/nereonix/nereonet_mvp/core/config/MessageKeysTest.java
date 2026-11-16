package com.nereonix.nereonet_mvp.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Office screens are English and Greek, so every key of messages.properties is also in messages_el.properties,
 * except the crew-only and email keys listed in {@link #ENGLISH_ONLY}, which are English only today.
 * The English texts say "Vessel", never "Ship" (only the names in {@link #SHIP_ALLOWED}).
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
            // (Download and the file size are on the office's read view too, so they are in Greek.)
            "library.empty.crew",
            "library.notification.newRequired",
            "library.notification.reminder",
            "library.notRead",
            "nav.group.ship",
            "nav.home",
            "nav.library",
            "home.title",
            "home.toRead.heading",
            "home.toRead.kind.LIBRARY",
            "home.toRead.kind.ANNOUNCEMENT",
            "home.toRead.date.LIBRARY",
            "home.toRead.date.ANNOUNCEMENT",
            "home.toRead.empty",
            "home.certificates.heading",
            "home.certificates.expires",
            "home.certificates.number",
            "home.certificates.empty",
            "home.certificates.contact",
            // The Master's home page.
            "masterhome.cards.heading",
            "masterhome.card.order",
            "masterhome.card.announcements",
            "masterhome.card.library",
            "masterhome.card.crew",
            "masterhome.card.toDo",
            "masterhome.orders.heading",
            "masterhome.orders.notConfirmed",
            "masterhome.orders.all",
            // The crew's announcements and their notification.
            "announcement.notification",
            "announcement.ship.title",
            "announcement.ship.hint",
            "announcement.ship.empty.title",
            "announcement.ship.empty.text",
            "announcement.ship.new",
            "announcement.ship.from",
            "announcement.ship.fromOffice",
            "announcement.ship.newCount",
            "announcement.ship.group.new",
            "announcement.ship.group.earlier",
            "announcement.ship.older",
            "announcement.ship.back",
            // The crew's word for announcements.
            "nav.news",
            // My profile and My crew. The field names, "Not given", the photo's alt text, the office's Profile
            // card and the audit actions are on office screens too, so they are in Greek and not listed here.
            "nav.profile",
            "nav.myCrew",
            "nav.more",
            "nav.count.more",
            "more.title",
            "more.signedInAs",
            "profile.title",
            "profile.visibility",
            "profile.fields",
            "profile.certificates.hint",
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
            // The Master's approvals and the request notification. "New item" is on the office's request page
            // too, so it is in Greek.
            "nav.approvals",
            "nav.count.approvals",
            // The crew's Announcements counter.
            "nav.count.announcements",
            "approval.title",
            "approval.hint.pending",
            "approval.hint.approved",
            "approval.hint.declined",
            "approval.tabs",
            "approval.tab.pending",
            "approval.tab.approved",
            "approval.tab.declined",
            "approval.empty.pending.title",
            "approval.empty.pending.text",
            "approval.empty.approved.title",
            "approval.empty.approved.text",
            "approval.empty.declined.title",
            "approval.empty.declined.text",
            "approval.personRole",
            "approval.lineCount",
            "approval.requestedBy",
            "approval.requestedAt",
            "approval.status",
            "approval.decision.APPROVED",
            "approval.decision.REJECTED",
            "approval.reason",
            "approval.unit.none",
            "request.status.PENDING",
            "request.status.APPROVED",
            "request.status.REJECTED",
            "request.status.CANCELLED",
            "request.notification.submitted",
            // Approving and declining a request, and the requester's notification. The audit actions are on
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
            // The Master's ship order and past orders. The office's notification text is saved in English, as
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
            "order.send.error.tooLong",
            "order.sent",
            "order.notification.sent",
            // The delivery scheduled or changed (the ship's notifications), the receipt on board (the Master's
            // pages) and the office's notification, saved in English as every notification.
            "order.notification.deliveryScheduled",
            "order.notification.deliveryChanged",
            "order.notification.received",
            "order.notification.receivedShort",
            // To the Chief cook when a delivery went into the store.
            "order.notification.stocked",
            "order.receipt.action",
            "order.receipt.waiting",
            "order.receipt.title",
            "order.receipt.heading",
            "order.receipt.hint",
            "order.receipt.lines",
            "order.receipt.ordered",
            "order.receipt.received",
            "order.receipt.received.label",
            "order.receipt.notDelivered",
            "order.receipt.note.label",
            "order.receipt.note.placeholder",
            "order.receipt.files",
            "order.receipt.files.hint",
            "order.receipt.files.add",
            "order.receipt.files.uploaded",
            "order.receipt.files.remove",
            "order.receipt.receivedAt",
            "order.receipt.receivedAt.hint",
            "order.receipt.review",
            "order.receipt.cancel",
            "order.receipt.review.title",
            "order.receipt.review.hint",
            "order.receipt.review.confirm",
            "order.receipt.review.back",
            "order.receipt.review.allReceived",
            "order.receipt.review.shortLines",
            "order.receipt.shortBy",
            "order.receipt.upload.progress",
            "order.receipt.upload.resizing",
            "order.receipt.upload.failed",
            "order.receipt.upload.tooLarge",
            "order.receipt.confirmed",
            "order.receipt.notConfirmed",
            "order.receipt.alreadyReceived",
            "order.receipt.receivedLine",
            "order.receipt.by",
            "order.receipt.error.noteTooLong",
            "order.receipt.error.receivedAtRequired",
            "order.receipt.error.receivedAtFuture",
            "order.receipt.error.receivedAtBeforeSent",
            "order.receipt.error.fileRequired",
            "order.receipt.error.fileType",
            "order.receipt.error.fileTooLarge",
            "order.receipt.error.tooManyFiles",
            "order.receipt.error.tooLargeTogether",
            // What the office changed, on the Master's order page, and the ship's notification.
            "order.officeChange.changed",
            "order.officeChange.removed",
            "order.officeChange.added",
            "order.notification.officeChanged",
            "order.notification.officeChanged.changed",
            "order.notification.officeChanged.changed.short",
            "order.notification.officeChanged.removed",
            "order.notification.officeChanged.removed.short",
            "order.notification.officeChanged.added",
            "order.notification.officeChanged.added.short",
            "order.status.SENT.waiting",
            "order.status",
            "order.sentBy",
            "order.number",
            "order.confirmedAt",
            "order.confirmedPort",
            "order.deliveryDate",
            "order.past.empty.title",
            // The crew's own missing required documents, on My profile and the home pages.
            "profile.required.contact",
            "home.documents.missing",
            // The Chief cook's store.
            "nav.stores",
            "nav.count.stores",
            "stores.title",
            "stores.hint",
            "stores.summary.heading",
            "stores.summary.lastCount",
            "stores.summary.notCounted",
            "stores.search",
            "stores.filter.status",
            "stores.filter.all",
            "stores.status.OK",
            "stores.status.LOW",
            "stores.status.OUT",
            "stores.place.other",
            "stores.empty.title",
            "stores.empty.text",
            "stores.item.quantity",
            "stores.item.status",
            "stores.item.unit",
            "stores.item.place",
            "stores.item.placeNone",
            "stores.history.heading",
            "stores.history.empty",
            "stores.history.at",
            "stores.history.type",
            "stores.history.before",
            "stores.history.after",
            "stores.history.by",
            "stores.history.note",
            "stores.movement.COUNT",
            "stores.movement.RECEIPT",
            // Counting the store by hand and its history.
            "stores.count.open",
            "stores.count.title",
            "stores.count.hint",
            "stores.count.save",
            "stores.count.changed",
            "stores.count.changed.none",
            "stores.count.changed.one",
            "stores.count.changed.many",
            "stores.count.changed.saved",
            "stores.count.nothing",
            "stores.count.add.heading",
            "stores.count.add.hint",
            "stores.count.add.name",
            "stores.count.add.unit",
            "stores.count.add.quantity",
            "stores.count.add.place",
            "stores.count.add.error.taken",
            "stores.history.difference",
            "stores.log.title",
            "stores.log.item",
            "stores.log.filter",
            "stores.log.clear",
            "stores.log.empty",
            "stores.log.pages",
            "stores.log.newer",
            "stores.log.older",
            "stores.log.page",
            "stores.card.low",
            "stores.card.out",
            // The Chief cook's and crew's requests to the Master.
            "nav.requests",
            "nav.count.requests",
            "request.list.title",
            "request.list.hint",
            "request.list.filters",
            "request.filter.all",
            "request.filter.waiting",
            "request.filter.approved",
            "request.filter.declined",
            "request.filter.showAll",
            "request.empty.title",
            "request.empty.text",
            "request.empty.WAITING",
            "request.empty.APPROVED",
            "request.empty.DECLINED",
            "request.new",
            "request.new.title",
            "request.edit",
            "request.edit.title",
            "request.sentAt",
            "request.cancel",
            "request.cancel.keep",
            "request.cancel.confirm.title",
            "request.cancel.confirm.body",
            "request.readOnly.APPROVED",
            "request.readOnly.REJECTED",
            "request.readOnly.CANCELLED",
            "request.sent",
            "request.saved",
            "request.cancelled",
            "request.notPending",
            "request.form.hint",
            "request.form.hint.cook",
            "request.form.noItems",
            "request.form.search",
            "request.form.searchPlaceholder",
            "request.form.noMatch",
            "request.form.runningLow",
            "request.form.runningLow.hint",
            "request.form.inStock",
            "request.form.newItem.heading",
            "request.form.newItem.hint",
            "request.form.newItem.name",
            "request.form.newItem.unit",
            "request.form.newItem.add",
            "request.form.newItem.adding",
            "request.form.newItem.remove",
            "request.form.newItem.removeLabel",
            "request.form.newItem.removing",
            "request.form.send",
            "request.form.sending",
            "request.form.error.nameRequired",
            "request.form.error.nameTooLong",
            "request.form.error.onList",
            "request.form.error.alreadyAdded",
            "request.form.error.newItemInvalid",
            "request.form.error.empty",
            "request.form.error.itemGone",
            "request.form.error.invalid",
            "home.card.requests",
            "home.card.requests.waiting",
            "stores.summary.requestsWaiting",
            // The Chief cook reads the vessel order, and the next delivery on Stores.
            "order.readOnly",
            "order.hint.readOnly",
            "stores.summary.nextDelivery",
            "stores.summary.nextDelivery.value",
            "stores.summary.noDelivery",
            // The orders in progress on Vessel order, and the Master's Deliveries to receive.
            "order.progress.delivery",
            "order.progress.overdue",
            "order.progress.sent",
            "order.progress.open",
            "order.progress.open.label",
            "order.progress.receipt.label",
            "order.current.heading",
            "order.view.label",
            "order.view.progress",
            "order.view.received",
            "order.progress.empty",
            "order.received.empty",
            "order.attention.receive",
            "order.attention.count",
            "masterhome.card.deliveries",
            // Counting the store by voice and its review.
            "stores.voice.heading",
            "stores.voice.hint",
            "stores.voice.enable",
            "stores.voice.enableHint",
            "stores.voice.hold",
            "stores.voice.release",
            "stores.voice.recording",
            "stores.voice.processing",
            "stores.voice.tooShort",
            "stores.voice.retry",
            "stores.voice.notSent",
            "stores.voice.limit",
            "stores.voice.off.unsupported",
            "stores.voice.off.insecure",
            "stores.voice.off.denied",
            "stores.voice.off.noDevice",
            "stores.voice.off.notSetUp",
            "stores.voice.noJs",
            "stores.voice.pending",
            "stores.voice.pendingLink",
            "stores.voice.review.title",
            "stores.voice.review.hint",
            "stores.voice.review.transcript",
            "stores.voice.review.lines",
            "stores.voice.review.counted",
            "stores.voice.review.words",
            "stores.voice.review.confidence",
            "stores.voice.review.remove",
            "stores.voice.review.noLines",
            "stores.voice.review.unmatched",
            "stores.voice.review.unmatchedHint",
            "stores.voice.extra.item",
            "stores.voice.extra.drop",
            "stores.voice.extra.quantity",
            "stores.voice.extra.itemError",
            "stores.voice.confirm",
            "stores.voice.discard",
            "stores.voice.confidence.HIGH",
            "stores.voice.confidence.MEDIUM",
            "stores.voice.confidence.LOW",
            "stores.voice.error.twice",
            "stores.voice.error.nothing",
            "stores.voice.saved",
            "stores.voice.discarded",
            "stores.voice.expired",
            "stores.voice.failure.NOT_CONFIGURED",
            "stores.voice.failure.NO_AUDIO",
            "stores.voice.failure.NO_ITEMS",
            "stores.voice.failure.TIMEOUT",
            "stores.voice.failure.PROVIDER_ERROR",
            "stores.voice.failure.NOTHING_UNDERSTOOD",
            "stores.voice.failure.ITEM_GONE",
            // The limits of the crew screens (requests and the Master's order, the receipt, the store count).
            "quantity.error.max.request",
            "quantity.error.max.receipt",
            "quantity.error.max.count",
            // The Chief cook's delivery count and the Master's approval (crew screens and notifications).
            "order.count.action",
            "order.count.view",
            "order.count.title",
            "order.count.heading",
            "order.count.waiting",
            "order.count.toApprove",
            "order.count.returnedShort",
            "order.count.draftShort",
            "order.progress.count.label",
            "order.progress.countView.label",
            "order.count.note.placeholder",
            "order.count.note",
            "order.count.note.example",
            "order.count.send",
            "order.count.send.hint",
            "order.count.sent",
            "order.count.alreadySent",
            "order.count.notConfirmed",
            "order.count.received",
            "order.count.error.required",
            "order.count.error.unfinished",
            "order.count.error.noteTooLong",
            "order.count.sentOn",
            "order.count.returnedBy",
            "order.count.countedLine",
            "order.count.countedBy",
            "order.count.return.action",
            "order.count.return.note",
            "order.count.return.hint",
            "order.count.return.submit",
            "order.count.returned",
            "order.count.return.noteRequired",
            "order.count.return.noteTooLong",
            "order.count.return.nothing",
            // Photographing the delivery invoice (the Master and the Chief cook).
            "order.invoice.title",
            "order.invoice.heading",
            "order.invoice.hint",
            "order.invoice.takePhoto",
            "order.invoice.chooseFile",
            "order.invoice.upload",
            "order.invoice.page",
            "order.invoice.pdf",
            "order.invoice.retake",
            "order.invoice.remove",
            "order.invoice.addPage",
            "order.invoice.done",
            "order.invoice.without",
            "order.invoice.maxPages",
            "order.invoice.sending",
            "order.invoice.preparing",
            "order.invoice.failed",
            "order.invoice.tryAgain",
            "order.invoice.fileTooLarge",
            "order.invoice.hard",
            "order.invoice.hard.hint",
            "order.invoice.continueAnyway",
            "order.invoice.pageFailed",
            "order.invoice.readAgain",
            "order.invoice.notReadNow",
            "order.invoice.otherOrder",
            "order.invoice.continue",
            "order.invoice.reading.title",
            "order.invoice.reading.page",
            "order.invoice.reading.done",
            "order.invoice.reading.hint",
            "order.invoice.error.noFile",
            "order.invoice.error.tooLarge",
            "order.invoice.error.fileType",
            "order.invoice.error.tooManyPages",
            "order.invoice.error.reading",
            "order.invoice.error.notConfirmed",
            "order.invoice.error.noPages",
            "order.invoice.photographed",
            "order.invoice.photograph",
            "order.invoice.retakePhoto",
            "order.count.tabs.label",
            "order.count.tab.todo",
            "order.count.tab.done",
            "order.count.ref.invoice",
            "order.count.ref.notRead",
            "order.count.ref.notOnInvoice",
            "order.count.ref.ordered",
            "order.count.ref.corrected",
            "order.count.ref.entered",
            "order.count.arrived",
            "order.count.confirm",
            "order.count.diff.less",
            "order.count.diff.more",
            "order.count.diff.lessOrdered",
            "order.count.diff.moreOrdered",
            "order.count.diff.none",
            "order.count.fix.open",
            "order.count.fix.enter",
            "order.count.fix.label",
            "order.count.fix.save",
            "order.count.fix.saved",
            "order.count.fix.required",
            "order.count.done.arrived",
            "order.approve.corrected",
            "order.count.addNote",
            "order.count.note.row",
            "order.count.row.saved",
            "order.count.todo.empty",
            "order.count.done.empty",
            "order.count.change",
            "order.count.picks.heading",
            "order.count.picks.choose",
            "order.count.notOrdered.heading",
            "order.count.notOrdered.hint",
            "order.count.finish",
            "order.count.finish.left",
            "order.count.choose.title",
            "order.count.choose.onInvoice",
            "order.count.choose.orderLines",
            "order.count.choose.items",
            "order.count.choose.none",
            "order.count.choose.search",
            "order.count.choose.find",
            "order.count.choose.save",
            "order.count.choose.empty",
            "order.count.choose.required",
            "order.count.choose.gone",
            "order.count.choose.saved",
            "order.count.choose.notOrdered",
            "order.count.left.title",
            "order.count.left.heading",
            "order.count.left.hint",
            "order.count.left.countThem",
            "order.count.send.title",
            "order.count.send.all",
            "order.count.send.some",
            "order.count.tile.same",
            "order.count.tile.sameOrdered",
            "order.count.tile.different",
            "order.count.different.heading",
            "order.count.line.of",
            "order.count.line.notOnInvoice",
            "order.count.line.arrived",
            "order.count.line.notCounted",
            "order.count.line.choose",
            "order.count.note.add",
            "order.approve.title",
            "order.approve.countedBy",
            "order.approve.tile.same",
            "order.approve.tile.sameOrdered",
            "order.approve.none",
            "order.approve.noneOrdered",
            "order.approve.notChosen",
            "order.approve.changedFrom",
            "order.approve.changedFromNone",
            "order.approve.showAll",
            "order.approve.all.heading",
            "order.approve.invoice",
            "order.approve.cookNote",
            "order.approve.changeDate",
            "order.approve.approve",
            "order.approve.hint",
            "order.approve.done",
            "order.approve.noFile",
            "order.notification.countApproved",
            // To the Chief cook when the office changed a match or a conversion after receipt.
            "order.notification.countAgain",
            "order.notification.counted",
            "order.notification.countReturned",
            "order.receipt.files.byCook",
            "masterhome.card.deliveries.counted");

    /**
     * Names that keep the word "ship" in English: the official name of an ISPS document, and the names of companies
     * and products. Matched ignoring case; everything else says "vessel" / "vessels".
     */
    private static final List<String> SHIP_ALLOWED = List.of("Ship Security Plan", "shipping company", "ShipServ",
            "RightShip");

    private static final Pattern SHIP_WORD = Pattern.compile("(?i)\\bships?\\b");

    @Test
    void noEnglishTextSaysShip() throws IOException {
        Map<String, String> sayShip = new TreeMap<>();
        Properties english = properties(ENGLISH);
        for (String key : english.stringPropertyNames()) {
            String value = english.getProperty(key);
            for (String allowed : SHIP_ALLOWED) {
                value = value.replaceAll("(?i)" + Pattern.quote(allowed), "");
            }
            if (SHIP_WORD.matcher(value).find()) {
                sayShip.put(key, english.getProperty(key));
            }
        }
        assertThat(sayShip).as("English texts with the word \"ship\" or \"ships\": say \"vessel\" / \"vessels\", or add "
                + "the name to SHIP_ALLOWED").isEmpty();
        // The check itself: a text with the word is found, in any case; one with an allowed name, a longer word or
        // the key is not.
        assertThat(SHIP_WORD.matcher("Add SHIP").find()).isTrue();
        assertThat(SHIP_WORD.matcher("all ships.").find()).isTrue();
        assertThat(SHIP_WORD.matcher("a vessel's crew, shipping, relationship").find()).isFalse();
        // The Greek texts keep their words (Πλοίο / Πλοία), as they were.
        assertThat(properties(GREEK).getProperty("nav.ships")).isEqualTo("Πλοία");
        assertThat(english.getProperty("nav.ships").toLowerCase(Locale.ROOT)).isEqualTo("vessels");
    }

    @Test
    // In Greek the Safety Score is "Δείκτης ασφάλειας πλοίου" ("Δείκτης ασφάλειας" where space is tight), never
    // the English name in any case; never typed in capitals (table headers are uppercase through CSS). English keeps
    // RightShip's name.
    void greekSaysDeiktisAsfaleiasNotSafetyScore() throws IOException {
        Properties greek = properties(GREEK);
        Map<String, String> english = new TreeMap<>();
        for (String key : greek.stringPropertyNames()) {
            String value = greek.getProperty(key);
            if (value.toLowerCase(Locale.ROOT).contains("safety score")) {
                english.put(key, value);
            }
            assertThat(value).as(key).doesNotContain("ΔΕΙΚΤΗΣ", "ΑΣΦΑΛΕΙΑΣ");
        }
        assertThat(english).as("Greek texts that still say Safety Score").isEmpty();
        assertThat(greek.getProperty("vetting.score")).isEqualTo("Δείκτης ασφάλειας πλοίου");
        assertThat(greek.getProperty("vetting.score.title")).isEqualTo("Δείκτης ασφάλειας πλοίου RightShip");
        assertThat(greek.getProperty("vetting.score.sanctioned")).isEqualTo("Χωρίς δείκτη ασφάλειας πλοίου");
        assertThat(properties(ENGLISH).getProperty("vetting.score")).isEqualTo("Safety Score");
    }

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

    /** Where the keys are used: the Java code, the templates, the scripts and the configuration. */
    private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("src/main/resources/templates"),
            Path.of("src/main/resources/static/js"), Path.of("src/main/resources/application.yml"));

    /**
     * Keys built at run time in a way the check below cannot see. Every other key built from a prefix uses a
     * literal that ends with a dot ('rank.' + rank) or is followed by + ("dashboard.setup." + key), and those count.
     */
    private static final List<String> BUILT_AT_RUN_TIME = List.of(
            // Spring's codes for a value that does not convert (typeMismatch.<type>).
            "typeMismatch.",
            // shiporder/parts :: officeChange(line, keys) and receivedShort(line, keys): keys + '.added', ...
            "officeorder.change.", "order.officeChange.", "officeorder.receipt.notDelivered", "officeorder.receipt.shortBy",
            // OfficeInvoiceController: successKey + "Stock".
            "officeorder.compare.factor.changedStock", "officeorder.compare.match.changedStock");

    /** ImportFlow.key(suffix): the import kinds' texts, messagePrefix + "." + suffix. */
    private static final List<String> IMPORT_PREFIXES = List.of("crewImport.", "itemImport.");

    private static final Pattern PREFIX_LITERAL = Pattern.compile(
            "[\"']([A-Za-z][A-Za-z0-9_.]*\\.)[\"']|[\"']([A-Za-z][A-Za-z0-9_.]*)[\"']\\s*\\+");
    private static final Pattern IMPORT_KEY = Pattern.compile("\\.key\\([\"']([A-Za-z0-9_.]+)[\"']\\)");

    @Test
    void everyKeyIsUsed() throws IOException {
        StringBuilder text = new StringBuilder();
        for (Path source : SOURCES) {
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        String code = text.toString();
        Set<String> prefixes = new TreeSet<>(BUILT_AT_RUN_TIME);
        var literals = PREFIX_LITERAL.matcher(code);
        while (literals.find()) {
            prefixes.add(literals.group(1) != null ? literals.group(1) : literals.group(2));
        }
        Set<String> imported = new TreeSet<>();
        var importKeys = IMPORT_KEY.matcher(code);
        while (importKeys.find()) {
            IMPORT_PREFIXES.forEach(prefix -> imported.add(prefix + importKeys.group(1)));
        }

        // Every dotted word of the code, and each of its shorter forms (a.b.c gives a.b.c, a.b and a).
        Set<String> words = new java.util.HashSet<>();
        var word = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*").matcher(code);
        while (word.find()) {
            String w = word.group();
            for (int dot = w.length(); dot > 0; dot = w.lastIndexOf('.', dot - 1)) {
                if (!words.add(w.substring(0, dot))) {
                    break;
                }
            }
        }

        Set<String> unused = new TreeSet<>();
        Set<String> all = new TreeSet<>(keys(ENGLISH));
        all.addAll(keys(GREEK));
        for (String key : all) {
            boolean literal = words.contains(key);
            boolean built = imported.contains(key)
                    || prefixes.stream().anyMatch(prefix -> prefix.length() > 3 && key.startsWith(prefix));
            if (!literal && !built) {
                unused.add(key);
            }
        }
        assertThat(unused).as("message keys no template, class or script uses: remove them from both files, or add "
                + "their prefix to BUILT_AT_RUN_TIME if they are built in a way this check cannot see").isEmpty();
    }

    private static Set<String> keys(Path file) throws IOException {
        return properties(file).stringPropertyNames();
    }

    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
