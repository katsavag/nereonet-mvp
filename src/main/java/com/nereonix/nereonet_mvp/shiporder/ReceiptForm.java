package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;

/**
 * The Master's "Confirm receipt" form (P51): per line the quantity received (prefilled with the quantity ordered)
 * or "Not delivered", and an optional note; the date and time received (UTC, now to start with); the files of the
 * delivery receipt to add, and the ones already uploaded to remove. The review page carries the same fields, without
 * the files, in hidden fields. Checked by OrderReceiptController (the lines must be the order's).
 */
public class ReceiptForm {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** One line of the order: its id, what arrived ("25", "2.5" or "2,5") or "Not delivered", and a note. */
    public static class Line {
        private Long lineId;
        private String received;
        private boolean notDelivered;
        private String note;

        public Long getLineId() { return lineId; }
        public void setLineId(Long lineId) { this.lineId = lineId; }

        public String getReceived() { return received; }
        public void setReceived(String received) { this.received = received == null ? null : received.strip(); }

        public boolean isNotDelivered() { return notDelivered; }
        public void setNotDelivered(boolean notDelivered) { this.notDelivered = notDelivered; }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note == null ? null : note.strip(); }

        /** The quantity received: 0 when not delivered; null when it is not a number. */
        public BigDecimal quantity() {
            if (notDelivered) {
                return BigDecimal.ZERO;
            }
            if (received == null || received.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(received.replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private List<Line> lines = new ArrayList<>();

    // The date and time received, UTC, as a datetime-local input sends it ("2026-11-14T10:30").
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime receivedAt;

    private List<MultipartFile> files = new ArrayList<>();

    private List<Long> removeFileIds = new ArrayList<>();

    /** The form as it opens: every line not removed by the office, received as ordered, now (UTC, to the minute). */
    static ReceiptForm of(ShipOrder order) {
        ReceiptForm form = new ReceiptForm();
        for (ShipOrderLine orderLine : ShipOrderService.byCategoryAndName(order.getLines())) {
            if (orderLine.isRemoved()) {
                continue;
            }
            Line line = new Line();
            line.setLineId(orderLine.getId());
            line.setReceived(orderLine.getQuantity().stripTrailingZeros().toPlainString());
            form.lines.add(line);
        }
        form.receivedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        return form;
    }

    /** The datetime-local input's value: "2026-11-14T10:30"; empty when not set. */
    public String getReceivedAtValue() {
        return receivedAt == null ? "" : INPUT.format(receivedAt);
    }

    /** The time received as an instant (the form's time is UTC); null when not set. */
    public Instant getReceivedAtInstant() {
        return receivedAt == null ? null : receivedAt.toInstant(ZoneOffset.UTC);
    }

    /** The newest a receipt time can be, for the input's max: today (UTC) at 23:59. */
    public String getReceivedAtMax() {
        return INPUT.format(LocalDateTime.now(ZoneOffset.UTC).toLocalDate().atTime(23, 59));
    }

    /** The files chosen in this request (empty parts left out). */
    List<MultipartFile> chosenFiles() {
        return files == null ? List.of() : files.stream().filter(Objects::nonNull).filter(f -> !f.isEmpty()).toList();
    }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }

    public List<MultipartFile> getFiles() { return files; }
    public void setFiles(List<MultipartFile> files) { this.files = files; }

    public List<Long> getRemoveFileIds() { return removeFileIds; }
    public void setRemoveFileIds(List<Long> removeFileIds) { this.removeFileIds = removeFileIds; }
}
