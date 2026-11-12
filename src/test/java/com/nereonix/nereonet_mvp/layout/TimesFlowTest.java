package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P11: every time is shown in UTC and says so, the same text whatever the viewer's time zone (the script only adds
 * the viewer's own time as a tooltip); dates without a time have no zone; dates that drive action have a relative
 * hint computed on the server; one date format in English and Greek.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class TimesFlowTest {

    private static final Pattern LOCAL_TIME = Pattern.compile("<time class=\"local-time[^\"]*\" datetime=\"([^\"]+)\">([^<]*)</time>");
    private static final Pattern ANY_TIME = Pattern.compile("<time\\b[^>]*>([^<]*)</time>");
    private static final Pattern UTC_TEXT = Pattern.compile("\\d{1,2} \\S+ \\d{4} · \\d{2}:\\d{2} UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    ShipOrderRepository orderRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void timesAreShownInUtcWithTheZoneInBothLanguages() throws Exception {
        Ship ship = ship();
        User crew = crew(ship);
        order(ship, crew, Instant.parse("2026-10-21T12:40:00Z"));

        String english = html(get("/office/orders"));
        assertThat(english).contains("datetime=\"2026-10-21T12:40:00Z\">21 Oct 2026 · 12:40 UTC</time>")
                .contains("data-local-time=\"Your time: {time}\"");

        String greek = html(get("/office/orders").param("lang", "el"));
        assertThat(greek).contains("datetime=\"2026-10-21T12:40:00Z\">21 Οκτ 2026 · 12:40 UTC</time>")
                .contains("data-local-time=\"Η ώρα σας: {time}\"");
    }

    @Test
    void noTimestampWithoutUtc() throws Exception {
        Ship ship = ship();
        User crew = crew(ship);
        ShipOrder order = order(ship, crew, Instant.now().minus(Duration.ofDays(2)));
        certificate(crew, "STCW Basic Safety", 10);
        invitation(ship, Role.CREW_MEMBER, Instant.now().minus(Duration.ofDays(1)));

        int times = 0;
        for (String page : List.of("/office", "/office/orders", "/office/orders/" + order.getId(),
                "/office/audit", "/office/certificates", "/office/crew", "/office/ships/" + ship.getId())) {
            for (String lang : List.of("en", "el")) {
                String html = html(get(page).param("lang", lang));
                Matcher time = LOCAL_TIME.matcher(html);
                while (time.find()) {
                    times++;
                    assertThat(time.group(2)).as("time on " + page).matches(UTC_TEXT);
                    assertThat(time.group(1)).as("datetime on " + page).endsWith("Z");
                }
                // Every other <time> is a date without a time, and has no zone.
                Matcher any = ANY_TIME.matcher(html);
                while (any.find()) {
                    if (!any.group().contains("local-time")) {
                        assertThat(any.group(1)).as("date on " + page).doesNotContain("UTC").doesNotContain(":");
                    }
                }
            }
        }
        assertThat(times).isGreaterThan(5);
    }

    @Test
    void datesThatDriveActionHaveARelativeHint() throws Exception {
        Ship ship = ship();
        User crew = crew(ship);
        certificate(crew, "STCW Basic Safety", 23);
        certificate(crew, "Medical fitness", -5);
        invitation(null, Role.OFFICE_USER, Instant.now().plus(Duration.ofDays(2)));
        invitation(ship, Role.CREW_MEMBER, Instant.now().plus(Duration.ofDays(2)));

        // Certificate expiry: a date (no zone), then the hint.
        String certificates = html(get("/office/certificates"));
        assertThat(certificates)
                .contains("datetime=\"" + LocalDate.now(ZoneOffset.UTC).plusDays(23) + "\">" + date(23) + "</time>")
                .containsPattern("(?s)" + Pattern.quote(date(23) + "</time>") + "\\s*<span class=\"date-hint\"><span aria-hidden=\"true\">·</span> in 23 days</span>")
                .contains("<span aria-hidden=\"true\">·</span> expired 5 days ago</span>");
        assertThat(html(get("/office/crew/" + crew.getId() + "/edit"))).contains("Expires " + date(23), "in 23 days",
                "expired 5 days ago");
        assertThat(html(get("/office/ships/" + ship.getId()))).contains("Expires on " + date(-5), "expired 5 days ago");
        assertThat(html(get("/office"))).contains(date(-5) + "</time>", "expired 5 days ago");

        // Invitation expiry.
        assertThat(html(get("/office/users"))).contains("expires in 2 days");
        assertThat(html(get("/office/crew"))).contains("expires in 2 days");

        // Greek, from the same UTC date.
        assertThat(html(get("/office/certificates").param("lang", "el"))).contains("σε 23 ημέρες",
                "έληξε πριν από 5 ημέρες");
        assertThat(html(get("/office/users").param("lang", "el"))).contains("λήγει σε 2 ημέρες");
    }

    @Test
    void auditFilterIsInUtcDays() throws Exception {
        assertThat(html(get("/office/audit"))).contains(">From (UTC)</label>", ">To (UTC)</label>");
        assertThat(html(get("/office/audit").param("lang", "el"))).contains(">Από (UTC)</label>", ">Έως (UTC)</label>");
    }

    @Test
    void scriptAddsTheViewersTimeWithoutChangingTheText() throws Exception {
        String script = new ClassPathResource("static/js/local-time.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).contains("el.title = text", "visually-hidden", "data-local-time")
                .doesNotContain("el.textContent");
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String date(long days) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    private Ship ship() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private User crew(Ship ship) {
        return userService.createUser("crew.member@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.DECK_CADET);
    }

    private void certificate(User crew, String type, long daysLeft) {
        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType(type);
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(daysLeft));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
    }

    // An order the ship has sent (P36), without lines.
    private ShipOrder order(Ship ship, User sentBy, Instant sentAt) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.SENT);
        order.setSentBy(sentBy);
        order.setSentAt(sentAt);
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.ofInstant(sentAt, ZoneOffset.UTC).plusDays(7));
        order.setCreatedAt(sentAt.minus(Duration.ofHours(1)));
        return orderRepository.save(order);
    }

    private void invitation(Ship ship, Role role, Instant expiresAt) {
        Invitation invitation = new Invitation();
        invitation.setEmail(role.name().toLowerCase() + "." + UUID.randomUUID() + "@example.com");
        invitation.setFullName("Invited Person");
        invitation.setRole(role);
        invitation.setShip(ship);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedAt(expiresAt.minus(Duration.ofDays(7)));
        invitationRepository.save(invitation);
    }
}
