package com.nereonix.nereonet_mvp.core.certificate;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// Marks that the reminder for one threshold (90, 60 or 30 days before expiry) of a certificate is done.
// Rows for larger thresholds are also saved when a smaller one is sent, without sending them.
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"certificate_id", "days_before"}))
@Getter
@Setter
public class CertificateReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Certificate certificate;

    @Column(nullable = false)
    private int daysBefore;

    @Column(nullable = false)
    private Instant sentAt;
}
