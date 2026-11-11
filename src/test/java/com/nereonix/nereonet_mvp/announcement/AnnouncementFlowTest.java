package com.nereonix.nereonet_mvp.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class AnnouncementFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AnnouncementRepository announcementRepository;

    @Autowired
    AnnouncementRecipientRepository recipientRepository;

    @Test
    void recipientsAreFixedWhenTheAnnouncementIsSent() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        crewMember("anna@example.com", "Anna Acked", ship);
        crewMember("paul@example.com", "Paul Pending", ship);

        mockMvc.perform(post("/office/announcements/new")
                        .param("target", ship.getId().toString())
                        .param("title", "Safety drill")
                        .param("body", "Drill on Friday at 10:00.")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/announcements/" + announcementRepository.findAll().getFirst().getId()));

        Announcement announcement = announcementRepository.findAll().getFirst();
        assertThat(announcement.getShip()).isEqualTo(ship);
        assertThat(announcement.getCreatedBy().getEmail()).isEqualTo("office@example.com");

        // Joins after the announcement was sent: not a recipient.
        crewMember("lena@example.com", "Lena Later", ship);

        List<AnnouncementRecipient> recipients = recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement);
        assertThat(recipients).extracting(r -> r.getUser().getFullName()).containsExactly("Anna Acked", "Paul Pending");
        recipients.getFirst().setAcknowledgedAt(Instant.now());
        recipientRepository.flush();

        String html = mockMvc.perform(get("/office/announcements/" + announcement.getId()))
                .andReturn().getResponse().getContentAsString();
        int acknowledged = html.indexOf("id=\"acknowledged\"");
        int pending = html.indexOf("id=\"pending\"");
        assertThat(acknowledged).isPositive().isLessThan(pending);
        assertThat(html.substring(acknowledged, pending)).contains("Anna Acked").doesNotContain("Paul Pending");
        assertThat(html.substring(pending)).contains("Paul Pending").doesNotContain("Anna Acked");
        assertThat(html).doesNotContain("Lena Later");

        String list = mockMvc.perform(get("/office/announcements"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("Safety drill").contains("1 of 2 acknowledged");
    }

    private void crewMember(String email, String fullName, Ship ship) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-used");
        user.setFullName(fullName);
        user.setRole(Role.CREW_MEMBER);
        user.setShip(ship);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
    }
}
