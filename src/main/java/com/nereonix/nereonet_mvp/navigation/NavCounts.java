package com.nereonix.nereonet_mvp.navigation;

/**
 * The counters of the office sidebar. Unread notifications come from NotificationBellAdvice.
 * certificates: expired or expiring within 30 days; orders: sent by the ships and not exported (P36); vetting: active
 * vessels whose RightShip inspection is expired or expires within 60 days (P40), or whose SIRE 2.0 report, operator
 * comments or observations need attention (P41), each vessel once (VettingService.vesselsNeedingAttention).
 */
public record NavCounts(long certificates, long orders, long vetting) {
}
