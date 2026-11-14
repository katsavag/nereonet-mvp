package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * A file of the delivery receipt of an order (P51): a PDF, JPEG or PNG of at most 10 MB, stored like the library's
 * files (LibraryFileStorage) under a generated name; the uploaded name is only shown and used for the download.
 * The Master uploads them while the order is CONFIRMED (they wait there, only the Master sees them, and can be removed
 * again); confirming receipt closes the order and from then on they are its receipt, never changed.
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

    // application/pdf, image/png or image/jpeg, checked against the extension and the first bytes on upload.
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

    public boolean isPdf() {
        return "application/pdf".equals(fileContentType);
    }
}
