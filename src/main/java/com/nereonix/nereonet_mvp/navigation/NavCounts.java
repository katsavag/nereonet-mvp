package com.nereonix.nereonet_mvp.navigation;

/**
 * The counters of the office sidebar. Unread notifications come from NotificationBellAdvice.
 * certificates: expired or expiring within 30 days; orders: sent by the ships and not exported (P36).
 */
public record NavCounts(long certificates, long orders) {
}
