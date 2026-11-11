package com.nereonix.nereonet_mvp.core.announcement;

// Number of recipients of one announcement and how many of them acknowledged it.
public record RecipientCount(Long announcementId, long recipients, long acknowledged) {
}
