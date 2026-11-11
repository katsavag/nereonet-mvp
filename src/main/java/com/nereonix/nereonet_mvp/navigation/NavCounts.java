package com.nereonix.nereonet_mvp.navigation;

/**
 * The counters of the office sidebar. Unread notifications come from NotificationBellAdvice.
 * certificates: expired or expiring within 30 days; requests: approved and not exported.
 */
public record NavCounts(long certificates, long requests) {
}
