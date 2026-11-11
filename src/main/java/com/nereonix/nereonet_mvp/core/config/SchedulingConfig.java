package com.nereonix.nereonet_mvp.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on @Scheduled jobs (see CertificateReminderService).
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
