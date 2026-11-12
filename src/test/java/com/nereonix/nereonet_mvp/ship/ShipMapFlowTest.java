package com.nereonix.nereonet_mvp.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

/**
 * Experiment (ShipMap): the "Position" card on the ship page tracks the ship on the VesselFinder map by its IMO number,
 * and app.ship-map.enabled=false removes the card and its external script.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ShipMapFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @MockitoBean
    MailService mailService;

    Ship demo;

    @BeforeEach
    void ship() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setActive(true);
        ship.setCreatedAt(Instant.now());
        demo = shipRepository.save(ship);
    }

    @Test
    void theShipPageShowsTheMapTrackingTheShipByItsImoNumber() throws Exception {
        String html = shipPage();
        assertThat(html).contains("id=\"shipMapTitle\">Position</h2>",
                "src=\"https://www.vesselfinder.com/aismap.js\"",
                "var imo=\"9074729\";",
                "Position data by VesselFinder");
    }

    @Nested
    @TestPropertySource(properties = "app.ship-map.enabled=false")
    class WhenSwitchedOff {

        @Test
        void theShipPageShowsNoMapAndLoadsNoExternalScript() throws Exception {
            assertThat(shipPage()).doesNotContain("shipMapTitle", "vesselfinder", "aismap.js", "var imo=");
        }
    }

    private String shipPage() throws Exception {
        return mockMvc.perform(get("/office/ships/{id}", demo.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
