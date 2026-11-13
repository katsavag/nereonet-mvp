package com.nereonix.nereonet_mvp.core.vetting;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
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
 * An official RightShip document of a vessel (P40): the report as RightShip issued it, a PDF, PNG or JPEG of at most
 * 10 MB, stored like the library's files (LibraryFileStorage) under a generated name; the uploaded name is only shown
 * and used for the download. The newest (by document date, then upload) is "the official document" beside the
 * dashboard; the others are listed under it. Never changed after the upload.
 */
@Entity
@Getter
@Setter
public class RightShipReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    @Column(nullable = false, length = 255)
    private String fileName;

    // application/pdf, image/png or image/jpeg, checked against the extension and the first bytes on upload.
    @Column(nullable = false, length = 50)
    private String fileContentType;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 36)
    private String storagePath;

    // The date on the document, as RightShip issued it.
    @Column(nullable = false)
    private LocalDate documentDate;

    // Null for the dev data, or once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User uploadedBy;

    @Column(nullable = false)
    private Instant uploadedAt;

    public boolean isPdf() {
        return "application/pdf".equals(fileContentType);
    }

    public boolean isImage() {
        return fileContentType.startsWith("image/");
    }
}
