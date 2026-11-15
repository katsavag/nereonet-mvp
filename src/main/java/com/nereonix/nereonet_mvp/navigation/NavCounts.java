package com.nereonix.nereonet_mvp.navigation;

/**
 * The counters of the office sidebar. Unread notifications come from NotificationBellAdvice.
 * certificates: expired or expiring within 30 days; orders: Pending or Delivery scheduled (sent and not exported until then); vetting: active
 * vessels whose RightShip inspection is expired or expires within 60 days, or whose SIRE 2.0 report, operator
 * comments or observations need attention, each vessel once (VettingService.vesselsNeedingAttention).
 */
public record NavCounts(long certificates, long orders, long vetting) {
}
