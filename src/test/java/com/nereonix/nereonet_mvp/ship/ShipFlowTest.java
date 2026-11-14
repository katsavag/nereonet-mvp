package com.nereonix.nereonet_mvp.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ShipFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Test
    void officeUserAddsShip() throws Exception {
        mockMvc.perform(post("/office/ships/new")
                        .param("imoNumber", "9074729")
                        .param("name", "MV Aegean Spirit")
                        .param("type", "BULK_CARRIER")
                        .param("flag", "Greece (GR)")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));

        List<Ship> ships = shipRepository.findAll();
        assertThat(ships).hasSize(1);
        Ship ship = ships.getFirst();
        assertThat(ship.getImoNumber()).isEqualTo("9074729");
        assertThat(ship.getName()).isEqualTo("MV Aegean Spirit");
        assertThat(ship.getType()).isEqualTo(ShipType.BULK_CARRIER);
        assertThat(ship.getFlag()).isEqualTo("GR");
        assertThat(ship.isActive()).isTrue();
        assertThat(ship.getCreatedAt()).isNotNull();

        mockMvc.perform(get("/office/ships"))
                .andExpect(content().string(Matchers.containsString("IMO 9074729")))
                .andExpect(content().string(Matchers.containsString("MV Aegean Spirit")));
    }

    // P45: without a VesselAPI key (the test profile has none) no lookup or refresh is offered, a note says why, and
    // the lookup action makes no call (VesselLookupFlowTest covers the configured case).
    @Test
    void withoutVesselApiTheLookupIsHiddenAndExplained() throws Exception {
        mockMvc.perform(get("/office/ships/new"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("formaction=\"/office/ships/new/lookup\""))))
                .andExpect(content().string(Matchers.containsString("The VesselAPI integration is not configured")));
        mockMvc.perform(post("/office/ships/new/lookup").param("imoNumber", "9074729").param("name", "Typed")
                        .with(csrf()))
                .andExpect(content().string(Matchers.containsString("value=\"Typed\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("VesselAPI result"))));
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(java.time.Instant.now());
        shipRepository.save(ship);
        mockMvc.perform(get("/office/ships/" + ship.getId() + "/edit"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/edit/lookup\""))))
                .andExpect(content().string(Matchers.containsString("The VesselAPI integration is not configured")));
        mockMvc.perform(get("/office/ships/" + ship.getId() + "/vessel"))
                .andExpect(content().string(Matchers.containsString("No VesselAPI data yet")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/edit/lookup\""))));
    }
}
