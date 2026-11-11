package com.nereonix.nereonet_mvp.core.library;

import java.time.Instant;

import org.hibernate.Length;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// One published version of a library item. Holds EITHER text OR one file, never both.
// The file is on disk (see LibraryFileStorage) under storagePath, a generated name.
@Entity
@Getter
@Setter
public class LibraryItemVersion {

    public static final int VERSION_LABEL_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    private LibraryItem item;

    // Free text, e.g. "1" or "2026-10".
    @Column(nullable = false, updatable = false, length = VERSION_LABEL_MAX_LENGTH)
    private String versionLabel;

    // Plain text. Null for a file version.
    @Column(updatable = false, length = Length.LONG32)
    private String text;

    // The four file fields are null for a text version.
    @Column(updatable = false)
    private String fileName;

    @Column(updatable = false)
    private String fileContentType;

    @Column(updatable = false)
    private Long fileSize;

    @Column(updatable = false)
    private String storagePath;

    // Null when that user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User publishedBy;

    @Column(nullable = false, updatable = false)
    private Instant publishedAt;

    public boolean isFile() {
        return storagePath != null;
    }
}
