package com.nereonix.nereonet_mvp.core.library;

import java.time.Instant;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// A crew member confirmed they read one version of a library item.
// Deleted together with the user (database cascade).
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"version_id", "user_id"}))
@Getter
@Setter
public class LibraryAcknowledgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    private LibraryItemVersion version;

    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, updatable = false)
    private Instant acknowledgedAt;
}
