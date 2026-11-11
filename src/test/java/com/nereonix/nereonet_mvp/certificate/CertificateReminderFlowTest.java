package com.nereonix.nereonet_mvp.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateReminder;
import com.nereonix.nereonet_mvp.core.certificate.CertificateReminderRepository;
import com.nereonix.nereonet_mvp.core.certificate.CertificateReminderService;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CertificateReminderFlowTest {

    @Autowired
    CertificateReminderService reminderService;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    CertificateReminderRepository reminderRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void certificateExpiringIn60DaysGetsOneReminder() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        User crew = new User();
        crew.setEmail("crew@example.com");
        crew.setPasswordHash("not-used");
        crew.setFullName("Crew Member");
        crew.setRole(Role.CREW_MEMBER);
        crew.setShip(ship);
        crew.setEnabled(true);
        crew.setCreatedAt(Instant.now());
        userRepository.save(crew);

        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW Basic Safety");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(60));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);

        reminderService.sendDueReminders();

        verify(mailService, times(1)).send(eq("crew@example.com"), anyString(),
                contains("Your certificate STCW Basic Safety expires in 60 days"));
        // office@example.com is seeded by BootstrapData.
        verify(mailService, times(1)).send(eq("office@example.com"), anyString(), contains("Crew Member"));

        List<Notification> notifications = notificationRepository
                .findAllByUserEmailOrderByCreatedAtDescIdDesc("crew@example.com");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getReadAt()).isNull();
        assertThat(notifications.getFirst().getText()).startsWith("Your certificate STCW Basic Safety expires in 60 days");

        // 60 sent, 90 saved without sending.
        assertThat(reminderRepository.findAll()).extracting(CertificateReminder::getDaysBefore)
                .containsExactlyInAnyOrder(60, 90);

        clearInvocations(mailService);
        reminderService.sendDueReminders();

        verifyNoInteractions(mailService);
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc("crew@example.com"))
                .hasSize(1);
    }
}
