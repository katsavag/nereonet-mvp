package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.web.multipart.MultipartFile;

import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;

/**
 * The Chief cook's delivery count (P61): per line what arrived ("Received", empty until counted; 0 when nothing
 * arrived) and an optional note; one optional note to the Master; photos of the delivery note to add, and his own
 * uploaded ones to remove. Checked by DeliveryCountController (the lines must be the order's).
 */
public class DeliveryCountForm {

    /** One line of the order: its id, what arrived ("25", "2.5" or "2,5"; empty: not counted yet) and a note. */
    public static class Line {
        private Long lineId;
        private String counted;
        private String note;

        public Long getLineId() { return lineId; }
        public void setLineId(Long lineId) { this.lineId = lineId; }

        public String getCounted() { return counted; }
        public void setCounted(String counted) { this.counted = counted == null ? null : counted.strip(); }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note == null ? null : note.strip(); }

        /** Nothing typed: not counted yet. */
        public boolean isBlank() {
            return counted == null || counted.isEmpty();
        }
    }

    private List<Line> lines = new ArrayList<>();

    private String note;

    private List<MultipartFile> files = new ArrayList<>();

    private List<Long> removeFileIds = new ArrayList<>();

    /** The form as saved on the order: every line not removed by the office, with the count so far (empty at first). */
    static DeliveryCountForm of(ShipOrder order) {
        DeliveryCountForm form = new DeliveryCountForm();
        for (ShipOrderLine orderLine : ShipOrderService.byCategoryAndName(order.getLines())) {
            if (orderLine.isRemoved()) {
                continue;
            }
            Line line = new Line();
            line.setLineId(orderLine.getId());
            BigDecimal counted = orderLine.getCountedQuantity();
            line.setCounted(counted == null ? null : counted.stripTrailingZeros().toPlainString());
            line.setNote(orderLine.getCountNote());
            form.lines.add(line);
        }
        form.note = order.getCountNote();
        return form;
    }

    /** The files chosen in this request (empty parts left out). */
    List<MultipartFile> chosenFiles() {
        return files == null ? List.of() : files.stream().filter(Objects::nonNull).filter(f -> !f.isEmpty()).toList();
    }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null ? null : note.strip(); }

    public List<MultipartFile> getFiles() { return files; }
    public void setFiles(List<MultipartFile> files) { this.files = files; }

    public List<Long> getRemoveFileIds() { return removeFileIds; }
    public void setRemoveFileIds(List<Long> removeFileIds) { this.removeFileIds = removeFileIds; }
}
