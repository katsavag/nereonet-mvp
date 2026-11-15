package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * A file of the delivery receipt of an order: a PDF, JPEG or PNG of at most 10 MB, stored like the library's
 * files (LibraryFileStorage) under a generated name; the uploaded name is only shown and used for the download.
 * The Master uploads them while the order is CONFIRMED (they wait there, only the Master sees them, and can be removed
 * again); confirming receipt closes the order and from then on they are its receipt, never changed.
 * The pages of the supplier's invoice are receipt files too (invoicePage), and may also be JPEG, PNG, WebP or
 * HEIC images.
 */
@Entity
@Getter
@Setter
public class OrderReceiptFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ShipOrder order;

    @Column(nullable = false, length = 255)
    private String fileName;

    // application/pdf, image/png or image/jpeg, checked against the extension and the first bytes on upload (an
    // invoice page also image/webp or image/heic, checked by its first bytes).
    @Column(nullable = false, length = 50)
    private String fileContentType;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 36)
    private String storagePath;

    // Null once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User uploadedBy;

    @Column(nullable = false)
    private Instant uploadedAt;

    // A page of the supplier's invoice, photographed on "Photograph the invoice": its page number (1, 2, ...,
    // without gaps), where its reading stands, the page total it shows and whether its checks failed ("check the
    // lines"). All null for any other receipt file. Nullable columns: Hibernate adds them.
    private Integer invoicePage;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InvoicePageStatus invoicePageStatus;

    @Column(precision = 14, scale = 2)
    private BigDecimal invoicePageTotal;

    private Boolean invoicePageCheck;

    public boolean isPdf() {
        return "application/pdf".equals(fileContentType);
    }

    /** P62: a page of the invoice. */
    public boolean isInvoicePage() {
        return invoicePage != null;
    }

    /** P62: an image the browser can show as a thumbnail (HEIC only Safari shows: the others get the page's name). */
    public boolean isImage() {
        return fileContentType != null && fileContentType.startsWith("image/");
    }
}
