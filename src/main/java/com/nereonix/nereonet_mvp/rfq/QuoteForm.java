package com.nereonix.nereonet_mvp.rfq;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.library.LibraryItemForm;
import com.nereonix.nereonet_mvp.core.rfq.RfqLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuote;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuoteLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqService;
import com.nereonix.nereonet_mvp.core.rfq.RfqWorkbook;
import com.nereonix.nereonet_mvp.core.rfq.Units;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// "Enter quote" for one supplier of an RFQ (P44): per line the unit price (of one pack), the quantity offered (in
// packs), the pack size and unit and a note; a line without a price is not offered. Then the quote's currency,
// delivery cost, delivery date and validity, and optionally the supplier's PDF (at most 10 MB, checked by its first
// bytes as P39). The rules that need the RFQ line (quantity with a price, a pack unit that compares with the item's)
// are checked by RfqController.checkLines.
public class QuoteForm {

    public static final long MAX_FILE_SIZE = LibraryItemForm.MAX_FILE_SIZE;

    private List<@Valid Line> lines = new ArrayList<>();

    @NotBlank(message = "{quote.error.currencyRequired}")
    @Pattern(regexp = "^\\s*[A-Za-z]{3}\\s*$", message = "{quote.error.currencyInvalid}")
    private String currency;

    @DecimalMin(value = "0", message = "{quote.error.amount}")
    @Digits(integer = 10, fraction = 2, message = "{quote.error.amount}")
    private BigDecimal deliveryCost;

    @NotNull(message = "{quote.error.deliveryDateRequired}")
    @PlausibleDate(yearsBefore = 1, yearsAfter = 2)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate deliveryDate;

    @PlausibleDate(yearsBefore = 1, yearsAfter = 2)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validUntil;

    private MultipartFile file;

    /** One line of the form, in the RFQ's order (by category and item name). */
    public static class Line {

        private Long lineId;

        @DecimalMin(value = "0", message = "{quote.error.amount}")
        @Digits(integer = 10, fraction = 4, message = "{quote.error.price}")
        private BigDecimal unitPrice;

        @DecimalMin(value = "0", inclusive = false, message = "{quote.error.quantity}")
        @Digits(integer = 9, fraction = 3, message = "{quote.error.quantity}")
        private BigDecimal quantityOffered;

        @DecimalMin(value = "0", inclusive = false, message = "{quote.error.packSize}")
        @Digits(integer = 9, fraction = 3, message = "{quote.error.packSize}")
        private BigDecimal packSize;

        @Size(max = Units.UNIT_MAX_LENGTH, message = "{quote.error.tooLong}")
        private String packUnit;

        @Size(max = RfqQuoteLine.NOTE_MAX_LENGTH, message = "{quote.error.tooLong}")
        private String note;

        public Long getLineId() { return lineId; }
        public void setLineId(Long lineId) { this.lineId = lineId; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getQuantityOffered() { return quantityOffered; }
        public void setQuantityOffered(BigDecimal quantityOffered) { this.quantityOffered = quantityOffered; }
        public BigDecimal getPackSize() { return packSize; }
        public void setPackSize(BigDecimal packSize) { this.packSize = packSize; }
        public String getPackUnit() { return packUnit; }
        public void setPackUnit(String packUnit) { this.packUnit = packUnit == null ? null : packUnit.strip(); }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    /** At least one line has a price; a supplier with nothing to offer is recorded as Declined instead. */
    @AssertTrue(message = "{quote.error.noLines}")
    public boolean isAnyLineOffered() {
        return lines.stream().anyMatch(line -> line.getUnitPrice() != null);
    }

    @AssertTrue(message = "{quote.error.fileType}")
    public boolean isFileTypeAllowed() {
        return !hasFile() || "application/pdf".equals(CertificateFileStorage.contentTypeOf(file));
    }

    @AssertTrue(message = "{quote.error.fileTooLarge}")
    public boolean isFileSizeAllowed() {
        return !hasFile() || file.getSize() <= MAX_FILE_SIZE;
    }

    public boolean hasFile() {
        return file != null && !file.isEmpty();
    }

    public long getMaxFileSize() {
        return MAX_FILE_SIZE;
    }

    /**
     * The form as it opens: the quote as entered before, or for a new one the supplier's currency and each line's
     * quantity asked (one pack of the item's unit), prices empty.
     */
    static QuoteForm of(RfqQuote quote) {
        QuoteForm form = new QuoteForm();
        form.setCurrency(quote.getCurrency() != null ? quote.getCurrency() : quote.getSupplier().getCurrency());
        boolean quoted = quote.isQuoted();
        form.setDeliveryCost(quoted ? quote.getDeliveryCost() : null);
        form.setDeliveryDate(quoted ? quote.getDeliveryDate() : null);
        form.setValidUntil(quoted ? quote.getValidUntil() : null);
        for (RfqLine rfqLine : RfqWorkbook.byCategoryAndName(quote.getRfq().getLines())) {
            Line line = new Line();
            line.setLineId(rfqLine.getId());
            RfqQuoteLine entered = quoted ? quote.lineFor(rfqLine).orElse(null) : null;
            if (entered != null) {
                line.setUnitPrice(entered.getUnitPrice().stripTrailingZeros());
                line.setQuantityOffered(entered.getQuantityOffered().stripTrailingZeros());
                line.setPackSize(entered.getPackSize() == null ? null : entered.getPackSize().stripTrailingZeros());
                line.setPackUnit(entered.getPackUnit());
                line.setNote(entered.getNote());
            } else {
                line.setQuantityOffered(rfqLine.getQuantity().stripTrailingZeros());
            }
            form.getLines().add(line);
        }
        return form;
    }

    RfqService.QuoteEntry entry() {
        List<RfqService.LineEntry> entries = lines.stream()
                .map(l -> new RfqService.LineEntry(l.getLineId(), l.getUnitPrice(), l.getQuantityOffered(),
                        l.getPackSize(), l.getPackUnit(), l.getNote()))
                .toList();
        return new RfqService.QuoteEntry(currency, deliveryCost, deliveryDate, validUntil, entries,
                hasFile() ? RfqService.file(file) : null);
    }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getDeliveryCost() { return deliveryCost; }
    public void setDeliveryCost(BigDecimal deliveryCost) { this.deliveryCost = deliveryCost; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
}
