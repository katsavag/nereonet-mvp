package com.nereonix.nereonet_mvp.core.vetting;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
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
 * A SIRE 2.0 inspection of a vessel (P41), entered by hand from the report: when and where, who commissioned it (the
 * submitting company, a charterer or oil company), the report's reference and status, and the report itself (a PDF,
 * stored like the library's files, LibraryFileStorage, under a generated name; the uploaded name is only shown and
 * used for the download). A vessel has many; the latest (by inspection date, then id) is "its" SIRE 2.0 standing.
 */
@Entity
@Getter
@Setter
public class SireInspection {

    public static final int PORT_MAX_LENGTH = 100;
    public static final int COMPANY_MAX_LENGTH = 150;
    public static final int INSPECTOR_MAX_LENGTH = 100;
    public static final int REFERENCE_MAX_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    @Column(nullable = false)
    private LocalDate inspectionDate;

    @Column(nullable = false, length = PORT_MAX_LENGTH)
    private String port;

    // The charterer or oil company that commissioned the inspection.
    @Column(nullable = false, length = COMPANY_MAX_LENGTH)
    private String submittingCompany;

    @Column(length = INSPECTOR_MAX_LENGTH)
    private String inspectorName;

    @Column(length = REFERENCE_MAX_LENGTH)
    private String reportReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SireStatus status;

    // When the report was published (or made available to the operator). While the status is Draft received, the
    // operator comments are due SireSettings.commentDays after it, or after the inspection date while it is empty.
    private LocalDate publishedOn;

    // The report: all null until it is uploaded. Always a PDF.
    @Column(length = 255)
    private String reportFileName;

    private Long reportFileSize;

    @Column(length = 36)
    private String reportStoragePath;

    private Instant reportUploadedAt;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User reportUploadedBy;

    // Null for the dev data, or once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean hasReport() {
        return reportStoragePath != null;
    }
}
