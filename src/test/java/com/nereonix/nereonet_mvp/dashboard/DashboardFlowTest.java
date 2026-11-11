package com.nereonix.nereonet_mvp.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class DashboardFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    ItemRequestRepository requestRepository;

    @Test
    void shipWithApprovedRequestNotExportedNeedsAttention() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedAt(Instant.now());
        request.setCreatedAt(Instant.now());
        requestRepository.save(request);

        mockMvc.perform(get("/office"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("MV Demo")))
                .andExpect(content().string(Matchers.containsString("IMO 9074729")))
                .andExpect(content().string(Matchers.containsString("Needs attention")))
                .andExpect(content().string(Matchers.containsString(
                        "/office/requests?shipId=" + ship.getId() + "&amp;notExported=true")));
    }
}
