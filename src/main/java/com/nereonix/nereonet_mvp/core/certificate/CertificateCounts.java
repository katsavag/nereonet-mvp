package com.nereonix.nereonet_mvp.core.certificate;

// Certificates of one ship's users: expired, and expiring within 30, 31-60 and 61-90 days (as the badges).
public record CertificateCounts(Long shipId, long expired, long within30, long within60, long within90) {
}
