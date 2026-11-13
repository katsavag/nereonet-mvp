package com.nereonix.nereonet_mvp.core.certificate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.time.Dates;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// Daily job: reminds crew members (email and in-app notification) and the office (email)
// 90, 60 and 30 days before a certificate expires. Texts are English (crew screens are English only).
@Service
public class CertificateReminderService {

    private static final Logger log = LoggerFactory.getLogger(CertificateReminderService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(Dates.DATE_PATTERN, Locale.ENGLISH);

    private final CertificateRepository certificateRepository;
    private final CertificateReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final MessageSource messageSource;
    private final AuditService auditService;
    private final TransactionTemplate transaction;

    public CertificateReminderService(CertificateRepository certificateRepository,
                                      CertificateReminderRepository reminderRepository,
                                      UserRepository userRepository,
                                      NotificationService notificationService,
                                      MailService mailService,
                                      MessageSource messageSource,
                                      AuditService auditService,
                                      PlatformTransactionManager transactionManager) {
        this.certificateRepository = certificateRepository;
        this.reminderRepository = reminderRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.mailService = mailService;
        this.messageSource = messageSource;
        this.auditService = auditService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * For every not yet expired certificate of an enabled user, finds the smallest threshold reached
     * (days left <= 90, 60 or 30). If it has no reminder row, sends the reminder and saves rows for it
     * and every larger threshold, so a certificate entered late gets one reminder, not three.
     * A certificate whose snooze holds (P43) is skipped: no reminder rows, so once the snooze ends the reminder of
     * the threshold reached by then is sent.
     * Each certificate has its own transaction: a failed email does not undo the reminders already sent,
     * and the failed one is tried again on the next run.
     */
    @Scheduled(cron = "${app.certificates.reminder-cron}", zone = "UTC")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Long> dueIds = transaction.execute(status -> certificateRepository
                .findDueForReminder(today, today.plusDays(Certificate.REMINDER_DAYS.getLast()), today.plusDays(30),
                        today.plusDays(60), AlertKind.CERTIFICATE_EXPIRED, AlertKind.CERTIFICATE_EXPIRING)
                .stream().map(Certificate::getId).toList());
        for (Long id : dueIds) {
            try {
                transaction.executeWithoutResult(status -> remind(certificateRepository.findById(id).orElseThrow(), today));
            } catch (RuntimeException e) {
                log.error("Certificate reminder failed for certificate {}", id, e);
            }
        }
    }

    private void remind(Certificate certificate, LocalDate today) {
        Integer threshold = certificate.reminderThreshold(today);
        if (threshold == null || reminderRepository.existsFor(certificate, threshold)) {
            return;
        }
        Instant now = Instant.now();
        for (int days : Certificate.REMINDER_DAYS) {
            if (days >= threshold && !reminderRepository.existsFor(certificate, days)) {
                CertificateReminder reminder = new CertificateReminder();
                reminder.setCertificate(certificate);
                reminder.setDaysBefore(days);
                reminder.setSentAt(now);
                reminderRepository.save(reminder);
            }
        }

        User member = certificate.getUser();
        long daysLeft = certificate.daysLeft(today);
        String expiresOn = DATE.format(certificate.getExpiresOn());
        String crewText = message("certificate.reminder.crew.text", certificate.getType(), daysLeft, expiresOn);
        // Names the person and the ship it concerns (P23); opens nothing: the crew have no certificate page yet.
        String subject = member.getShip() == null ? member.getFullName()
                : member.getFullName() + " · " + member.getShip().getName();
        notificationService.notify(member, NotificationType.CERTIFICATE, subject, crewText, null);
        mailService.send(member.getEmail(), message("certificate.reminder.crew.subject", certificate.getType()),
                message("certificate.reminder.crew.email", member.getFullName(), crewText));

        String officeSubject = message("certificate.reminder.office.subject", member.getFullName(),
                certificate.getType());
        String officeText = message("certificate.reminder.office.email", certificate.getType(),
                certificate.getNumber() == null ? "-" : certificate.getNumber(), member.getFullName(),
                member.getShip() == null ? "-" : member.getShip().getName(), daysLeft, expiresOn);
        for (User office : userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER)) {
            if (office.isEnabled()) {
                mailService.send(office.getEmail(), officeSubject, officeText);
            }
        }

        // No actor: sent by the daily job.
        auditService.record("CERTIFICATE_REMINDER_SENT", member.getShip(),
                member.getEmail() + ": " + certificate.getType() + ", expires " + certificate.getExpiresOn()
                        + ", " + threshold + "-day reminder", null);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, Locale.ENGLISH);
    }
}
